import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.PrintStream;
import java.math.BigDecimal;
import java.nio.ByteBuffer;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

public class JsonPick {
    static final String HELP = String.join("\n",
        "Usage: jsonpick PATH [FILE] [--shape] [--depth N]",
        "",
        "Extract a value by path from JSON.",
        "",
        "Arguments:",
        "  PATH      required; e.g. a.b[0].c ('.' or \"\" selects the whole document)",
        "  FILE      JSON file to read; omitted or '-' means stdin",
        "",
        "Options:",
        "  --shape   print a depth-limited type/array-length summary of the subtree at PATH",
        "  --depth N depth of the --shape summary (default 2)",
        "  --help    show this message and exit",
        "");

    static final class UsageError extends Exception {
        UsageError(String m) { super(m); }
    }

    static final class ParseError extends Exception {
        ParseError(String m) { super(m); }
    }

    static final Object NULL = new Object() {
        public String toString() { return "null"; }
    };

    /** Number kept as its source text. */
    record Num(String text) {}

    public static void main(String[] args) throws Exception {
        int[] code = {0};
        Thread t = new Thread(null, () -> code[0] = run(args), "main", 1L << 29);
        t.start();
        t.join();
        System.exit(code[0]);
    }

    static void emit(PrintStream ps, String s) {
        byte[] b = s.getBytes(StandardCharsets.UTF_8);
        ps.write(b, 0, b.length);
        ps.flush();
    }

    static int run(String[] args) {
        PrintStream out = new PrintStream(new java.io.FileOutputStream(java.io.FileDescriptor.out), false);
        PrintStream err = new PrintStream(new java.io.FileOutputStream(java.io.FileDescriptor.err), false);
        try {
            String path = null, file = null;
            boolean shape = false, onlyPositional = false;
            int depth = 2;
            for (int i = 0; i < args.length; i++) {
                String a = args[i];
                if (!onlyPositional && a.startsWith("-") && !a.equals("-")) {
                    if (a.equals("--")) { onlyPositional = true; continue; }
                    if (a.equals("--help")) { emit(out, HELP); return 0; }
                    if (a.equals("--shape")) { shape = true; continue; }
                    if (a.equals("--depth") || a.startsWith("--depth=")) {
                        String v;
                        if (a.startsWith("--depth=")) v = a.substring(8);
                        else if (i + 1 < args.length) v = args[++i];
                        else throw new UsageError("--depth requires a value");
                        try {
                            depth = Integer.parseInt(v);
                        } catch (NumberFormatException e) {
                            throw new UsageError("invalid value for --depth: " + v);
                        }
                        if (depth < 0) throw new UsageError("invalid value for --depth: " + v);
                        continue;
                    }
                    throw new UsageError("unknown flag: " + a);
                }
                if (path == null) path = a;
                else if (file == null) file = a;
                else throw new UsageError("unexpected argument: " + a);
            }
            if (path == null) throw new UsageError("missing required argument: PATH");

            byte[] data;
            try {
                if (file == null || file.equals("-")) data = readAll(System.in);
                else data = Files.readAllBytes(Paths.get(file));
            } catch (IOException e) {
                throw new UsageError("cannot read input: " + (file == null ? "-" : file));
            }

            Object root;
            try {
                root = parse(data);
            } catch (ParseError e) {
                emit(err, "error: invalid JSON: " + e.getMessage() + "\n");
                return 2;
            } catch (StackOverflowError e) {
                emit(err, "error: invalid JSON: nesting too deep\n");
                return 2;
            }

            Object v = navigate(root, path);
            if (v == null) {
                emit(err, "error: path not found: " + path + "\n");
                return 1;
            }
            if (shape) emit(out, shape(v, depth) + "\n");
            else if (v instanceof String s) emit(out, s + "\n");
            else {
                StringBuilder sb = new StringBuilder();
                write(sb, v);
                emit(out, sb.append('\n').toString());
            }
            return 0;
        } catch (UsageError e) {
            emit(err, (e.getMessage().startsWith("unknown flag: ") ? "" : "error: ") + e.getMessage() + "\n");
            return 2;
        }
    }

    static byte[] readAll(InputStream in) throws IOException {
        ByteArrayOutputStream bo = new ByteArrayOutputStream();
        in.transferTo(bo);
        return bo.toByteArray();
    }

    // ---------- path ----------

