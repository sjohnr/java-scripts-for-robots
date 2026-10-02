import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.io.PrintStream;
import java.math.BigDecimal;
import java.math.BigInteger;
import java.nio.ByteBuffer;
import java.nio.CharBuffer;
import java.nio.charset.CharsetDecoder;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

/** jsonpick: extract a value by path from JSON. See SPEC.md. */
public final class JsonPick {

    static final String USAGE = String.join("\n",
            "Usage: jsonpick PATH [FILE] [--shape] [--depth N]",
            "",
            "Extract a value by path from JSON.",
            "",
            "Arguments:",
            "  PATH         path to select, e.g. a.b[0].c (\".\" or \"\" selects the root)",
            "  FILE         JSON file to read; omitted or \"-\" reads stdin",
            "",
            "Options:",
            "  --shape      print a type/array-length summary of the value at PATH",
            "  --depth N    levels to describe in --shape mode (default 2)",
            "  --help       show this help and exit",
            "");

    /** Raised to terminate with a given exit code and stderr message. */
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

    /** Object members kept as an ordered list to preserve document order (and duplicates). */
    record JObject(List<String> keys, List<Object> values) {}

    /** Number kept as its validated source literal; formatted canonically on output. */
    record JNumber(String literal) {}

    enum JLiteral { TRUE, FALSE, NULL }

    // ---------------------------------------------------------------- entry point

    public static void main(String[] args) {
        int[] code = {0};
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
        PrintStream err = new PrintStream(System.err, true, StandardCharsets.UTF_8);
        try {
            String out = execute(args);
            writeStdout(out);
            return 0;
        } catch (Exit e) {
            if (e.message != null) {
                err.print(e.message + "\n");
                err.flush();
            }
            return e.code;
        }
    }

    static void writeStdout(String s) {
        try {
            OutputStream o = System.out;
            o.write(s.getBytes(StandardCharsets.UTF_8));
            o.flush();
        } catch (IOException e) {
            // Nothing sensible to report (e.g. closed pipe).
        }
    }

    static String execute(String[] args) {
        for (String a : args) {
            if (a.equals("--help")) {
                return USAGE;
            }
        }

        boolean shape = false;
        int depth = 2;
        List<String> positional = new ArrayList<>();
        for (int i = 0; i < args.length; i++) {
            String a = args[i];
            if (a.equals("--shape")) {
                shape = true;
            } else if (a.equals("--depth") || a.startsWith("--depth=")) {
                String v;
                if (a.equals("--depth")) {
                    if (i + 1 >= args.length) {
                        throw new Exit(2, "error: --depth requires a value");
                    }
                    v = args[++i];
                } else {
                    v = a.substring("--depth=".length());
                }
                depth = parseDepth(v);
            } else if (a.startsWith("-") && !a.equals("-")) {
                throw new Exit(2, "unknown flag: " + a);
            } else {
                positional.add(a);
            }
        }
        if (positional.isEmpty()) {
            throw new Exit(2, "error: missing required argument: PATH");
        }
        if (positional.size() > 2) {
            throw new Exit(2, "error: unexpected argument: " + positional.get(2));
        }
        String pathText = positional.get(0);
        String file = positional.size() > 1 ? positional.get(1) : "-";

        List<Object> path = parsePath(pathText);
        Object root = new Parser(decode(readInput(file))).parseDocument();

        Object node = root;
        for (Object step : path) {
            node = step(node, step);
            if (node == null) {
                throw new Exit(1, "error: path not found: " + pathText);
            }
        }

        if (shape) {
            StringBuilder sb = new StringBuilder();
            shape(node, depth, sb);
            return sb.append('\n').toString();
        }
        if (node instanceof String s) {
            return s + "\n";
        }
        StringBuilder sb = new StringBuilder();
        writeJson(node, sb);
        return sb.append('\n').toString();
    }

    static int parseDepth(String v) {
        if (v.isEmpty() || !v.chars().allMatch(c -> c >= '0' && c <= '9')) {
            throw new Exit(2, "error: invalid --depth value: " + v);
        }
        try {
            return Integer.parseInt(v);
        } catch (NumberFormatException e) {
            return Integer.MAX_VALUE;
        }
    }

    // ---------------------------------------------------------------- input

    static byte[] readInput(String file) {
        try {
            if (file.equals("-")) {
                return readAll(System.in);
            }
            return Files.readAllBytes(Path.of(file));
        } catch (IOException | RuntimeException e) {
            throw new Exit(2, "error: cannot read file: " + file);
        }
    }

    static byte[] readAll(InputStream in) throws IOException {
        ByteArrayOutputStream buf = new ByteArrayOutputStream();
        in.transferTo(buf);
        return buf.toByteArray();
    }

