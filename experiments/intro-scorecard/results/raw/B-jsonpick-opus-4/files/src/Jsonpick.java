import java.io.ByteArrayOutputStream;
import java.io.FileDescriptor;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
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
import java.util.List;

/** jsonpick: extract a value by path from JSON. See SPEC.md. */
public final class Jsonpick {

    static final int EXIT_OK = 0;
    static final int EXIT_NOT_FOUND = 1;
    static final int EXIT_USAGE = 2;

    static final String HELP = String.join("\n",
            "Usage: jsonpick PATH [FILE] [--shape] [--depth N]",
            "",
            "Extract a value by path from JSON.",
            "",
            "Arguments:",
            "  PATH        Path to select, e.g. a.b[0].c. Use \".\" or \"\" for the whole document.",
            "  FILE        JSON file to read. Omitted or \"-\" means stdin.",
            "",
            "Flags:",
            "  --shape     Print a depth-limited type summary of the selected value",
            "              instead of the value itself.",
            "  --depth N   Levels to describe in --shape mode (default 2).",
            "  --help      Print this help and exit.") + "\n";

    // ---------------------------------------------------------------- JSON model

    /** Parsed JSON value. Objects keep entries in document order (duplicates included). */
    sealed interface Value permits JString, JNumber, JBool, JNull, JArray, JObject {}
    record JString(String value) implements Value {}
    record JNumber(String text) implements Value {}
    record JBool(boolean value) implements Value {}
    record JNull() implements Value {}
    record JArray(List<Value> items) implements Value {}
    record JObject(List<String> keys, List<Value> values) implements Value {
        /** Last occurrence wins for duplicate keys. */
        Value get(String key) {
            for (int i = keys.size() - 1; i >= 0; i--) {
                if (keys.get(i).equals(key)) return values.get(i);
            }
            return null;
        }
    }

    static final class UsageException extends Exception {
        UsageException(String message) { super(message); }
    }

    static final class ParseException extends Exception {
        ParseException(String message) { super(message); }
    }

    // ---------------------------------------------------------------- parser

    static final class Parser {
        private final String s;
        private int pos;

        Parser(String s) {
            this.s = s;
            // Ignore a leading UTF-8 byte order mark.
            this.pos = (!s.isEmpty() && s.charAt(0) == '﻿') ? 1 : 0;
        }

        Value parseDocument() throws ParseException {
            skipWs();
            Value v = parseValue();
            skipWs();
            if (pos < s.length()) throw error("unexpected trailing content");
            return v;
        }

        private ParseException error(String reason) {
            int line = 1;
            int col = 1;
            for (int i = 0; i < pos && i < s.length(); i++) {
                char c = s.charAt(i);
                if (c == '\n') {
                    line++;
                    col = 1;
                } else if (!Character.isLowSurrogate(c)) {
                    col++;
                }
            }
            return new ParseException(reason + " at line " + line + ", column " + col);
        }

        private String describeCurrent() {
            if (pos >= s.length()) return "end of input";
            int cp = s.codePointAt(pos);
            if (cp < 0x20 || cp == 0x7F) return String.format("character U+%04X", cp);
            return "character '" + new String(Character.toChars(cp)) + "'";
        }

        private void skipWs() {
            while (pos < s.length()) {
                char c = s.charAt(pos);
                if (c == ' ' || c == '\t' || c == '\n' || c == '\r') pos++;
                else break;
            }
        }

        private Value parseValue() throws ParseException {
            if (pos >= s.length()) throw error("unexpected end of input");
            char c = s.charAt(pos);
            switch (c) {
                case '{': return parseObject();
                case '[': return parseArray();
                case '"': return new JString(parseString());
                case 't': expectLiteral("true"); return new JBool(true);
                case 'f': expectLiteral("false"); return new JBool(false);
                case 'n': expectLiteral("null"); return new JNull();
                default:
                    if (c == '-' || (c >= '0' && c <= '9')) return parseNumber();
                    throw error("unexpected " + describeCurrent());
            }
        }

