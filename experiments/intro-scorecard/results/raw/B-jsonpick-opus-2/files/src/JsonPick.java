import java.io.ByteArrayOutputStream;
import java.io.FileInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** jsonpick: extract a value by path from JSON. See SPEC.md. */
public final class JsonPick {

    static final int EXIT_OK = 0;
    static final int EXIT_NOT_FOUND = 1;
    static final int EXIT_USAGE = 2;

    static final String HELP = String.join("\n",
            "Usage: jsonpick PATH [FILE] [--shape] [--depth N]",
            "",
            "Extract a value by path from JSON.",
            "",
            "Arguments:",
            "  PATH         Path to select, e.g. a.b[0].c  (\".\" or \"\" selects the root)",
            "  FILE         JSON input file; omitted or \"-\" reads stdin",
            "",
            "Options:",
            "  --shape      Print a depth-limited type summary of the selected value",
            "  --depth N    Levels to describe in --shape mode (default 2)",
            "  --help       Show this help and exit",
            "");

    /** Signals termination with a given exit code and optional stderr message. */
    static final class Exit extends RuntimeException {
        final int code;
        final String message;

        Exit(int code, String message) {
            super(message, null, false, false);
            this.code = code;
            this.message = message;
        }
    }

    // ---------------------------------------------------------------- JSON model

    /** Number value retaining its source literal. */
    record JsonNumber(String literal) {}

    /** Marker for JSON null (Java null is avoided so lookups stay unambiguous). */
    enum JsonNull { INSTANCE }

    // ---------------------------------------------------------------- entry point