    /** Returns the resolved value, or null if not found. */
    static Object navigate(Object cur, String path) {
        int i = 0, n = path.length();
        if (n == 0 || path.equals(".")) return cur;
        if (path.charAt(0) == '.') i = 1;
        boolean first = true;
        while (i < n) {
            char c = path.charAt(i);
            if (c == '[') {
                int close = path.indexOf(']', i);
                if (close < 0) return null;
                String idx = path.substring(i + 1, close);
                if (idx.isEmpty() || idx.length() > 9) return null;
                for (int k = 0; k < idx.length(); k++) {
                    char d = idx.charAt(k);
                    if (d < '0' || d > '9') return null;
                }
                if (!(cur instanceof List<?> l)) return null;
                int ix = Integer.parseInt(idx);
                if (ix >= l.size()) return null;
                cur = l.get(ix);
                i = close + 1;
                first = false;
                // after ']' must come '.', '[' or end
                if (i < n && path.charAt(i) != '.' && path.charAt(i) != '[') return null;
            } else {
                if (c == '.') {
                    if (first) return null;
                    i++; // skip separator
                    if (i >= n) return null; // trailing dot
                    if (path.charAt(i) == '[') continue;
                }
                int j = i;
                while (j < n && path.charAt(j) != '.' && path.charAt(j) != '[') j++;
                String key = path.substring(i, j);
                if (!(cur instanceof Map<?, ?> m)) return null;
                if (!m.containsKey(key)) return null;
                cur = m.get(key);
                i = j;
                first = false;
            }
        }
        return cur;
    }

    // ---------- shape ----------

    static String shape(Object v, int budget) {
        if (v instanceof String) return "string";
        if (v instanceof Num) return "number";
        if (v instanceof Boolean) return "boolean";
        if (v == NULL) return "null";
        if (v instanceof List<?> l) {
            if (l.isEmpty()) return "array[0]";
            if (budget <= 0) return "array[" + l.size() + "]";
            return "array[" + l.size() + "] of " + shape(l.get(0), budget - 1);
        }
        Map<?, ?> m = (Map<?, ?>) v;
        if (m.isEmpty()) return "object{}";
        if (budget <= 0) return "object{" + m.size() + "}";
        StringBuilder sb = new StringBuilder("object{");
        boolean f = true;
        for (Map.Entry<?, ?> e : m.entrySet()) {
            if (!f) sb.append(", ");
            f = false;
            sb.append(e.getKey()).append(": ").append(shape(e.getValue(), budget - 1));
        }
        return sb.append('}').toString();
    }

    // ---------- output ----------

    static String canonNumber(String t) {
        try {
            BigDecimal bd = new BigDecimal(t);
            if (bd.signum() == 0) return t.startsWith("-") ? "-0" : "0";
            if (Math.abs((long) bd.precision() - bd.scale()) > 1000) return t;
            bd = bd.stripTrailingZeros();
            if (bd.scale() <= 0) return bd.toBigIntegerExact().toString();
            return bd.toPlainString();
        } catch (ArithmeticException | NumberFormatException e) {
            return t;
        }
    }

    static void write(StringBuilder sb, Object v) {
        if (v instanceof String s) quote(sb, s);
        else if (v instanceof Num n) sb.append(canonNumber(n.text()));
        else if (v instanceof Boolean || v == NULL) sb.append(v);
        else if (v instanceof List<?> l) {
            sb.append('[');
            for (int i = 0; i < l.size(); i++) {
                if (i > 0) sb.append(',');
                write(sb, l.get(i));
            }
            sb.append(']');
        } else {
            sb.append('{');
            boolean f = true;
            for (Map.Entry<?, ?> e : ((Map<?, ?>) v).entrySet()) {
                if (!f) sb.append(',');
                f = false;
                quote(sb, (String) e.getKey());
                sb.append(':');
                write(sb, e.getValue());
            }
            sb.append('}');
        }
    }

