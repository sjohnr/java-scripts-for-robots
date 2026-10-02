import java.io.*;
import java.math.BigDecimal;
import java.nio.ByteBuffer;
import java.nio.charset.*;
import java.nio.file.*;
import java.util.*;

public class Jsonpick {
    static final class ParseError extends Exception {
        ParseError(String m) { super(m); }
    }

    static final class Null { static final Null I = new Null(); }

    // ---- parser ----
    static final class Parser {
        final String s;
        int p = 0;
        Parser(String s) { this.s = s; }

        ParseError err(String msg, int at) {
            int line = 1, col = 1;
            for (int i = 0; i < at && i < s.length(); i++) {
                if (s.charAt(i) == '\n') { line++; col = 1; } else col++;
            }
            return new ParseError(msg + " at line " + line + " col " + col + " (offset " + at + ")");
        }

        void ws() {
            while (p < s.length()) {
                char c = s.charAt(p);
                if (c == ' ' || c == '\t' || c == '\n' || c == '\r') p++; else break;
            }
        }

        Object parseDocument() throws ParseError {
            ws();
            if (p >= s.length()) throw err("unexpected end of input", p);
            Object v = value();
            ws();
            if (p < s.length()) throw err("unexpected trailing character '" + s.charAt(p) + "'", p);
            return v;
        }

        Object value() throws ParseError {
            if (p >= s.length()) throw err("unexpected end of input", p);
            char c = s.charAt(p);
            switch (c) {
                case '{': return object();
                case '[': return array();
                case '"': return string();
                case 't': lit("true"); return Boolean.TRUE;
                case 'f': lit("false"); return Boolean.FALSE;
                case 'n': lit("null"); return Null.I;
                default:
                    if (c == '-' || (c >= '0' && c <= '9')) return number();
                    throw err("unexpected character '" + c + "'", p);
            }
        }

        void lit(String w) throws ParseError {
            if (!s.startsWith(w, p)) throw err("invalid literal", p);
            p += w.length();
        }

        Object number() throws ParseError {
            int st = p;
            if (s.charAt(p) == '-') p++;
            if (p >= s.length()) throw err("invalid number", p);
            if (s.charAt(p) == '0') p++;
            else if (s.charAt(p) >= '1' && s.charAt(p) <= '9') { while (p < s.length() && Character.isDigit(s.charAt(p)) && s.charAt(p) < 128) p++; }
            else throw err("invalid number", p);
            if (p < s.length() && s.charAt(p) == '.') {
                p++;
                int d = p;
                while (p < s.length() && s.charAt(p) >= '0' && s.charAt(p) <= '9') p++;
                if (d == p) throw err("invalid number", p);
            }
            if (p < s.length() && (s.charAt(p) == 'e' || s.charAt(p) == 'E')) {
                p++;
                if (p < s.length() && (s.charAt(p) == '+' || s.charAt(p) == '-')) p++;
                int d = p;
                while (p < s.length() && s.charAt(p) >= '0' && s.charAt(p) <= '9') p++;
                if (d == p) throw err("invalid number", p);
            }
            try {
                return new BigDecimal(s.substring(st, p));
            } catch (NumberFormatException e) {
                throw err("invalid number", st);
            }
        }