    public static void main(String[] args) {
        int[] code = {EXIT_OK};
        // Run on a thread with a large stack so deeply nested documents don't overflow.
        Thread t = new Thread(null, () -> code[0] = run(args), "jsonpick", 1L << 30);
        t.start();
        try {
            t.join();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
        System.exit(code[0]);
    }

    static int run(String[] args) {
        OutputStream out = System.out;
        OutputStream err = System.err;
        try {
            String result = execute(args);
            write(out, result);
            return EXIT_OK;
        } catch (Exit e) {
            if (e.message != null) {
                write(err, e.message + "\n");
            }
            return e.code;
        }
    }

    static void write(OutputStream stream, String s) {
        try {
            stream.write(s.getBytes(StandardCharsets.UTF_8));
            stream.flush();
        } catch (IOException ignored) {
            // e.g. closed pipe; nothing useful to report
        }
    }

    /** Parses arguments, runs the command and returns the text to print on stdout. */
    static String execute(String[] args) {
        String path = null;
        String file = null;
        boolean shape = false;
        int depth = 2;
        int positional = 0;

        for (int i = 0; i < args.length; i++) {
            String arg = args[i];
            if (arg.equals("--help")) {
                return HELP;
            } else if (arg.equals("--shape")) {
                shape = true;
            } else if (arg.equals("--depth") || arg.startsWith("--depth=")) {
                String value;
                if (arg.equals("--depth")) {
                    if (i + 1 >= args.length) {
                        throw usage("missing value for --depth");
                    }
                    value = args[++i];
                } else {
                    value = arg.substring("--depth=".length());
                }
                depth = parseDepth(value);
            } else if (arg.length() > 1 && arg.startsWith("-")) {
                throw new Exit(EXIT_USAGE, "unknown flag: " + arg);
            } else {
                if (positional == 0) {
                    path = arg;
                } else if (positional == 1) {
                    file = arg;
                } else {
                    throw usage("unexpected argument: " + arg);
                }
                positional++;
            }
        }
        if (path == null) {
            throw usage("missing required argument: PATH");
        }

        List<Object> steps = parsePath(path);
        byte[] input = readInput(file);
        Object root = new Parser(input).parseDocument();
        Object value = resolve(root, steps, path);

        if (shape) {
            StringBuilder sb = new StringBuilder();
            shapeOf(value, depth, sb);
            return sb.append('\n').toString();
        }
        if (value instanceof String s) {
            return s + "\n";
        }
        StringBuilder sb = new StringBuilder();
        writeCompact(value, sb);
        return sb.append('\n').toString();
    }

    static Exit usage(String message) {
        return new Exit(EXIT_USAGE, "error: " + message);
    }

    static int parseDepth(String value) {
        if (value.isEmpty() || !value.chars().allMatch(c -> c >= '0' && c <= '9')) {
            throw usage("invalid value for --depth: " + value);
        }
        try {
            return Integer.parseInt(value);
        } catch (NumberFormatException e) {
            throw usage("invalid value for --depth: " + value);
        }
    }

    static byte[] readInput(String file) {
        try {
            if (file == null || file.equals("-")) {
                return readAll(System.in);
            }
            try (InputStream in = new FileInputStream(file)) {
                return readAll(in);
            }
        } catch (IOException e) {
            String name = file == null ? "-" : file;
            throw usage("cannot read file: " + name);
        }
    }

    static byte[] readAll(InputStream in) throws IOException {
        ByteArrayOutputStream buf = new ByteArrayOutputStream();
        in.transferTo(buf);
        return buf.toByteArray();
    }

    // ---------------------------------------------------------------- paths

    /** Returns a list of String (object key) and Integer (array index) steps. */
    static List<Object> parsePath(String path) {
        List<Object> steps = new ArrayList<>();
        int i = 0;
        int n = path.length();
        if (i < n && path.charAt(i) == '.') {
            i++; // leading dot selects the root
        }
        boolean expectKey = true; // at the start or right after a '.'
        boolean first = true;
        while (i < n) {
            char c = path.charAt(i);
            if (c == '[') {
                int close = path.indexOf(']', i);
                if (close < 0) {
                    throw usage("invalid path: " + path);
                }
                String digits = path.substring(i + 1, close);
                if (digits.isEmpty() || !digits.chars().allMatch(d -> d >= '0' && d <= '9')) {
                    throw usage("invalid path: " + path);
                }
                int index;
                try {
                    index = Integer.parseInt(digits);
                } catch (NumberFormatException e) {
                    index = Integer.MAX_VALUE; // can never be in range
                }
                steps.add(index);
                i = close + 1;
                expectKey = false;
            } else if (c == '.') {
                if (expectKey) {
                    throw usage("invalid path: " + path); // empty key, e.g. "a..b"
                }
                i++;
                expectKey = true;
                if (i == n) {
                    throw usage("invalid path: " + path); // trailing dot
                }
            } else {
                if (!expectKey && !first) {
                    throw usage("invalid path: " + path); // e.g. "a[0]b"
                }
                int start = i;
                while (i < n && path.charAt(i) != '.' && path.charAt(i) != '[') {
                    if (path.charAt(i) == ']') {
                        throw usage("invalid path: " + path);
                    }
                    i++;
                }
                steps.add(path.substring(start, i));
                expectKey = false;
            }
            first = false;
        }
        return steps;
    }

    @SuppressWarnings("unchecked")
    static Object resolve(Object root, List<Object> steps, String path) {
        Object current = root;
        for (Object step : steps) {
            if (step instanceof String key) {
                if (!(current instanceof Map<?, ?> map) || !map.containsKey(key)) {
                    throw notFound(path);
                }
                current = ((Map<String, Object>) map).get(key);
            } else {
                int index = (Integer) step;
                if (!(current instanceof List<?> list) || index >= list.size()) {
                    throw notFound(path);
                }
                current = list.get(index);
            }
        }
        return current;
    }

    static Exit notFound(String path) {
        return new Exit(EXIT_NOT_FOUND, "error: path not found: " + path);
    }

    // ---------------------------------------------------------------- output

    static void writeCompact(Object value, StringBuilder sb) {
        if (value instanceof String s) {
            writeString(s, sb);
        } else if (value instanceof JsonNumber num) {
            sb.append(canonicalNumber(num.literal()));
        } else if (value instanceof Boolean b) {
            sb.append(b ? "true" : "false");
        } else if (value == JsonNull.INSTANCE) {
            sb.append("null");
        } else if (value instanceof List<?> list) {
            sb.append('[');
            for (int i = 0; i < list.size(); i++) {
                if (i > 0) {
                    sb.append(',');
                }
                writeCompact(list.get(i), sb);
            }
            sb.append(']');
        } else if (value instanceof Map<?, ?> map) {
            sb.append('{');
            boolean firstEntry = true;
            for (Map.Entry<?, ?> e : map.entrySet()) {
                if (!firstEntry) {
                    sb.append(',');
                }
                firstEntry = false;
                writeString((String) e.getKey(), sb);
                sb.append(':');
                writeCompact(e.getValue(), sb);
            }
            sb.append('}');
        }
    }

    static void writeString(String s, StringBuilder sb) {
        sb.append('"');
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            switch (c) {
                case '"' -> sb.append("\\\"");
                case '\\' -> sb.append("\\\\");
                case '\b' -> sb.append("\\b");
                case '\f' -> sb.append("\\f");
                case '\n' -> sb.append("\\n");
                case '\r' -> sb.append("\\r");
                case '\t' -> sb.append("\\t");
                default -> {
                    boolean loneSurrogate = Character.isSurrogate(c)
                            && !(Character.isHighSurrogate(c) && i + 1 < s.length()
                                    && Character.isLowSurrogate(s.charAt(i + 1)))
                            && !(Character.isLowSurrogate(c) && i > 0
                                    && Character.isHighSurrogate(s.charAt(i - 1)));
                    if (c < 0x20 || loneSurrogate) {
                        sb.append(String.format("\\u%04x", (int) c));
                    } else {
                        sb.append(c);
                    }
                }
            }
        }
        sb.append('"');
    }