    static String decode(byte[] bytes) {
        int start = 0;
        if (bytes.length >= 3 && (bytes[0] & 0xff) == 0xEF && (bytes[1] & 0xff) == 0xBB
                && (bytes[2] & 0xff) == 0xBF) {
            start = 3;
        }
        CharsetDecoder dec = StandardCharsets.UTF_8.newDecoder()
                .onMalformedInput(CodingErrorAction.REPORT)
                .onUnmappableCharacter(CodingErrorAction.REPORT);
        ByteBuffer in = ByteBuffer.wrap(bytes, start, bytes.length - start);
        CharBuffer out = CharBuffer.allocate(bytes.length);
        var result = dec.decode(in, out, true);
        if (result.isError()) {
            throw new Exit(2, "error: invalid JSON: invalid UTF-8 at byte offset " + in.position());
        }
        dec.flush(out);
        out.flip();
        return out.toString();
    }

    // ---------------------------------------------------------------- path

    /** Returns a list of String (object key) and BigInteger (array index) steps. */
    static List<Object> parsePath(String p) {
        List<Object> steps = new ArrayList<>();
        int i = 0;
        int n = p.length();
        if (p.startsWith(".")) {
            i = 1;
        }
        boolean expectKey = true; // at start, or right after a '.'
        while (i < n) {
            char c = p.charAt(i);
            if (c == '[') {
                int close = p.indexOf(']', i);
                String digits = close < 0 ? "" : p.substring(i + 1, close);
                if (digits.isEmpty() || !digits.chars().allMatch(d -> d >= '0' && d <= '9')) {
                    throw new Exit(2, "error: invalid path: " + p);
                }
                if (expectKey && !steps.isEmpty()) {
                    throw new Exit(2, "error: invalid path: " + p); // "a.[0]"
                }
                steps.add(new BigInteger(digits));
                i = close + 1;
                expectKey = false;
            } else if (c == '.') {
                if (expectKey) {
                    throw new Exit(2, "error: invalid path: " + p); // "a..b"
                }
                i++;
                expectKey = true;
                if (i == n) {
                    throw new Exit(2, "error: invalid path: " + p); // trailing '.'
                }
            } else {
                if (!expectKey) {
                    throw new Exit(2, "error: invalid path: " + p); // "a[0]b"
                }
                int j = i;
                while (j < n && p.charAt(j) != '.' && p.charAt(j) != '[') {
                    j++;
                }
                steps.add(p.substring(i, j));
                i = j;
                expectKey = false;
            }
        }
        return steps;
    }

    /** Applies one path step; returns null if it does not resolve. */
    static Object step(Object node, Object step) {
        if (step instanceof String key) {
            if (!(node instanceof JObject o)) {
                return null;
            }
            // Last occurrence wins for duplicate keys.
            for (int k = o.keys().size() - 1; k >= 0; k--) {
                if (o.keys().get(k).equals(key)) {
                    return o.values().get(k);
                }
            }
            return null;
        }
        BigInteger idx = (BigInteger) step;
        if (!(node instanceof List<?> list)) {
            return null;
        }
        if (idx.compareTo(BigInteger.valueOf(list.size())) >= 0) {
            return null;
        }
        return list.get(idx.intValue());
    }

    // ---------------------------------------------------------------- output

    static void shape(Object node, int depth, StringBuilder sb) {
        if (node instanceof String) {
            sb.append("string");
        } else if (node instanceof JNumber) {
            sb.append("number");
        } else if (node == JLiteral.NULL) {
            sb.append("null");
        } else if (node instanceof JLiteral) {
            sb.append("boolean");
        } else if (node instanceof List<?> list) {
            sb.append("array[").append(list.size()).append(']');
            if (depth > 0 && !list.isEmpty()) {
                sb.append(" of ");
                shape(list.get(0), depth - 1, sb);
            }
        } else {
            JObject o = (JObject) node;
            int size = o.keys().size();
            if (size == 0) {
                sb.append("object{}");
            } else if (depth == 0) {
                sb.append("object{").append(size).append(" keys}");
            } else {
                sb.append("object{");
                for (int k = 0; k < size; k++) {
                    if (k > 0) {
                        sb.append(", ");
                    }
                    sb.append(o.keys().get(k)).append(": ");
                    shape(o.values().get(k), depth - 1, sb);
                }
                sb.append('}');
            }
        }
    }

