import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.math.BigDecimal;
import java.nio.ByteBuffer;
import java.nio.CharBuffer;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.CharsetDecoder;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
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
            "  PATH         Path to select, e.g. a.b[0].c. Use \".\" or \"\" for the whole document.",
            "  FILE         JSON file to read. Omit or use \"-\" to read stdin.",
            "",
            "Flags:",
            "  --shape      Print a type/array-length summary of the selected subtree",
            "               instead of its value.",
            "  --depth N    Levels to describe in --shape mode (default 2).",
            "  --help       Show this help and exit.",
            "") + "\n";

    /** Raised to terminate with a given exit code and stderr message. */
    static final class Exit extends RuntimeException {
        final int code;

        Exit(int code, String message) {
            super(message, null, false, false);
            this.code = code;
        }
    }

    /** A JSON number, kept as its source text so precision is never lost. */
    record Num(String text) {}

    public static void main(String[] args) throws InterruptedException {
        int[] code = {EXIT_OK};
        // Run on a thread with a large stack so deeply nested documents don't overflow.
        Thread t = new Thread(null, () -> code[0] = run(args), "jsonpick", 1L << 30);
        t.start();
        t.join();
        System.exit(code[0]);
    }

    static int run(String[] args) {
        try {
            return execute(args);
        } catch (Exit e) {
            writeErr(e.getMessage());
            return e.code;
        } catch (StackOverflowError e) {
            writeErr("error: invalid JSON: nesting too deep");
            return EXIT_USAGE;
        }
    }

    static int execute(String[] args) {
        String path = null;
        String file = null;
        boolean shape = false;
        int depth = 2;
        boolean endOfFlags = false;
        List<String> positionals = new ArrayList<>();

        for (int i = 0; i < args.length; i++) {
            String a = args[i];
            if (endOfFlags || a.equals("-") || !a.startsWith("-")) {
                positionals.add(a);
            } else if (a.equals("--")) {
                endOfFlags = true;
            } else if (a.equals("--help")) {
                writeOut(HELP);
                return EXIT_OK;
            } else if (a.equals("--shape")) {
                shape = true;
            } else if (a.equals("--depth")) {
                if (i + 1 >= args.length) {
                    throw new Exit(EXIT_USAGE, "error: --depth requires a value");
                }
                depth = parseDepth(args[++i]);
            } else if (a.startsWith("--depth=")) {
                depth = parseDepth(a.substring("--depth=".length()));
            } else {
                throw new Exit(EXIT_USAGE, "unknown flag: " + a);
            }
        }

        if (positionals.isEmpty()) {
            throw new Exit(EXIT_USAGE, "error: missing required argument: PATH");
        }
        if (positionals.size() > 2) {
            throw new Exit(EXIT_USAGE, "error: unexpected argument: " + positionals.get(2));
        }
        path = positionals.get(0);
        if (positionals.size() == 2) {
            file = positionals.get(1);
        }

        List<Object> steps = parsePath(path);
        String text = decode(readInput(file));
        Object root = new Parser(text).parseDocument();
        Object value = navigate(root, steps, path);

        StringBuilder out = new StringBuilder();
        if (shape) {
            shapeOf(value, depth, out);
        } else if (value instanceof String s) {
            out.append(s);
        } else {
            writeJson(value, out);
        }
        out.append('\n');
        writeOut(out.toString());
        return EXIT_OK;
    }

    static int parseDepth(String s) {
        if (!s.isEmpty() && s.chars().allMatch(c -> c >= '0' && c <= '9')) {
            try {
                return Integer.parseInt(s);
            } catch (NumberFormatException ignored) {
                // Absurdly large depth: equivalent to unlimited.
                return Integer.MAX_VALUE;
            }
        }
        throw new Exit(EXIT_USAGE, "error: invalid --depth value: " + s);
    }

    // ---------------------------------------------------------------- input

    static byte[] readInput(String file) {
        try {
            if (file == null || file.equals("-")) {
                return readAll(System.in);
            }
            return Files.readAllBytes(Path.of(file));
        } catch (IOException | RuntimeException e) {
            throw new Exit(EXIT_USAGE, "error: cannot read file: " + file);
        }
    }

    static byte[] readAll(InputStream in) throws IOException {
        ByteArrayOutputStream buf = new ByteArrayOutputStream();
        in.transferTo(buf);
        return buf.toByteArray();
    }

    static String decode(byte[] bytes) {
        int start = 0;
        if (bytes.length >= 3 && (bytes[0] & 0xFF) == 0xEF && (bytes[1] & 0xFF) == 0xBB
                && (bytes[2] & 0xFF) == 0xBF) {
            start = 3; // tolerate a UTF-8 byte order mark
        }
        CharsetDecoder dec = StandardCharsets.UTF_8.newDecoder()
                .onMalformedInput(CodingErrorAction.REPORT)
                .onUnmappableCharacter(CodingErrorAction.REPORT);
        ByteBuffer in = ByteBuffer.wrap(bytes, start, bytes.length - start);
        CharBuffer out = CharBuffer.allocate(bytes.length);
        var result = dec.decode(in, out, true);
        if (result.isError()) {
            throw new Exit(EXIT_USAGE,
                    "error: invalid JSON: invalid UTF-8 at byte offset " + in.position());
        }
        result = dec.flush(out);
        if (result.isError()) {
            throw new Exit(EXIT_USAGE,
                    "error: invalid JSON: invalid UTF-8 at byte offset " + in.position());
        }
        out.flip();
        return out.toString();
    }

    // ---------------------------------------------------------------- path

    /** Parses PATH into a list of String (object key) and Long (array index) steps. */
    static List<Object> parsePath(String path) {
        List<Object> steps = new ArrayList<>();
        int i = 0;
        int n = path.length();
        if (i < n && path.charAt(i) == '.') {
            i++; // leading '.' selects the root
        }
        boolean needKey = false; // true right after a '.' separator
        while (i < n) {
            char c = path.charAt(i);
            if (c == '[') {
                if (needKey) {
                    throw invalidPath(path);
                }
                int close = path.indexOf(']', i);
                if (close < 0) {
                    throw invalidPath(path);
                }
                String digits = path.substring(i + 1, close);
                if (digits.isEmpty() || !digits.chars().allMatch(d -> d >= '0' && d <= '9')) {
                    throw invalidPath(path);
                }
                long index;
                try {
                    index = Long.parseLong(digits);
                } catch (NumberFormatException e) {
                    index = Long.MAX_VALUE; // can never be in range
                }
                steps.add(index);
                i = close + 1;
                if (i < n && path.charAt(i) == '.') {
                    i++;
                    needKey = true;
                    if (i == n) {
                        throw invalidPath(path);
                    }
                } else if (i < n && path.charAt(i) != '[') {
                    throw invalidPath(path);
                }
            } else {
                int j = i;
                while (j < n && path.charAt(j) != '.' && path.charAt(j) != '[') {
                    j++;
                }
                if (j == i) {
                    throw invalidPath(path); // empty key, e.g. "a..b"
                }
                steps.add(path.substring(i, j));
                needKey = false;
                i = j;
                if (i < n && path.charAt(i) == '.') {
                    i++;
                    needKey = true;
                    if (i == n) {
                        throw invalidPath(path);
                    }
                }
            }
        }
        return steps;
    }

    static Exit invalidPath(String path) {
        return new Exit(EXIT_USAGE, "error: invalid path: " + path);
    }

    static Object navigate(Object root, List<Object> steps, String path) {
        Object cur = root;
        for (Object step : steps) {
            if (step instanceof String key && cur instanceof Map<?, ?> obj && obj.containsKey(key)) {
                cur = obj.get(key);
            } else if (step instanceof Long idx && cur instanceof List<?> arr && idx < arr.size()) {
                cur = arr.get((int) (long) idx);
            } else {
                throw new Exit(EXIT_NOT_FOUND, "error: path not found: " + path);
            }
        }
        return cur;
    }

    // ---------------------------------------------------------------- output

    static void writeJson(Object v, StringBuilder out) {
        if (v == null) {
            out.append("null");
        } else if (v instanceof Boolean b) {
            out.append(b);
        } else if (v instanceof Num num) {
            out.append(canonicalNumber(num.text()));
        } else if (v instanceof String s) {
            writeJsonString(s, out);
        } else if (v instanceof List<?> list) {
            out.append('[');
            for (int i = 0; i < list.size(); i++) {
                if (i > 0) {
                    out.append(',');
                }
                writeJson(list.get(i), out);
            }
            out.append(']');
        } else if (v instanceof Map<?, ?> map) {
            out.append('{');
            boolean first = true;
            for (Map.Entry<?, ?> e : map.entrySet()) {
                if (!first) {
                    out.append(',');
                }
                first = false;
                writeJsonString((String) e.getKey(), out);
                out.append(':');
                writeJson(e.getValue(), out);
            }
            out.append('}');
        }
    }

    static void writeJsonString(String s, StringBuilder out) {
        out.append('"');
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            switch (c) {
                case '"' -> out.append("\\\"");
                case '\\' -> out.append("\\\\");
                case '\n' -> out.append("\\n");
                case '\r' -> out.append("\\r");
                case '\t' -> out.append("\\t");
                case '\b' -> out.append("\\b");
                case '\f' -> out.append("\\f");
                default -> {
                    boolean loneSurrogate = Character.isSurrogate(c)
                            && !(Character.isHighSurrogate(c) && i + 1 < s.length()
                                    && Character.isLowSurrogate(s.charAt(i + 1)))
                            && !(Character.isLowSurrogate(c) && i > 0
                                    && Character.isHighSurrogate(s.charAt(i - 1)));
                    if (c < 0x20 || c == 0x7F || loneSurrogate) {
                        out.append(String.format("\\u%04x", (int) c));
                    } else {
                        out.append(c);
                    }
                }
            }
        }
        out.append('"');
    }

    /**
     * Shortest canonical form of a JSON number literal: integers without a decimal point,
     * fractions without trailing zeros, exponent notation only for very large/small values.
     */
    static String canonicalNumber(String text) {
        BigDecimal d = new BigDecimal(text);
        if (d.signum() == 0) {
            return "0";
        }
        d = d.stripTrailingZeros();
        // Order of magnitude: value = unscaled * 10^-scale, unscaled has `precision` digits.
        long exponent = (long) d.precision() - d.scale() - 1;
        if (exponent >= -7 && exponent < 21) {
            return d.toPlainString();
        }
        String digits = d.unscaledValue().abs().toString();
        StringBuilder sb = new StringBuilder();
        if (d.signum() < 0) {
            sb.append('-');
        }
        sb.append(digits.charAt(0));
        if (digits.length() > 1) {
            sb.append('.').append(digits, 1, digits.length());
        }
        sb.append('e').append(exponent >= 0 ? "+" : "").append(exponent);
        return sb.toString();
    }

    static void shapeOf(Object v, int budget, StringBuilder out) {
        if (v == null) {
            out.append("null");
        } else if (v instanceof Boolean) {
            out.append("boolean");
        } else if (v instanceof Num) {
            out.append("number");
        } else if (v instanceof String) {
            out.append("string");
        } else if (v instanceof List<?> list) {
            out.append("array[").append(list.size()).append(']');
            if (budget > 0 && !list.isEmpty()) {
                out.append(" of ");
                shapeOf(list.get(0), budget - 1, out);
            }
        } else if (v instanceof Map<?, ?> map) {
            if (map.isEmpty()) {
                out.append("object{}");
            } else if (budget == 0) {
                out.append("object{").append(map.size()).append(" keys}");
            } else {
                out.append("object{");
                boolean first = true;
                for (Map.Entry<?, ?> e : map.entrySet()) {
                    if (!first) {
                        out.append(", ");
                    }
                    first = false;
                    out.append(e.getKey()).append(": ");
                    shapeOf(e.getValue(), budget - 1, out);
                }
                out.append('}');
            }
        }
    }

    static void writeOut(String s) {
        write(System.out, s);
    }

    static void writeErr(String s) {
        write(System.err, s + "\n");
    }

    static void write(OutputStream stream, String s) {
        try {
            stream.write(encodeUtf8(s));
            stream.flush();
        } catch (IOException ignored) {
            // Nothing sensible to report if the output stream is closed.
        }
    }

    /** UTF-8 encode, replacing unpaired surrogates (which UTF-8 cannot represent) with U+FFFD. */
    static byte[] encodeUtf8(String s) {
        try {
            ByteBuffer buf = StandardCharsets.UTF_8.newEncoder()
                    .onMalformedInput(CodingErrorAction.REPLACE)
                    .onUnmappableCharacter(CodingErrorAction.REPLACE)
                    .replaceWith(new byte[] {(byte) 0xEF, (byte) 0xBF, (byte) 0xBD})
                    .encode(CharBuffer.wrap(s));
            byte[] bytes = new byte[buf.remaining()];
            buf.get(bytes);
            return bytes;
        } catch (CharacterCodingException e) {
            return s.getBytes(StandardCharsets.UTF_8);
        }
    }

    // ---------------------------------------------------------------- parser

    /** Strict RFC 8259 JSON parser. Objects keep document key order; duplicate keys: last wins. */
    static final class Parser {
        private final String s;
        private int pos;

        Parser(String s) {
            this.s = s;
        }

        Object parseDocument() {
            skipWs();
            Object v = parseValue();
            skipWs();
            if (pos < s.length()) {
                throw error("unexpected trailing character " + describe(s.charAt(pos)));
            }
            return v;
        }

        private Object parseValue() {
            if (pos >= s.length()) {
                throw error("unexpected end of input");
            }
            char c = s.charAt(pos);
            switch (c) {
                case '{':
                    return parseObject();
                case '[':
                    return parseArray();
                case '"':
                    return parseString();
                case 't':
                    expectWord("true");
                    return Boolean.TRUE;
                case 'f':
                    expectWord("false");
                    return Boolean.FALSE;
                case 'n':
                    expectWord("null");
                    return null;
                default:
                    if (c == '-' || (c >= '0' && c <= '9')) {
                        return parseNumber();
                    }
                    throw error("unexpected character " + describe(c));
            }
        }

        private Map<String, Object> parseObject() {
            Map<String, Object> obj = new LinkedHashMap<>();
            pos++; // '{'
            skipWs();
            if (peek() == '}') {
                pos++;
                return obj;
            }
            while (true) {
                skipWs();
                if (peek() != '"') {
                    throw unexpected("expected string key");
                }
                String key = parseString();
                skipWs();
                if (peek() != ':') {
                    throw unexpected("expected ':'");
                }
                pos++;
                skipWs();
                obj.put(key, parseValue());
                skipWs();
                char c = peek();
                if (c == ',') {
                    pos++;
                } else if (c == '}') {
                    pos++;
                    return obj;
                } else {
                    throw unexpected("expected ',' or '}'");
                }
            }
        }

        private List<Object> parseArray() {
            List<Object> arr = new ArrayList<>();
            pos++; // '['
            skipWs();
            if (peek() == ']') {
                pos++;
                return arr;
            }
            while (true) {
                skipWs();
                arr.add(parseValue());
                skipWs();
                char c = peek();
                if (c == ',') {
                    pos++;
                } else if (c == ']') {
                    pos++;
                    return arr;
                } else {
                    throw unexpected("expected ',' or ']'");
                }
            }
        }

        private String parseString() {
            pos++; // opening quote
            StringBuilder sb = new StringBuilder();
            while (true) {
                if (pos >= s.length()) {
                    throw error("unterminated string");
                }
                char c = s.charAt(pos);
                if (c == '"') {
                    pos++;
                    return sb.toString();
                }
                if (c < 0x20) {
                    throw error("unescaped control character " + describe(c) + " in string");
                }
                if (c != '\\') {
                    sb.append(c);
                    pos++;
                    continue;
                }
                pos++;
                if (pos >= s.length()) {
                    throw error("unterminated string");
                }
                char e = s.charAt(pos);
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
                        for (int k = 1; k <= 4; k++) {
                            int h = pos + k < s.length() ? Character.digit(s.charAt(pos + k), 16) : -1;
                            if (h < 0) {
                                pos += k;
                                throw error("invalid \\u escape");
                            }
                            code = code * 16 + h;
                        }
                        sb.append((char) code);
                        pos += 4;
                    }
                    default -> throw error("invalid escape " + describe(e));
                }
                pos++;
            }
        }

        private Num parseNumber() {
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
                throw unexpected("expected digit");
            }
            if (peek() == '.') {
                pos++;
                if (!isDigit(peek())) {
                    throw unexpected("expected digit after '.'");
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
                    throw unexpected("expected digit in exponent");
                }
                while (isDigit(peek())) {
                    pos++;
                }
            }
            String text = s.substring(start, pos);
            try {
                new BigDecimal(text); // rejects exponents beyond BigDecimal's range
            } catch (NumberFormatException | ArithmeticException ex) {
                pos = start;
                throw error("number out of range");
            }
            return new Num(text);
        }

        private void expectWord(String word) {
            if (s.startsWith(word, pos)) {
                pos += word.length();
                return;
            }
            int i = 0;
            while (pos + i < s.length() && i < word.length() && s.charAt(pos + i) == word.charAt(i)) {
                i++;
            }
            pos += i;
            throw unexpected("expected '" + word + "'");
        }

        private static boolean isDigit(char c) {
            return c >= '0' && c <= '9';
        }

        /** Current char, or NUL at end of input. */
        private char peek() {
            return pos < s.length() ? s.charAt(pos) : '\0';
        }

        private void skipWs() {
            while (pos < s.length()) {
                char c = s.charAt(pos);
                if (c == ' ' || c == '\t' || c == '\n' || c == '\r') {
                    pos++;
                } else {
                    break;
                }
            }
        }

        private Exit unexpected(String expectation) {
            if (pos >= s.length()) {
                return error("unexpected end of input, " + expectation);
            }
            return error("unexpected character " + describe(s.charAt(pos)) + ", " + expectation);
        }

        private static String describe(char c) {
            if (c < 0x20 || c == 0x7F) {
                return String.format("U+%04X", (int) c);
            }
            return "'" + c + "'";
        }

        private Exit error(String reason) {
            int line = 1;
            int col = 1;
            int end = Math.min(pos, s.length());
            for (int i = 0; i < end; i++) {
                char c = s.charAt(i);
                if (c == '\n') {
                    line++;
                    col = 1;
                } else if (!Character.isLowSurrogate(c)) {
                    col++;
                }
            }
            return new Exit(EXIT_USAGE,
                    "error: invalid JSON: " + reason + " at line " + line + ", column " + col);
        }
    }
}