    /**
     * Canonical number form: no superfluous zeros or exponent; integers have no decimal
     * point. Very large or very small magnitudes use exponent notation (JavaScript style).
     */
    static String canonicalNumber(String literal) {
        BigDecimal bd = new BigDecimal(literal);
        if (bd.signum() == 0) {
            return "0";
        }
        bd = bd.stripTrailingZeros();
        String sign = bd.signum() < 0 ? "-" : "";
        String digits = bd.unscaledValue().abs().toString();
        int exponent = digits.length() - 1 - bd.scale(); // decimal exponent of first digit
        boolean plainLiteralInteger = literal.indexOf('.') < 0
                && literal.indexOf('e') < 0 && literal.indexOf('E') < 0;
        if (bd.scale() <= 0) {
            if (exponent < 21 || plainLiteralInteger) {
                return bd.toPlainString();
            }
        } else if (exponent >= -7) {
            return bd.toPlainString();
        }
        String mantissa = digits.length() == 1 ? digits : digits.charAt(0) + "." + digits.substring(1);
        return sign + mantissa + "e" + (exponent >= 0 ? "+" : "") + exponent;
    }

    static void shapeOf(Object value, int budget, StringBuilder sb) {
        if (value instanceof String) {
            sb.append("string");
        } else if (value instanceof JsonNumber) {
            sb.append("number");
        } else if (value instanceof Boolean) {
            sb.append("boolean");
        } else if (value == JsonNull.INSTANCE) {
            sb.append("null");
        } else if (value instanceof List<?> list) {
            sb.append("array[").append(list.size()).append(']');
            if (budget > 0 && !list.isEmpty()) {
                sb.append(" of ");
                shapeOf(list.get(0), budget - 1, sb);
            }
        } else if (value instanceof Map<?, ?> map) {
            if (map.isEmpty()) {
                sb.append("object{}");
            } else if (budget == 0) {
                sb.append("object{").append(map.size()).append(" keys}");
            } else {
                sb.append("object{");
                boolean firstEntry = true;
                for (Map.Entry<?, ?> e : map.entrySet()) {
                    if (!firstEntry) {
                        sb.append(", ");
                    }
                    firstEntry = false;
                    sb.append(e.getKey()).append(": ");
                    shapeOf(e.getValue(), budget - 1, sb);
                }
                sb.append('}');
            }
        }
    }

    // ---------------------------------------------------------------- parser

    /** Strict RFC 8259 parser over raw UTF-8 bytes, reporting line/column on failure. */
    static final class Parser {
        private final byte[] in;
        private int pos;

        Parser(byte[] in) {
            this.in = in;
            // Tolerate a UTF-8 byte order mark.
            if (in.length >= 3 && (in[0] & 0xFF) == 0xEF && (in[1] & 0xFF) == 0xBB
                    && (in[2] & 0xFF) == 0xBF) {
                pos = 3;
            }
        }