    static void writeJson(Object node, StringBuilder sb) {
        if (node instanceof String s) {
            writeString(s, sb);
        } else if (node instanceof JNumber num) {
            sb.append(canonicalNumber(num.literal()));
        } else if (node instanceof JLiteral lit) {
            sb.append(switch (lit) {
                case TRUE -> "true";
                case FALSE -> "false";
                case NULL -> "null";
            });
        } else if (node instanceof List<?> list) {
            sb.append('[');
            for (int k = 0; k < list.size(); k++) {
                if (k > 0) {
                    sb.append(',');
                }
                writeJson(list.get(k), sb);
            }
            sb.append(']');
        } else {
            JObject o = (JObject) node;
            sb.append('{');
            for (int k = 0; k < o.keys().size(); k++) {
                if (k > 0) {
                    sb.append(',');
                }
                writeString(o.keys().get(k), sb);
                sb.append(':');
                writeJson(o.values().get(k), sb);
            }
            sb.append('}');
        }
    }

    static void writeString(String s, StringBuilder sb) {
        sb.append('"');
        for (int k = 0; k < s.length(); k++) {
            char c = s.charAt(k);
            switch (c) {
                case '"' -> sb.append("\\\"");
                case '\\' -> sb.append("\\\\");
                case '\n' -> sb.append("\\n");
                case '\r' -> sb.append("\\r");
                case '\t' -> sb.append("\\t");
                case '\b' -> sb.append("\\b");
                case '\f' -> sb.append("\\f");
                default -> {
                    if (c < 0x20 || Character.isSurrogate(c) && !validSurrogateAt(s, k)) {
                        sb.append(String.format("\\u%04x", (int) c));
                    } else {
                        sb.append(c);
                    }
                }
            }
        }
        sb.append('"');
    }

    /** True if the surrogate at k is part of a well-formed pair. */
    static boolean validSurrogateAt(String s, int k) {
        char c = s.charAt(k);
        if (Character.isHighSurrogate(c)) {
            return k + 1 < s.length() && Character.isLowSurrogate(s.charAt(k + 1));
        }
        return k > 0 && Character.isHighSurrogate(s.charAt(k - 1));
    }

    /**
     * Canonical form of a JSON number literal: exact decimal value with no redundant zeros.
     * Integral values print without a decimal point; very large/small magnitudes use exponent form.
     */
    static String canonicalNumber(String literal) {
        BigDecimal d;
        try {
            d = new BigDecimal(literal);
        } catch (NumberFormatException | ArithmeticException e) {
            return literal; // exponent beyond BigDecimal's range; already valid JSON
        }
        boolean negative = literal.startsWith("-");
        if (d.signum() == 0) {
            return negative ? "-0" : "0";
        }
        d = d.stripTrailingZeros();
        String digits = d.unscaledValue().abs().toString();
        int exp = digits.length() - 1 - d.scale(); // decimal exponent of the leading digit
        String sign = d.signum() < 0 ? "-" : "";
        boolean integerLiteral = literal.chars().noneMatch(c -> c == '.' || c == 'e' || c == 'E');
        if (integerLiteral || (exp >= -7 && exp < 21)) {
            return d.toPlainString();
        }
        String mantissa = digits.length() == 1 ? digits : digits.charAt(0) + "." + digits.substring(1);
        return sign + mantissa + "e" + (exp > 0 ? "+" : "") + exp;
    }

    // ---------------------------------------------------------------- parser

    static final class Parser {
        final String s;
        int pos;

        Parser(String s) {
            this.s = s;
        }

        Object parseDocument() {
            skipWs();
            if (pos >= s.length()) {
                throw fail("unexpected end of input, expected a value");
            }
            Object v = parseValue();
            skipWs();
            if (pos < s.length()) {
                throw fail("unexpected trailing content");
            }
            return v;
        }

        Exit fail(String reason) {
            int line = 1;
            int col = 1;
            for (int k = 0; k < pos && k < s.length(); k++) {
                if (s.charAt(k) == '\n') {
                    line++;
                    col = 1;
                } else if (!Character.isLowSurrogate(s.charAt(k))) {
                    col++;
                }
            }
            return new Exit(2, "error: invalid JSON: " + reason + " at line " + line + ", column " + col);
        }

        void skipWs() {
            while (pos < s.length()) {
                char c = s.charAt(pos);
                if (c == ' ' || c == '\t' || c == '\n' || c == '\r') {
                    pos++;
                } else {
                    break;
                }
            }
        }

        Object parseValue() {
            if (pos >= s.length()) {
                throw fail("unexpected end of input, expected a value");
            }
            char c = s.charAt(pos);
            return switch (c) {
                case '{' -> parseObject();
                case '[' -> parseArray();
                case '"' -> parseString();
                case 't' -> literal("true", JLiteral.TRUE);
                case 'f' -> literal("false", JLiteral.FALSE);
                case 'n' -> literal("null", JLiteral.NULL);
                default -> {
                    if (c == '-' || (c >= '0' && c <= '9')) {
                        yield parseNumber();
                    }
                    throw fail("unexpected character " + describe(c));
                }
            };
        }