        String string() throws ParseError {
            int start = p;
            p++;
            StringBuilder sb = new StringBuilder();
            while (true) {
                if (p >= s.length()) throw err("unterminated string", start);
                char c = s.charAt(p);
                if (c == '"') { p++; return sb.toString(); }
                if (c < 0x20) throw err("control character in string", p);
                if (c == '\\') {
                    p++;
                    if (p >= s.length()) throw err("unterminated string", start);
                    char e = s.charAt(p);
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
                            if (p + 4 >= s.length()) throw err("invalid unicode escape", p - 1);
                            int v = 0;
                            for (int i = 1; i <= 4; i++) {
                                int d = Character.digit(s.charAt(p + i), 16);
                                if (d < 0 || s.charAt(p + i) > 127) throw err("invalid unicode escape", p - 1);
                                v = v * 16 + d;
                            }
                            sb.append((char) v);
                            p += 4;
                            break;
                        }
                        default: throw err("invalid escape", p - 1);
                    }
                    p++;
                } else { sb.append(c); p++; }
            }
        }

        List<Object> array() throws ParseError {
            p++;
            List<Object> l = new ArrayList<>();
            ws();
            if (p < s.length() && s.charAt(p) == ']') { p++; return l; }
            while (true) {
                ws();
                l.add(value());
                ws();
                if (p >= s.length()) throw err("unexpected end of input", p);
                char c = s.charAt(p);
                if (c == ',') { p++; continue; }
                if (c == ']') { p++; return l; }
                throw err("expected ',' or ']'", p);
            }
        }

        Map<String, Object> object() throws ParseError {
            p++;
            Map<String, Object> m = new LinkedHashMap<>();
            ws();
            if (p < s.length() && s.charAt(p) == '}') { p++; return m; }
            while (true) {
                ws();
                if (p >= s.length()) throw err("unexpected end of input", p);
                if (s.charAt(p) != '"') throw err("expected string key", p);
                String k = string();
                ws();
                if (p >= s.length() || s.charAt(p) != ':') throw err("expected ':'", p);
                p++;
                ws();
                m.put(k, value());
                ws();
                if (p >= s.length()) throw err("unexpected end of input", p);
                char c = s.charAt(p);
                if (c == ',') { p++; continue; }
                if (c == '}') { p++; return m; }
                throw err("expected ',' or '}'", p);
            }
        }
    }

    // ---- output ----
    static String num(BigDecimal d) {
        if (d.signum() == 0) return "0";
        d = d.stripTrailingZeros();
        if (d.scale() <= 0) return d.toBigIntegerExact().toString();
        return d.toPlainString();
    }

    static void quote(StringBuilder sb, String str) {
        sb.append('"');
        for (int i = 0; i < str.length(); i++) {
            char c = str.charAt(i);
            switch (c) {
                case '"': sb.append("\\\""); break;
                case '\\': sb.append("\\\\"); break;
                case '\n': sb.append("\\n"); break;
                case '\r': sb.append("\\r"); break;
                case '\t': sb.append("\\t"); break;
                case '\b': sb.append("\\b"); break;
                case '\f': sb.append("\\f"); break;
                default:
                    if (c < 0x20) sb.append(String.format("\\u%04x", (int) c)); else sb.append(c);
            }
        }
        sb.append('"');
    }

    @SuppressWarnings("unchecked")
    static void compact(StringBuilder sb, Object v) {
        if (v instanceof String) quote(sb, (String) v);
        else if (v instanceof BigDecimal) sb.append(num((BigDecimal) v));
        else if (v instanceof Boolean) sb.append(v.toString());
        else if (v instanceof Null) sb.append("null");
        else if (v instanceof List) {
            sb.append('[');
            boolean first = true;
            for (Object o : (List<Object>) v) { if (!first) sb.append(','); first = false; compact(sb, o); }
            sb.append(']');
        } else {
            sb.append('{');
            boolean first = true;
            for (Map.Entry<String, Object> e : ((Map<String, Object>) v).entrySet()) {
                if (!first) sb.append(',');
                first = false;
                quote(sb, e.getKey());
                sb.append(':');
                compact(sb, e.getValue());
            }
            sb.append('}');
        }
    }

    @SuppressWarnings("unchecked")
    static void shape(StringBuilder sb, Object v, int budget) {
        if (v instanceof String) sb.append("string");
        else if (v instanceof BigDecimal) sb.append("number");
        else if (v instanceof Boolean) sb.append("boolean");
        else if (v instanceof Null) sb.append("null");
        else if (v instanceof List) {
            List<Object> l = (List<Object>) v;
            sb.append("array[").append(l.size()).append(']');
            if (budget > 0 && !l.isEmpty()) {
                sb.append(" of ");
                shape(sb, l.get(0), budget - 1);
            }
        } else {
            Map<String, Object> m = (Map<String, Object>) v;
            if (budget <= 0) { sb.append("object{").append(m.size()).append('}'); return; }
            sb.append("object{");
            boolean first = true;
            for (Map.Entry<String, Object> e : m.entrySet()) {
                if (!first) sb.append(", ");
                first = false;
                sb.append(e.getKey()).append(": ");
                shape(sb, e.getValue(), budget - 1);
            }
            sb.append('}');
        }
    }

    // ---- path ----
    /** Returns the resolved value, or null if the path does not resolve. */
    @SuppressWarnings("unchecked")
    static Object resolve(Object root, String path) {
        int i = 0, n = path.length();
        if (n == 0 || path.equals(".")) return root;
        if (path.charAt(0) == '.') i = 1;
        Object cur = root;
        boolean first = true;
        while (i < n) {
            char c = path.charAt(i);
            if (c == '[') {
                int e = path.indexOf(']', i);
                if (e < 0) return null;
                String idx = path.substring(i + 1, e);
                if (idx.isEmpty()) return null;
                for (int k = 0; k < idx.length(); k++) if (idx.charAt(k) < '0' || idx.charAt(k) > '9') return null;
                if (!(cur instanceof List)) return null;
                int ix;
                try { ix = Integer.parseInt(idx); } catch (NumberFormatException ex) { return null; }
                List<Object> l = (List<Object>) cur;
                if (ix >= l.size()) return null;
                cur = l.get(ix);
                i = e + 1;
            } else {
                if (!first) {
                    if (c != '.') return null;
                    i++;
                }
                int e = i;
                while (e < n && path.charAt(e) != '.' && path.charAt(e) != '[') e++;
                if (e == i) return null;
                String key = path.substring(i, e);
                if (!(cur instanceof Map)) return null;
                Map<String, Object> m = (Map<String, Object>) cur;
                if (!m.containsKey(key)) return null;
                cur = m.get(key);
                i = e;
            }
            first = false;
            // after an index, a following key must be introduced by '.', handled via !first above
        }
        return cur;
    }

    static final String HELP =
        "Usage: jsonpick PATH [FILE] [--shape] [--depth N]\n"
        + "\n"
        + "Extract a value by path from JSON.\n"
        + "\n"
        + "Arguments:\n"
        + "  PATH      path such as a.b[0].c ('.' or \"\" selects the whole document)\n"
        + "  FILE      JSON file to read; omitted or '-' means stdin\n"
        + "\n"
        + "Options:\n"
        + "  --shape   print a type/array-length summary of the subtree at PATH\n"
        + "  --depth N levels to describe with --shape (default 2)\n"
        + "  --help    show this message\n";

    static PrintStream out, err;

    static int usage(String msg) {
        err.print(msg + "\n");
        return 2;
    }

    static int run(String[] args) throws IOException {
        String path = null, file = null;
        boolean shape = false, help = false;
        int depth = 2;
        String unknown = null, bad = null;
        for (int i = 0; i < args.length; i++) {
            String a = args[i];
            if (a.equals("--help")) help = true;
            else if (a.equals("--shape")) shape = true;
            else if (a.equals("--depth") || a.startsWith("--depth=")) {
                String v;
                if (a.equals("--depth")) {
                    if (i + 1 >= args.length) { if (bad == null) bad = "error: --depth requires a value"; continue; }
                    v = args[++i];
                } else v = a.substring(8);
                try {
                    depth = Integer.parseInt(v);
                    if (depth < 0) throw new NumberFormatException();
                } catch (NumberFormatException e) {
                    if (bad == null) bad = "error: invalid --depth value: " + v;
                }
            } else if (a.startsWith("-") && !a.equals("-")) {
                if (unknown == null) unknown = a;
            } else if (path == null) path = a;
            else if (file == null) file = a;
            else if (bad == null) bad = "error: unexpected argument: " + a;
        }
        if (unknown != null) return usage("unknown flag: " + unknown);
        if (help) { out.print(HELP); return 0; }
        if (bad != null) return usage(bad);
        if (path == null) return usage("error: missing required argument: PATH");

        byte[] data;
        try {
            if (file == null || file.equals("-")) data = System.in.readAllBytes();
            else data = Files.readAllBytes(Paths.get(file));
        } catch (IOException | InvalidPathException e) {
            return usage("error: cannot read file: " + (file == null ? "-" : file));
        }
        String text;
        try {
            text = StandardCharsets.UTF_8.newDecoder()
                .onMalformedInput(CodingErrorAction.REPORT)
                .onUnmappableCharacter(CodingErrorAction.REPORT)
                .decode(ByteBuffer.wrap(data)).toString();
        } catch (CharacterCodingException e) {
            return usage("error: invalid JSON: invalid UTF-8 input");
        }
        if (text.startsWith("﻿")) text = text.substring(1);

        Object root;
        try {
            root = new Parser(text).parseDocument();
        } catch (ParseError e) {
            return usage("error: invalid JSON: " + e.getMessage());
        }
        Object v = resolve(root, path);
        if (v == null) {
            err.print("error: path not found: " + path + "\n");
            return 1;
        }
        StringBuilder sb = new StringBuilder();
        if (shape) shape(sb, v, depth);
        else if (v instanceof String) sb.append((String) v);
        else compact(sb, v);
        sb.append('\n');
        out.print(sb);
        return 0;
    }

    public static void main(String[] args) throws Exception {
        out = new PrintStream(new FileOutputStream(FileDescriptor.out), false, "UTF-8");
        err = new PrintStream(new FileOutputStream(FileDescriptor.err), false, "UTF-8");
        int[] code = {2};
        Thread t = new Thread(null, () -> {
            try { code[0] = run(args); }
            catch (StackOverflowError e) { err.print("error: invalid JSON: nesting too deep\n"); code[0] = 2; }
            catch (IOException e) { err.print("error: " + e.getMessage() + "\n"); code[0] = 2; }
        }, "main", 1L << 29);
        t.start();
        t.join();
        out.flush();
        err.flush();
        System.exit(code[0]);
    }
}