        Object parseDocument() {
            try {
                skipWhitespace();
                if (pos >= in.length) {
                    throw fail("unexpected end of input, expected a value");
                }
                Object value = parseValue();
                skipWhitespace();
                if (pos < in.length) {
                    throw fail("unexpected trailing " + describe(pos));
                }
                return value;
            } catch (StackOverflowError e) {
                throw fail("nesting too deep");
            }
        }

        private Object parseValue() {
            skipWhitespace();
            if (pos >= in.length) {
                throw fail("unexpected end of input, expected a value");
            }
            byte b = in[pos];
            return switch (b) {
                case '{' -> parseObject();
                case '[' -> parseArray();
                case '"' -> parseString();
                case 't' -> literal("true", Boolean.TRUE);
                case 'f' -> literal("false", Boolean.FALSE);
                case 'n' -> literal("null", JsonNull.INSTANCE);
                default -> {
                    if (b == '-' || (b >= '0' && b <= '9')) {
                        yield parseNumber();
                    }
                    throw fail("unexpected " + describe(pos) + ", expected a value");
                }
            };
        }

        private Object literal(String word, Object value) {
            for (int i = 0; i < word.length(); i++) {
                if (pos + i >= in.length) {
                    throw failAt(pos + i, "unexpected end of input in literal '" + word + "'");
                }
                if (in[pos + i] != word.charAt(i)) {
                    throw failAt(pos + i, "invalid literal, expected '" + word + "'");
                }
            }
            pos += word.length();
            return value;
        }

        private Map<String, Object> parseObject() {
            Map<String, Object> map = new LinkedHashMap<>();
            pos++; // '{'
            skipWhitespace();
            if (peek() == '}') {
                pos++;
                return map;
            }
            while (true) {
                skipWhitespace();
                if (peek() != '"') {
                    throw fail(atEnd() ? "unexpected end of input, expected object key"
                            : "unexpected " + describe(pos) + ", expected object key");
                }
                String key = parseString();
                skipWhitespace();
                expect(':', "':' after object key");
                Object value = parseValue();
                map.put(key, value);
                skipWhitespace();
                int c = peek();
                if (c == ',') {
                    pos++;
                } else if (c == '}') {
                    pos++;
                    return map;
                } else {
                    throw fail(atEnd() ? "unexpected end of input, expected ',' or '}'"
                            : "unexpected " + describe(pos) + ", expected ',' or '}'");
                }
            }
        }

        private List<Object> parseArray() {
            List<Object> list = new ArrayList<>();
            pos++; // '['
            skipWhitespace();
            if (peek() == ']') {
                pos++;
                return list;
            }
            while (true) {
                list.add(parseValue());
                skipWhitespace();
                int c = peek();
                if (c == ',') {
                    pos++;
                } else if (c == ']') {
                    pos++;
                    return list;
                } else {
                    throw fail(atEnd() ? "unexpected end of input, expected ',' or ']'"
                            : "unexpected " + describe(pos) + ", expected ',' or ']'");
                }
            }
        }

        private String parseString() {
            int start = pos;
            pos++; // opening quote
            StringBuilder sb = new StringBuilder();
            while (true) {
                if (pos >= in.length) {
                    throw failAt(start, "unterminated string");
                }
                int b = in[pos] & 0xFF;
                if (b == '"') {
                    pos++;
                    return sb.toString();
                } else if (b == '\\') {
                    parseEscape(sb);
                } else if (b < 0x20) {
                    throw fail("unescaped control character in string");
                } else if (b < 0x80) {
                    sb.append((char) b);
                    pos++;
                } else {
                    sb.appendCodePoint(decodeUtf8());
                }
            }
        }

        private void parseEscape(StringBuilder sb) {
            int escStart = pos;
            pos++; // backslash
            if (pos >= in.length) {
                throw failAt(escStart, "unterminated string");
            }
            byte e = in[pos++];
            switch (e) {
                case '"' -> sb.append('"');
                case '\\' -> sb.append('\\');
                case '/' -> sb.append('/');
                case 'b' -> sb.append('\b');
                case 'f' -> sb.append('\f');
                case 'n' -> sb.append('\n');
                case 'r' -> sb.append('\r');
                case 't' -> sb.append('\t');
                case 'u' -> {
                    int code = 0;
                    for (int i = 0; i < 4; i++) {
                        if (pos >= in.length) {
                            throw failAt(escStart, "unterminated string");
                        }
                        int d = Character.digit(in[pos], 16);
                        if (d < 0 || in[pos] < 0) {
                            throw fail("invalid hex digit in \\u escape");
                        }
                        code = code * 16 + d;
                        pos++;
                    }
                    sb.append((char) code);
                }
                default -> throw failAt(escStart, "invalid escape sequence");
            }
        }