    static void quote(StringBuilder sb, String s) {
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
                    if (c < 0x20) sb.append(String.format("\\u%04x", (int) c));
                    else sb.append(c);
                }
            }
        }
        sb.append('"');
    }

    // ---------- parser ----------

    static String text;
    static int pos;

    static Object parse(byte[] data) throws ParseError {
        try {
            text = StandardCharsets.UTF_8.newDecoder()
                .onMalformedInput(CodingErrorAction.REPORT)
                .onUnmappableCharacter(CodingErrorAction.REPORT)
                .decode(ByteBuffer.wrap(data)).toString();
        } catch (CharacterCodingException e) {
            // locate offending byte
            int off = 0;
            var dec = StandardCharsets.UTF_8.newDecoder()
                .onMalformedInput(CodingErrorAction.REPORT)
                .onUnmappableCharacter(CodingErrorAction.REPORT);
            ByteBuffer bb = ByteBuffer.wrap(data);
            java.nio.CharBuffer cb = java.nio.CharBuffer.allocate(data.length + 1);
            dec.decode(bb, cb, true);
            off = bb.position();
            throw new ParseError("invalid UTF-8 at byte offset " + off);
        }
        pos = 0;
        if (text.startsWith("﻿")) pos = 1;
        ws();
        Object v = value();
        ws();
        if (pos < text.length()) throw err("unexpected trailing content");
        return v;
    }

    static ParseError err(String msg) {
        int line = 1, col = 1;
        for (int i = 0; i < pos && i < text.length(); i++) {
            if (text.charAt(i) == '\n') { line++; col = 1; }
            else col++;
        }
        return new ParseError(msg + " at line " + line + " col " + col);
    }

    static void ws() {
        while (pos < text.length()) {
            char c = text.charAt(pos);
            if (c == ' ' || c == '\t' || c == '\n' || c == '\r') pos++;
            else break;
        }
    }

    static Object value() throws ParseError {
        if (pos >= text.length()) throw err("unexpected end of input");
        char c = text.charAt(pos);
        switch (c) {
            case '{': return object();
            case '[': return array();
            case '"': return string();
            case 't': lit("true"); return Boolean.TRUE;
            case 'f': lit("false"); return Boolean.FALSE;
            case 'n': lit("null"); return NULL;
            default:
                if (c == '-' || (c >= '0' && c <= '9')) return number();
                throw err("unexpected character '" + c + "'");
        }
    }

    static void lit(String w) throws ParseError {
        if (!text.startsWith(w, pos)) throw err("invalid literal");
        pos += w.length();
    }

    static Object number() throws ParseError {
        int s = pos;
        if (text.charAt(pos) == '-') pos++;
        if (pos >= text.length()) throw err("invalid number");
        char c = text.charAt(pos);
        if (c == '0') pos++;
        else if (c >= '1' && c <= '9') digits();
        else throw err("invalid number");
        if (pos < text.length() && text.charAt(pos) == '.') {
            pos++;
            if (!digit()) throw err("invalid number: expected digit after '.'");
            digits();
        }
        if (pos < text.length() && (text.charAt(pos) == 'e' || text.charAt(pos) == 'E')) {
            pos++;
            if (pos < text.length() && (text.charAt(pos) == '+' || text.charAt(pos) == '-')) pos++;
            if (!digit()) throw err("invalid number: expected digit in exponent");
            digits();
        }
        return new Num(text.substring(s, pos));
    }

    static boolean digit() {
        return pos < text.length() && text.charAt(pos) >= '0' && text.charAt(pos) <= '9';
    }

    static void digits() {
        while (digit()) pos++;
    }

    static String string() throws ParseError {
        pos++; // opening quote
        StringBuilder sb = new StringBuilder();
        while (true) {
            if (pos >= text.length()) throw err("unterminated string");
            char c = text.charAt(pos);
            if (c == '"') { pos++; return sb.toString(); }
            if (c < 0x20) throw err("control character in string");
            if (c == '\\') {
                pos++;
                if (pos >= text.length()) throw err("unterminated string");
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
                        if (pos + 4 >= text.length()) throw err("invalid unicode escape");
                        int cp = 0;
                        for (int k = 1; k <= 4; k++) {
                            int d = Character.digit(text.charAt(pos + k), 16);
                            if (d < 0) { pos += k; throw err("invalid unicode escape"); }
                            cp = cp * 16 + d;
                        }
                        sb.append((char) cp);
                        pos += 4;
                    }
                    default -> throw err("invalid escape");
                }
                pos++;
            } else {
                sb.append(c);
                pos++;
            }
        }
    }

    static List<Object> array() throws ParseError {
        pos++;
        List<Object> l = new ArrayList<>();
        ws();
        if (pos < text.length() && text.charAt(pos) == ']') { pos++; return l; }
        while (true) {
            ws();
            l.add(value());
            ws();
            if (pos >= text.length()) throw err("unexpected end of input");
            char c = text.charAt(pos);
            if (c == ',') { pos++; continue; }
            if (c == ']') { pos++; return l; }
            throw err("expected ',' or ']'");
        }
    }

    static Map<String, Object> object() throws ParseError {
        pos++;
        Map<String, Object> m = new LinkedHashMap<>();
        ws();
        if (pos < text.length() && text.charAt(pos) == '}') { pos++; return m; }
        while (true) {
            ws();
            if (pos >= text.length()) throw err("unexpected end of input");
            if (text.charAt(pos) != '"') throw err("expected string key");
            String k = string();
            ws();
            if (pos >= text.length() || text.charAt(pos) != ':') throw err("expected ':'");
            pos++;
            ws();
            m.put(k, value());
            ws();
            if (pos >= text.length()) throw err("unexpected end of input");
            char c = text.charAt(pos);
            if (c == ',') { pos++; continue; }
            if (c == '}') { pos++; return m; }
            throw err("expected ',' or '}'");
        }
    }
}