        private void expectLiteral(String lit) throws ParseException {
            for (int i = 0; i < lit.length(); i++) {
                if (pos >= s.length() || s.charAt(pos) != lit.charAt(i)) {
                    throw error("unexpected " + describeCurrent() + " (expected '" + lit + "')");
                }
                pos++;
            }
        }

        private Value parseObject() throws ParseException {
            pos++; // '{'
            List<String> keys = new ArrayList<>();
            List<Value> values = new ArrayList<>();
            skipWs();
            if (pos < s.length() && s.charAt(pos) == '}') {
                pos++;
                return new JObject(keys, values);
            }
            while (true) {
                skipWs();
                if (pos >= s.length() || s.charAt(pos) != '"') {
                    throw error("unexpected " + describeCurrent() + " (expected object key string)");
                }
                keys.add(parseString());
                skipWs();
                if (pos >= s.length() || s.charAt(pos) != ':') {
                    throw error("unexpected " + describeCurrent() + " (expected ':')");
                }
                pos++;
                skipWs();
                values.add(parseValue());
                skipWs();
                if (pos < s.length() && s.charAt(pos) == ',') {
                    pos++;
                } else if (pos < s.length() && s.charAt(pos) == '}') {
                    pos++;
                    return new JObject(keys, values);
                } else {
                    throw error("unexpected " + describeCurrent() + " (expected ',' or '}')");
                }
            }
        }

        private Value parseArray() throws ParseException {
            pos++; // '['
            List<Value> items = new ArrayList<>();
            skipWs();
            if (pos < s.length() && s.charAt(pos) == ']') {
                pos++;
                return new JArray(items);
            }
            while (true) {
                skipWs();
                items.add(parseValue());
                skipWs();
                if (pos < s.length() && s.charAt(pos) == ',') {
                    pos++;
                } else if (pos < s.length() && s.charAt(pos) == ']') {
                    pos++;
                    return new JArray(items);
                } else {
                    throw error("unexpected " + describeCurrent() + " (expected ',' or ']')");
                }
            }
        }

        private String parseString() throws ParseException {
            pos++; // opening quote
            StringBuilder sb = new StringBuilder();
            while (true) {
                if (pos >= s.length()) throw error("unterminated string");
                char c = s.charAt(pos);
                if (c == '"') {
                    pos++;
                    return sb.toString();
                }
                if (c < 0x20) throw error("unescaped control " + describeCurrent() + " in string");
                if (c != '\\') {
                    sb.append(c);
                    pos++;
                    continue;
                }
                pos++;
                if (pos >= s.length()) throw error("unterminated string");
                char e = s.charAt(pos);
                switch (e) {
                    case '"': sb.append('"'); break;
                    case '\\': sb.append('\\'); break;
                    case '/': sb.append('/'); break;
                    case 'b': sb.append('\b'); break;
                    case 'f': sb.append('\f'); break;
                    case 'n': sb.append('\n'); break;
                    case 'r': sb.append('\r'); break;
                    case 't': sb.append('\t'); break;
                    case 'u': {
                        int code = 0;
                        for (int i = 1; i <= 4; i++) {
                            int d = pos + i < s.length() ? Character.digit(s.charAt(pos + i), 16) : -1;
                            if (d < 0) {
                                pos += i;
                                throw error("invalid \\u escape in string");
                            }
                            code = code * 16 + d;
                        }
                        sb.append((char) code);
                        pos += 4;
                        break;
                    }
                    default:
                        throw error("invalid escape '\\" + e + "' in string");
                }
                pos++;
            }
        }