        /** Decodes one multi-byte UTF-8 sequence at pos, rejecting malformed input. */
        private int decodeUtf8() {
            int start = pos;
            int b0 = in[pos] & 0xFF;
            int len;
            int cp;
            int min;
            if (b0 >= 0xC2 && b0 <= 0xDF) {
                len = 2;
                cp = b0 & 0x1F;
                min = 0x80;
            } else if (b0 >= 0xE0 && b0 <= 0xEF) {
                len = 3;
                cp = b0 & 0x0F;
                min = 0x800;
            } else if (b0 >= 0xF0 && b0 <= 0xF4) {
                len = 4;
                cp = b0 & 0x07;
                min = 0x10000;
            } else {
                throw failAt(start, "invalid UTF-8 byte");
            }
            for (int i = 1; i < len; i++) {
                if (start + i >= in.length || (in[start + i] & 0xC0) != 0x80) {
                    throw failAt(start, "invalid UTF-8 sequence");
                }
                cp = (cp << 6) | (in[start + i] & 0x3F);
            }
            if (cp < min || cp > 0x10FFFF || (cp >= 0xD800 && cp <= 0xDFFF)) {
                throw failAt(start, "invalid UTF-8 sequence");
            }
            pos += len;
            return cp;
        }

        private JsonNumber parseNumber() {
            int start = pos;
            if (peek() == '-') {
                pos++;
            }
            if (peek() == '0') {
                pos++;
            } else if (isDigit(peek())) {
                while (isDigit(peek())) {
                    pos++;
                }
            } else {
                throw fail("invalid number");
            }
            if (peek() == '.') {
                pos++;
                if (!isDigit(peek())) {
                    throw fail("invalid number, expected digit after '.'");
                }
                while (isDigit(peek())) {
                    pos++;
                }
            }
            if (peek() == 'e' || peek() == 'E') {
                pos++;
                if (peek() == '+' || peek() == '-') {
                    pos++;
                }
                if (!isDigit(peek())) {
                    throw fail("invalid number, expected digit in exponent");
                }
                while (isDigit(peek())) {
                    pos++;
                }
            }
            return new JsonNumber(new String(in, start, pos - start, StandardCharsets.US_ASCII));
        }

        private static boolean isDigit(int c) {
            return c >= '0' && c <= '9';
        }

        private void expect(char c, String what) {
            if (peek() != c) {
                throw fail(atEnd() ? "unexpected end of input, expected " + what
                        : "unexpected " + describe(pos) + ", expected " + what);
            }
            pos++;
        }

        private void skipWhitespace() {
            while (pos < in.length) {
                byte b = in[pos];
                if (b == ' ' || b == '\t' || b == '\n' || b == '\r') {
                    pos++;
                } else {
                    break;
                }
            }
        }

        private int peek() {
            return pos < in.length ? in[pos] & 0xFF : -1;
        }

        private boolean atEnd() {
            return pos >= in.length;
        }

        private String describe(int at) {
            int b = in[at] & 0xFF;
            if (b >= 0x21 && b < 0x7F) {
                return "character '" + (char) b + "'";
            }
            return String.format("byte 0x%02x", b);
        }

        private Exit fail(String reason) {
            return failAt(pos, reason);
        }

        /** Builds an invalid-JSON error naming 1-based line/column and 0-based byte offset. */
        private Exit failAt(int at, String reason) {
            int line = 1;
            int col = 1;
            for (int i = 0; i < at && i < in.length; i++) {
                if (in[i] == '\n') {
                    line++;
                    col = 1;
                } else if ((in[i] & 0xC0) != 0x80) {
                    col++; // count characters, not UTF-8 continuation bytes
                }
            }
            return new Exit(EXIT_USAGE, "error: invalid JSON: " + reason + " at line " + line
                    + ", column " + col + " (byte offset " + at + ")");
        }
    }
}
