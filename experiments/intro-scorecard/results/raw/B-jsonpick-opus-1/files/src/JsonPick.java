import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.PrintStream;
import java.math.BigDecimal;
import java.math.BigInteger;
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

    static final String USAGE = String.join("\n",
            "Usage: jsonpick PATH [FILE] [--shape] [--depth N]",
            "",
            "Extract a value by path from JSON.",
            "",
            "Arguments:",
            "  PATH         Path to select, e.g. a.b[0].c (\".\" or \"\" selects the root)",
            "  FILE         JSON input file; omitted or \"-\" reads stdin",
            "",
            "Flags:",
            "  --shape      Print a type/array-length summary of the value at PATH",
            "  --depth N    Levels to describe in --shape mode (default 2)",
            "  --help       Show this help and exit",
            "");

    /** Exit carrying a process exit code and an optional stderr message. */
    static final class Exit extends RuntimeException {
        final int code;
        final String message;

        Exit(int code, String message) {
            super(message, null, false, false);
            this.code = code;
            this.message = message;
        }
    }

    static final PrintStream OUT = new PrintStream(new java.io.FileOutputStream(java.io.FileDescriptor.out), false, StandardCharsets.UTF_8);
    static final PrintStream ERR = new PrintStream(new java.io.FileOutputStream(java.io.FileDescriptor.err), false, StandardCharsets.UTF_8);

    public static void main(String[] args) throws InterruptedException {
        int[] code = {0};
        // Large stack so deeply nested documents don't overflow the recursive parser/printers.
        Thread t = new Thread(null, () -> code[0] = run(args), "jsonpick", 1L << 30);
        t.start();
        t.join();
        OUT.flush();
        ERR.flush();
        System.exit(code[0]);
    }

    static int run(String[] args) {
        try {
            String out = execute(args);
            OUT.print(out);
            OUT.flush();
            return 0;
        } catch (Exit e) {
            if (e.message != null) {
                ERR.print(e.message + "\n");
                ERR.flush();
            }
            return e.code;
        }
    }

    static Exit usage(String msg) {
        return new Exit(2, msg);
    }

    static String execute(String[] args) {
        List<String> positional = new ArrayList<>();
        boolean shape = false;
        int depth = 2;
        boolean endOfFlags = false;
        for (int i = 0; i < args.length; i++) {
            String a = args[i];
            if (endOfFlags || a.equals("-") || !a.startsWith("-")) {
                positional.add(a);
            } else if (a.equals("--")) {
                endOfFlags = true;
            } else if (a.equals("--help")) {
                return USAGE;
            } else if (a.equals("--shape")) {
                shape = true;
            } else if (a.equals("--depth") || a.startsWith("--depth=")) {
                String v;
                if (a.equals("--depth")) {
                    if (i + 1 >= args.length) {
                        throw usage("error: --depth requires a value");
                    }
                    v = args[++i];
                } else {
                    v = a.substring("--depth=".length());
                }
                depth = parseDepth(v);
            } else {
                throw usage("unknown flag: " + a);
            }
        }
        if (positional.isEmpty()) {
            throw usage("error: missing required argument: PATH");
        }
        if (positional.size() > 2) {
            throw usage("error: unexpected argument: " + positional.get(2));
        }
        String pathText = positional.get(0);
        List<Object> path = parsePath(pathText);
        String file = positional.size() == 2 ? positional.get(1) : "-";

        byte[] input = readInput(file);
        Object root = new Parser(input).parseDocument();

        Object value = root;
        for (Object step : path) {
            value = navigate(value, step);
            if (value == MISSING) {
                throw new Exit(1, "error: path not found: " + pathText);
            }
        }

        StringBuilder sb = new StringBuilder();
        if (shape) {
            shape(value, depth, sb);
            sb.append('\n');
        } else if (value instanceof String s) {
            sb.append(s).append('\n');
        } else {
            writeJson(value, sb);
            sb.append('\n');
        }
        return sb.toString();
    }

    static int parseDepth(String v) {
        if (v.isEmpty() || !v.chars().allMatch(c -> c >= '0' && c <= '9')) {
            throw usage("error: invalid --depth value: " + v);
        }
        try {
            return Integer.parseInt(v);
        } catch (NumberFormatException e) {
            return Integer.MAX_VALUE;
        }
    }

    // ---- input ----

    static byte[] readInput(String file) {
        try {
            if (file.equals("-")) {
                return readAll(System.in);
            }
            return Files.readAllBytes(Path.of(file));
        } catch (IOException | java.nio.file.InvalidPathException e) {
            throw usage("error: cannot read file: " + file);
        }
    }

    static byte[] readAll(InputStream in) throws IOException {
        ByteArrayOutputStream bos = new ByteArrayOutputStream();
        in.transferTo(bos);
        return bos.toByteArray();
    }

    // ---- path ----

    /** Path steps: String for object keys, BigInteger for array indices. */
    static List<Object> parsePath(String p) {
        List<Object> steps = new ArrayList<>();
        int i = 0;
        int n = p.length();
        if (n == 0 || p.equals(".")) {
            return steps;
        }
        if (p.charAt(0) == '.') {
            i = 1;
        }
        boolean expectKey = p.charAt(i) != '[';
        while (i < n) {
            char c = p.charAt(i);
            if (c == '[') {
                int close = p.indexOf(']', i);
                if (close < 0) {
                    throw invalidPath(p);
                }
                String digits = p.substring(i + 1, close);
                if (digits.isEmpty() || !digits.chars().allMatch(d -> d >= '0' && d <= '9')) {
                    throw invalidPath(p);
                }
                steps.add(new BigInteger(digits));
                i = close + 1;
                expectKey = false;
                if (i < n) {
                    char next = p.charAt(i);
                    if (next == '.') {
                        i++;
                        expectKey = true;
                        if (i >= n) {
                            throw invalidPath(p);
                        }
                    } else if (next != '[') {
                        throw invalidPath(p);
                    }
                }
            } else if (expectKey) {
                int j = i;
                while (j < n && p.charAt(j) != '.' && p.charAt(j) != '[') {
                    j++;
                }
                if (j == i) {
                    throw invalidPath(p);
                }
                steps.add(p.substring(i, j));
                i = j;
                expectKey = false;
                if (i < n && p.charAt(i) == '.') {
                    i++;
                    expectKey = true;
                    if (i >= n) {
                        throw invalidPath(p);
                    }
                }
            } else {
                throw invalidPath(p);
            }
        }
        return steps;
    }

    static Exit invalidPath(String p) {
        return usage("error: invalid path: " + p);
    }

    static final Object MISSING = new Object();

    @SuppressWarnings("unchecked")
    static Object navigate(Object value, Object step) {
        if (step instanceof String key) {
            if (value instanceof Map<?, ?> m && m.containsKey(key)) {
                return m.get(key);
            }
            return MISSING;
        }
        BigInteger idx = (BigInteger) step;
        if (value instanceof List<?> list && idx.compareTo(BigInteger.valueOf(list.size())) < 0) {
            return list.get(idx.intValue());
        }
        return MISSING;
    }

    // ---- output ----

    static void writeJson(Object v, StringBuilder sb) {
        if (v == null) {
            sb.append("null");
        } else if (v instanceof Boolean b) {
            sb.append(b ? "true" : "false");
        } else if (v instanceof BigDecimal d) {
            sb.append(formatNumber(d));
        } else if (v instanceof String s) {
            writeString(s, sb);
        } else if (v instanceof List<?> list) {
            sb.append('[');
            for (int i = 0; i < list.size(); i++) {
                if (i > 0) {
                    sb.append(',');
                }
                writeJson(list.get(i), sb);
            }
            sb.append(']');
        } else {
            Map<?, ?> m = (Map<?, ?>) v;
            sb.append('{');
            boolean first = true;
            for (Map.Entry<?, ?> e : m.entrySet()) {
                if (!first) {
                    sb.append(',');
                }
                first = false;
                writeString((String) e.getKey(), sb);
                sb.append(':');
                writeJson(e.getValue(), sb);
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
                case '\n' -> sb.append("\\n");
                case '\r' -> sb.append("\\r");
                case '\t' -> sb.append("\\t");
                case '\b' -> sb.append("\\b");
                case '\f' -> sb.append("\\f");
                default -> {
                    if (c < 0x20) {
                        sb.append(String.format("\\u%04x", (int) c));
                    } else {
                        sb.append(c);
                    }
                }
            }
        }
        sb.append('"');
    }

    /** Shortest exact decimal form: integers without a decimal point, no trailing zeros. */
    static String formatNumber(BigDecimal d) {
        if (d.signum() == 0) {
            return "0";
        }
        d = d.stripTrailingZeros();
        // Exponent of the leading digit (scientific notation exponent).
        long exp = (long) d.precision() - d.scale() - 1;
        if (exp >= -7 && exp < 21) {
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
        sb.append('e').append(exp > 0 ? "+" : "").append(exp);
        return sb.toString();
    }

    static void shape(Object v, int depth, StringBuilder sb) {
        if (v == null) {
            sb.append("null");
        } else if (v instanceof Boolean) {
            sb.append("boolean");
        } else if (v instanceof BigDecimal) {
            sb.append("number");
        } else if (v instanceof String) {
            sb.append("string");
        } else if (v instanceof List<?> list) {
            sb.append("array[").append(list.size()).append(']');
            if (depth > 0 && !list.isEmpty()) {
                sb.append(" of ");
                shape(list.get(0), depth - 1, sb);
            }
        } else {
            Map<?, ?> m = (Map<?, ?>) v;
            if (depth == 0 && !m.isEmpty()) {
                sb.append("object{").append(m.size()).append('}');
                return;
            }
            sb.append("object{");
            boolean first = true;
            for (Map.Entry<?, ?> e : m.entrySet()) {
                if (!first) {
                    sb.append(", ");
                }
                first = false;
                sb.append(e.getKey()).append(": ");
                shape(e.getValue(), depth - 1, sb);
            }
            sb.append('}');
        }
    }

    // ---- JSON parser (strict RFC 8259) ----

    static final class Parser {
        private final String text;
        private int pos;

        Parser(byte[] bytes) {
            int start = 0;
            if (bytes.length >= 3 && (bytes[0] & 0xff) == 0xEF && (bytes[1] & 0xff) == 0xBB && (bytes[2] & 0xff) == 0xBF) {
                start = 3;
            }
            CharsetDecoder dec = StandardCharsets.UTF_8.newDecoder()
                    .onMalformedInput(CodingErrorAction.REPORT)
                    .onUnmappableCharacter(CodingErrorAction.REPORT);
            ByteBuffer in = ByteBuffer.wrap(bytes, start, bytes.length - start);
            CharBuffer out = CharBuffer.allocate(bytes.length - start + 1);
            var result = dec.decode(in, out, true);
            if (result.isError()) {
                throw invalid("invalid UTF-8 at byte offset " + in.position());
            }
            dec.flush(out);
            out.flip();
            this.text = out.toString();
        }

        Exit invalid(String reason) {
            return new Exit(2, "error: invalid JSON: " + reason);
        }

        Exit errorAt(String what) {
            int line = 1;
            int col = 1;
            for (int i = 0; i < pos && i < text.length(); i++) {
                if (text.charAt(i) == '\n') {
                    line++;
                    col = 1;
                } else {
                    col++;
                }
            }
            return invalid(what + " at line " + line + ", column " + col);
        }

        Exit unexpected() {
            if (pos >= text.length()) {
                return errorAt("unexpected end of input");
            }
            int cp = text.codePointAt(pos);
            String shown = cp < 0x20 || cp == 0x7f ? String.format("U+%04X", cp) : "'" + new String(Character.toChars(cp)) + "'";
            return errorAt("unexpected character " + shown);
        }

        Object parseDocument() {
            skipWs();
            Object v = parseValue();
            skipWs();
            if (pos < text.length()) {
                throw unexpected();
            }
            return v;
        }

        void skipWs() {
            while (pos < text.length()) {
                char c = text.charAt(pos);
                if (c == ' ' || c == '\t' || c == '\n' || c == '\r') {
                    pos++;
                } else {
                    break;
                }
            }
        }

        Object parseValue() {
            if (pos >= text.length()) {
                throw unexpected();
            }
            char c = text.charAt(pos);
            switch (c) {
                case '{':
                    return parseObject();
                case '[':
                    return parseArray();
                case '"':
                    return parseString();
                case 't':
                    expectLiteral("true");
                    return Boolean.TRUE;
                case 'f':
                    expectLiteral("false");
                    return Boolean.FALSE;
                case 'n':
                    expectLiteral("null");
                    return null;
                default:
                    if (c == '-' || (c >= '0' && c <= '9')) {
                        return parseNumber();
                    }
                    throw unexpected();
            }
        }

        void expectLiteral(String lit) {
            for (int i = 0; i < lit.length(); i++) {
                if (pos >= text.length() || text.charAt(pos) != lit.charAt(i)) {
                    throw unexpected();
                }
                pos++;
            }
        }

        Map<String, Object> parseObject() {
            Map<String, Object> m = new LinkedHashMap<>();
            pos++; // {
            skipWs();
            if (pos < text.length() && text.charAt(pos) == '}') {
                pos++;
                return m;
            }
            while (true) {
                skipWs();
                if (pos >= text.length() || text.charAt(pos) != '"') {
                    throw unexpected();
                }
                String key = parseString();
                skipWs();
                if (pos >= text.length() || text.charAt(pos) != ':') {
                    throw unexpected();
                }
                pos++;
                skipWs();
                Object v = parseValue();
                m.put(key, v);
                skipWs();
                if (pos >= text.length()) {
                    throw unexpected();
                }
                char c = text.charAt(pos);
                if (c == ',') {
                    pos++;
                } else if (c == '}') {
                    pos++;
                    return m;
                } else {
                    throw unexpected();
                }
            }
        }

        List<Object> parseArray() {
            List<Object> list = new ArrayList<>();
            pos++; // [
            skipWs();
            if (pos < text.length() && text.charAt(pos) == ']') {
                pos++;
                return list;
            }
            while (true) {
                skipWs();
                list.add(parseValue());
                skipWs();
                if (pos >= text.length()) {
                    throw unexpected();
                }
                char c = text.charAt(pos);
                if (c == ',') {
                    pos++;
                } else if (c == ']') {
                    pos++;
                    return list;
                } else {
                    throw unexpected();
                }
            }
        }

        String parseString() {
            pos++; // opening quote
            StringBuilder sb = new StringBuilder();
            while (true) {
                if (pos >= text.length()) {
                    throw errorAt("unterminated string");
                }
                char c = text.charAt(pos);
                if (c == '"') {
                    pos++;
                    return sb.toString();
                }
                if (c < 0x20) {
                    throw errorAt("unescaped control character in string");
                }
                if (c != '\\') {
                    sb.append(c);
                    pos++;
                    continue;
                }
                pos++;
                if (pos >= text.length()) {
                    throw errorAt("unterminated string");
                }
                char e = text.charAt(pos);
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
                            int h = pos + k < text.length() ? Character.digit(text.charAt(pos + k), 16) : -1;
                            if (h < 0) {
                                pos += k;
                                throw errorAt("invalid \\u escape");
                            }
                            code = code * 16 + h;
                        }
                        sb.append((char) code);
                        pos += 4;
                    }
                    default -> throw errorAt("invalid escape sequence");
                }
                pos++;
            }
        }

        BigDecimal parseNumber() {
            int start = pos;
            if (text.charAt(pos) == '-') {
                pos++;
            }
            if (pos >= text.length()) {
                throw unexpected();
            }
            char c = text.charAt(pos);
            if (c == '0') {
                pos++;
            } else if (c >= '1' && c <= '9') {
                while (pos < text.length() && isDigit(text.charAt(pos))) {
                    pos++;
                }
            } else {
                throw unexpected();
            }
            if (pos < text.length() && text.charAt(pos) == '.') {
                pos++;
                requireDigits();
            }
            if (pos < text.length() && (text.charAt(pos) == 'e' || text.charAt(pos) == 'E')) {
                pos++;
                if (pos < text.length() && (text.charAt(pos) == '+' || text.charAt(pos) == '-')) {
                    pos++;
                }
                requireDigits();
            }
            try {
                return new BigDecimal(text.substring(start, pos));
            } catch (NumberFormatException | ArithmeticException ex) {
                pos = start;
                throw errorAt("number out of range");
            }
        }

        void requireDigits() {
            if (pos >= text.length() || !isDigit(text.charAt(pos))) {
                throw unexpected();
            }
            while (pos < text.length() && isDigit(text.charAt(pos))) {
                pos++;
            }
        }

        static boolean isDigit(char c) {
            return c >= '0' && c <= '9';
        }
    }
}