        private Value parseNumber() throws ParseException {
            int start = pos;
            if (s.charAt(pos) == '-') pos++;
            if (pos >= s.length() || !isDigit(s.charAt(pos))) {
                throw error("invalid number: expected digit, got " + describeCurrent());
            }
            if (s.charAt(pos) == '0') {
                pos++;
            } else {
                while (pos < s.length() && isDigit(s.charAt(pos))) pos++;
            }
            if (pos < s.length() && s.charAt(pos) == '.') {
                pos++;
                if (pos >= s.length() || !isDigit(s.charAt(pos))) {
                    throw error("invalid number: expected digit after '.', got " + describeCurrent());
                }
                while (pos < s.length() && isDigit(s.charAt(pos))) pos++;
            }
            if (pos < s.length() && (s.charAt(pos) == 'e' || s.charAt(pos) == 'E')) {
                pos++;
                if (pos < s.length() && (s.charAt(pos) == '+' || s.charAt(pos) == '-')) pos++;
                if (pos >= s.length() || !isDigit(s.charAt(pos))) {
                    throw error("invalid number: expected exponent digit, got " + describeCurrent());
                }
                while (pos < s.length() && isDigit(s.charAt(pos))) pos++;
            }
            return new JNumber(s.substring(start, pos));
        }

        private static boolean isDigit(char c) {
            return c >= '0' && c <= '9';
        }
    }

    // ---------------------------------------------------------------- path

    sealed interface Step permits KeyStep, IndexStep {}
    record KeyStep(String key) implements Step {}
    record IndexStep(BigInteger index) implements Step {}

    static List<Step> parsePath(String path) throws UsageException {
        List<Step> steps = new ArrayList<>();
        int i = 0;
        int n = path.length();
        if (i < n && path.charAt(i) == '.') i++; // leading '.' selects the root
        boolean expectKey = true; // at start, or right after '.'
        boolean first = true;
        while (i < n) {
            char c = path.charAt(i);
            if (c == '[') {
                int close = path.indexOf(']', i);
                if (close < 0) throw invalidPath(path);
                String num = path.substring(i + 1, close);
                if (num.isEmpty() || !num.chars().allMatch(ch -> ch >= '0' && ch <= '9')) {
                    throw invalidPath(path);
                }
                // "a.[0]" is malformed; "[0]" and ".[0]" at the start are fine.
                if (expectKey && !first) throw invalidPath(path);
                steps.add(new IndexStep(new BigInteger(num)));
                i = close + 1;
                expectKey = false;
            } else if (c == '.') {
                if (expectKey) throw invalidPath(path); // empty key segment, e.g. "a..b"
                i++;
                expectKey = true;
            } else {
                if (!expectKey) throw invalidPath(path); // e.g. "a[0]b"
                int start = i;
                while (i < n && path.charAt(i) != '.' && path.charAt(i) != '[') i++;
                steps.add(new KeyStep(path.substring(start, i)));
                expectKey = false;
            }
            first = false;
        }
        if (expectKey && !first) throw invalidPath(path); // trailing '.', e.g. "a."
        return steps;
    }

    static UsageException invalidPath(String path) {
        return new UsageException("error: invalid path: " + path);
    }

    static Value resolve(Value root, List<Step> steps) {
        Value cur = root;
        for (Step step : steps) {
            if (step instanceof KeyStep k) {
                if (!(cur instanceof JObject o)) return null;
                cur = o.get(k.key());
            } else {
                IndexStep idx = (IndexStep) step;
                if (!(cur instanceof JArray a)) return null;
                if (idx.index().compareTo(BigInteger.valueOf(a.items().size())) >= 0) return null;
                cur = a.items().get(idx.index().intValue());
            }
            if (cur == null) return null;
        }
        return cur;
    }

    // ---------------------------------------------------------------- output

    /** Canonical JSON form of a number literal. */
    static String canonicalNumber(String text) {
        BigDecimal d;
        try {
            d = new BigDecimal(text);
        } catch (NumberFormatException | ArithmeticException e) {
            return text; // exponent out of range; keep the literal as written
        }
        boolean negative = text.startsWith("-");
        if (d.signum() == 0) return negative ? "-0" : "0";
        d = d.stripTrailingZeros();
        String digits = d.unscaledValue().abs().toString();
        long exp = (long) digits.length() - 1 - d.scale(); // value = d.ddd * 10^exp
        String sign = d.signum() < 0 ? "-" : "";
        if (d.scale() <= 0) {
            // Integer: print in full unless absurdly long.
            if (exp < 1000) return sign + digits + "0".repeat(-d.scale());
            return sign + scientific(digits, exp);
        }
        if (exp >= -7) return d.toPlainString();
        return sign + scientific(digits, exp);
    }