        static String describe(char c) {
            if (c < 0x20 || c == 0x7f) {
                return String.format("U+%04X", (int) c);
            }
            return "'" + c + "'";
        }

        Object literal(String word, JLiteral value) {
            if (!s.startsWith(word, pos)) {
                throw fail("invalid literal, expected '" + word + "'");
            }
            pos += word.length();
            return value;
        }

        JObject parseObject() {
            pos++; // '{'
            List<String> keys = new ArrayList<>();
            List<Object> values = new ArrayList<>();
            skipWs();
            if (pos < s.length() && s.charAt(pos) == '}') {
                pos++;
                return new JObject(keys, values);
            }
            while (true) {
                skipWs();
                if (pos >= s.length()) {
                    throw fail("unexpected end of input in object");
                }
                if (s.charAt(pos) != '"') {
                    throw fail("expected string key in object");
                }
                keys.add(parseString());
                skipWs();
                if (pos >= s.length()) {
                    throw fail("unexpected end of input in object");
                }
                if (s.charAt(pos) != ':') {
                    throw fail("expected ':' after object key");
                }
                pos++;
                skipWs();
                values.add(parseValue());
                skipWs();
                if (pos >= s.length()) {
                    throw fail("unexpected end of input in object");
                }
                char c = s.charAt(pos);
                if (c == ',') {
                    pos++;
                } else if (c == '}') {
                    pos++;
                    return new JObject(keys, values);
                } else {
                    throw fail("expected ',' or '}' in object");
                }
            }
        }

        List<Object> parseArray() {
            pos++; // '['
            List<Object> items = new ArrayList<>();
            skipWs();
            if (pos < s.length() && s.charAt(pos) == ']') {
                pos++;
                return items;
            }
            while (true) {
                skipWs();
                items.add(parseValue());
                skipWs();
                if (pos >= s.length()) {
                    throw fail("unexpected end of input in array");
                }
                char c = s.charAt(pos);
                if (c == ',') {
                    pos++;
                } else if (c == ']') {
                    pos++;
                    return items;
                } else {
                    throw fail("expected ',' or ']' in array");
                }
            }
        }

        String parseString() {
            pos++; // opening quote
            StringBuilder sb = new StringBuilder();
            while (true) {
                if (pos >= s.length()) {
                    throw fail("unterminated string");
                }
                char c = s.charAt(pos);
                if (c == '"') {
                    pos++;
                    return sb.toString();
                }
                if (c < 0x20) {
                    throw fail("unescaped control character " + describe(c) + " in string");
                }
                if (c != '\\') {
                    sb.append(c);
                    pos++;
                    continue;
                }
                pos++;
                if (pos >= s.length()) {
                    throw fail("unterminated string");
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
                        if (pos + 5 > s.length()) {
                            throw fail("incomplete \\u escape");
                        }
                        int v = 0;
                        for (int k = 1; k <= 4; k++) {
                            int h = Character.digit(s.charAt(pos + k), 16);
                            if (h < 0) {
                                throw fail("invalid \\u escape");
                            }
                            v = v * 16 + h;
                        }
                        sb.append((char) v);
                        pos += 4;
                    }
                    default -> throw fail("invalid escape '\\" + e + "'");
                }
                pos++;
            }
        }

        JNumber parseNumber() {
            int start = pos;
            if (s.charAt(pos) == '-') {
                pos++;
            }
            if (pos >= s.length() || !isDigit(s.charAt(pos))) {
                throw fail("invalid number");
            }
            if (s.charAt(pos) == '0') {
                pos++;
                if (pos < s.length() && isDigit(s.charAt(pos))) {
                    throw fail("leading zeros are not allowed in numbers");
                }
            } else {
                while (pos < s.length() && isDigit(s.charAt(pos))) {
                    pos++;
                }
            }
            if (pos < s.length() && s.charAt(pos) == '.') {
                pos++;
                if (pos >= s.length() || !isDigit(s.charAt(pos))) {
                    throw fail("expected digit after decimal point");
                }
                while (pos < s.length() && isDigit(s.charAt(pos))) {
                    pos++;
                }
            }
            if (pos < s.length() && (s.charAt(pos) == 'e' || s.charAt(pos) == 'E')) {
                pos++;
                if (pos < s.length() && (s.charAt(pos) == '+' || s.charAt(pos) == '-')) {
                    pos++;
                }
                if (pos >= s.length() || !isDigit(s.charAt(pos))) {
                    throw fail("expected digit in exponent");
                }
                while (pos < s.length() && isDigit(s.charAt(pos))) {
                    pos++;
                }
            }
            return new JNumber(s.substring(start, pos));
        }

        static boolean isDigit(char c) {
            return c >= '0' && c <= '9';
        }
    }
}