    static String scientific(String digits, long exp) {
        String mantissa = digits.length() == 1 ? digits : digits.charAt(0) + "." + digits.substring(1);
        return mantissa + "e" + (exp >= 0 ? "+" : "") + exp;
    }

    static void writeCompact(Value v, StringBuilder out) {
        switch (v) {
            case JString str -> writeJsonString(str.value(), out);
            case JNumber num -> out.append(canonicalNumber(num.text()));
            case JBool b -> out.append(b.value());
            case JNull ignored -> out.append("null");
            case JArray a -> {
                out.append('[');
                for (int i = 0; i < a.items().size(); i++) {
                    if (i > 0) out.append(',');
                    writeCompact(a.items().get(i), out);
                }
                out.append(']');
            }
            case JObject o -> {
                out.append('{');
                for (int i = 0; i < o.keys().size(); i++) {
                    if (i > 0) out.append(',');
                    writeJsonString(o.keys().get(i), out);
                    out.append(':');
                    writeCompact(o.values().get(i), out);
                }
                out.append('}');
            }
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

    static void writeShape(Value v, int budget, StringBuilder out) {
        switch (v) {
            case JString ignored -> out.append("string");
            case JNumber ignored -> out.append("number");
            case JBool ignored -> out.append("boolean");
            case JNull ignored -> out.append("null");
            case JArray a -> {
                out.append("array[").append(a.items().size()).append(']');
                if (budget > 0 && !a.items().isEmpty()) {
                    out.append(" of ");
                    writeShape(a.items().get(0), budget - 1, out);
                }
            }
            case JObject o -> {
                int n = o.keys().size();
                if (n == 0) {
                    out.append("object{}");
                } else if (budget == 0) {
                    out.append("object{").append(n).append(" keys}");
                } else {
                    out.append("object{");
                    for (int i = 0; i < n; i++) {
                        if (i > 0) out.append(", ");
                        out.append(o.keys().get(i)).append(": ");
                        writeShape(o.values().get(i), budget - 1, out);
                    }
                    out.append('}');
                }
            }
        }
    }

    // ---------------------------------------------------------------- CLI

    record Options(String path, String file, boolean shape, int depth, boolean help) {}

    static Options parseArgs(String[] args) throws UsageException {
        List<String> positional = new ArrayList<>();
        boolean shape = false;
        int depth = 2;
        for (int i = 0; i < args.length; i++) {
            String a = args[i];
            if (a.equals("--help")) {
                return new Options(null, null, false, 0, true);
            } else if (a.equals("--shape")) {
                shape = true;
            } else if (a.equals("--depth") || a.startsWith("--depth=")) {
                String val;
                if (a.equals("--depth")) {
                    if (i + 1 >= args.length) throw new UsageException("error: --depth requires a value");
                    val = args[++i];
                } else {
                    val = a.substring("--depth=".length());
                }
                depth = parseDepth(val);
            } else if (a.startsWith("-") && !a.equals("-")) {
                throw new UsageException("unknown flag: " + a);
            } else {
                positional.add(a);
            }
        }
        if (positional.isEmpty()) throw new UsageException("error: missing required argument: PATH");
        if (positional.size() > 2) {
            throw new UsageException("error: unexpected argument: " + positional.get(2));
        }
        String file = positional.size() == 2 ? positional.get(1) : "-";
        return new Options(positional.get(0), file, shape, depth, false);
    }

    static int parseDepth(String val) throws UsageException {
        if (val.isEmpty() || !val.chars().allMatch(ch -> ch >= '0' && ch <= '9')) {
            throw new UsageException("error: invalid --depth value: " + val);
        }
        try {
            return Integer.parseInt(val);
        } catch (NumberFormatException e) {
            return Integer.MAX_VALUE; // deeper than any document can be
        }
    }

    static String decodeUtf8(byte[] bytes) throws ParseException {
        CharsetDecoder dec = StandardCharsets.UTF_8.newDecoder()
                .onMalformedInput(CodingErrorAction.REPORT)
                .onUnmappableCharacter(CodingErrorAction.REPORT);
        ByteBuffer in = ByteBuffer.wrap(bytes);
        CharBuffer out = CharBuffer.allocate(bytes.length);
        var result = dec.decode(in, out, true);
        if (result.isError()) {
            throw new ParseException("invalid UTF-8 at byte offset " + in.position());
        }
        dec.flush(out);
        out.flip();
        return out.toString();
    }

    static byte[] readInput(String file) throws UsageException {
        try {
            if (file.equals("-")) {
                InputStream in = System.in;
                ByteArrayOutputStream buf = new ByteArrayOutputStream();
                in.transferTo(buf);
                return buf.toByteArray();
            }
            return Files.readAllBytes(Path.of(file));
        } catch (IOException | RuntimeException e) {
            throw new UsageException("error: cannot read file: " + file);
        }
    }

    /** Runs the CLI, writing to the given buffers; returns the exit code. */
    static int run(String[] args, StringBuilder stdout, StringBuilder stderr) {
        Options opts;
        List<Step> steps;
        try {
            opts = parseArgs(args);
            if (opts.help()) {
                stdout.append(HELP);
                return EXIT_OK;
            }
            steps = parsePath(opts.path());
        } catch (UsageException e) {
            stderr.append(e.getMessage()).append('\n');
            return EXIT_USAGE;
        }

        Value root;
        try {
            byte[] bytes = readInput(opts.file());
            root = new Parser(decodeUtf8(bytes)).parseDocument();
        } catch (UsageException e) {
            stderr.append(e.getMessage()).append('\n');
            return EXIT_USAGE;
        } catch (ParseException e) {
            stderr.append("error: invalid JSON: ").append(e.getMessage()).append('\n');
            return EXIT_USAGE;
        } catch (StackOverflowError e) {
            stderr.append("error: invalid JSON: nesting too deep").append('\n');
            return EXIT_USAGE;
        }

        Value v = resolve(root, steps);
        if (v == null) {
            stderr.append("error: path not found: ").append(opts.path()).append('\n');
            return EXIT_NOT_FOUND;
        }

        if (opts.shape()) {
            writeShape(v, opts.depth(), stdout);
        } else if (v instanceof JString str) {
            stdout.append(str.value());
        } else {
            writeCompact(v, stdout);
        }
        stdout.append('\n');
        return EXIT_OK;
    }

    static void emit(OutputStream os, CharSequence text) {
        if (text.isEmpty()) return;
        try {
            // Lone surrogates (from unicode escapes) cannot be encoded; replace them with U+FFFD.
            String s = text.toString();
            StringBuilder clean = null;
            for (int i = 0; i < s.length(); i++) {
                char c = s.charAt(i);
                boolean bad = false;
                if (Character.isHighSurrogate(c)) {
                    if (i + 1 < s.length() && Character.isLowSurrogate(s.charAt(i + 1))) {
                        if (clean != null) clean.append(c).append(s.charAt(i + 1));
                        i++;
                        continue;
                    }
                    bad = true;
                } else if (Character.isLowSurrogate(c)) {
                    bad = true;
                }
                if (bad && clean == null) clean = new StringBuilder(s.substring(0, i));
                if (clean != null) clean.append(bad ? '�' : c);
            }
            os.write((clean != null ? clean.toString() : s).getBytes(StandardCharsets.UTF_8));
            os.flush();
        } catch (IOException e) {
            // Nothing sensible to report (e.g. closed pipe).
        }
    }

    public static void main(String[] args) throws InterruptedException {
        StringBuilder out = new StringBuilder();
        StringBuilder err = new StringBuilder();
        int[] code = new int[1];
        // Large stack so deeply nested documents parse without overflowing.
        Thread worker = new Thread(null, () -> code[0] = run(args, out, err), "jsonpick", 1L << 30);
        worker.start();
        worker.join();
        emit(new FileOutputStream(FileDescriptor.out), out);
        emit(new FileOutputStream(FileDescriptor.err), err);
        System.exit(code[0]);
    }
}
