import java.io.*;
import java.math.BigDecimal;
import java.nio.ByteBuffer;
import java.nio.charset.*;
import java.nio.file.*;
import java.util.*;

public class Jsonpick {
    static final class Num {
        final String lit;
        Num(String l) { lit = l; }
    }

    static final class ParseError extends Exception {
        ParseError(String m) { super(m); }
    }

    static final class Parser {
        final String s;
        int i = 0;
        Parser(String s) { this.s = s; }

        ParseError err(String msg, int pos) {
            int line = 1, col = 1;
            for (int k = 0; k < pos && k < s.length(); k++) {
                if (s.charAt(k) == '\n') { line++; col = 1; } else col++;
            }
            return new ParseError(msg + " at line " + line + " col " + col);
        }

        void ws() {
            while (i < s.length()) {
                char c = s.charAt(i);
                if (c == ' ' || c == '\t' || c == '\n' || c == '\r') i++; else break;
            }
        }

        Object parseDocument() throws ParseError {
            ws();
            Object v = value();
            ws();
            if (i < s.length()) throw err("unexpected trailing content", i);
            return v;
        }

        Object value() throws ParseError {
            if (i >= s.length()) throw err("unexpected end of input", i);
            char c = s.charAt(i);
            switch (c) {
                case '{': return object();
                case '[': return array();
                case '"': return string();
                case 't': lit("true"); return Boolean.TRUE;
                case 'f': lit("false"); return Boolean.FALSE;
                case 'n': lit("null"); return null;
                default:
                    if (c == '-' || (c >= '0' && c <= '9')) return number();
                    throw err("unexpected character '" + c + "'", i);
            }
        }

        void lit(String w) throws ParseError {
            if (!s.startsWith(w, i)) throw err("invalid literal", i);
            i += w.length();
        }

        Object number() throws ParseError {
            int st = i;
            if (s.charAt(i) == '-') i++;
            if (i >= s.length()) throw err("invalid number", i);
            if (s.charAt(i) == '0') i++;
            else if (s.charAt(i) >= '1' && s.charAt(i) <= '9') { while (digit()) i++; }
            else throw err("invalid number", i);
            if (i < s.length() && s.charAt(i) == '.') {
                i++;
                if (!digit()) throw err("invalid number", i);
                while (digit()) i++;
            }
            if (i < s.length() && (s.charAt(i) == 'e' || s.charAt(i) == 'E')) {
                i++;
                if (i < s.length() && (s.charAt(i) == '+' || s.charAt(i) == '-')) i++;
                if (!digit()) throw err("invalid number", i);
                while (digit()) i++;
            }
            return new Num(s.substring(st, i));
        }

        boolean digit() { return i < s.length() && s.charAt(i) >= '0' && s.charAt(i) <= '9'; }

        String string() throws ParseError {
            int start = i;
            i++;
            StringBuilder sb = new StringBuilder();
            while (true) {
                if (i >= s.length()) throw err("unterminated string", start);
                char c = s.charAt(i);
                if (c == '"') { i++; return sb.toString(); }
                if (c < 0x20) throw err("control character in string", i);
                if (c == '\\') {
                    i++;
                    if (i >= s.length()) throw err("unterminated string", start);
                    char e = s.charAt(i);
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
                            if (i + 4 >= s.length()) throw err("invalid unicode escape", i);
                            int v = 0;
                            for (int k = 1; k <= 4; k++) {
                                int d = Character.digit(s.charAt(i + k), 16);
                                if (d < 0 || s.charAt(i + k) > 'f') throw err("invalid unicode escape", i);
                                v = v * 16 + d;
                            }
                            sb.append((char) v);
                            i += 4;
                            break;
                        }
                        default: throw err("invalid escape", i);
                    }
                    i++;
                } else {
                    sb.append(c);
                    i++;
                }
            }
        }

        Object array() throws ParseError {
            i++;
            List<Object> list = new ArrayList<>();
            ws();
            if (i < s.length() && s.charAt(i) == ']') { i++; return list; }
            while (true) {
                ws();
                list.add(value());
                ws();
                if (i >= s.length()) throw err("unexpected end of input", i);
                char c = s.charAt(i);
                if (c == ',') { i++; continue; }
                if (c == ']') { i++; return list; }
                throw err("expected ',' or ']'", i);
            }
        }

        Object object() throws ParseError {
            i++;
            Map<String, Object> map = new LinkedHashMap<>();
            ws();
            if (i < s.length() && s.charAt(i) == '}') { i++; return map; }
            while (true) {
                ws();
                if (i >= s.length()) throw err("unexpected end of input", i);
                if (s.charAt(i) != '"') throw err("expected string key", i);
                String k = string();
                ws();
                if (i >= s.length() || s.charAt(i) != ':') throw err("expected ':'", i);
                i++;
                ws();
                map.put(k, value());
                ws();
                if (i >= s.length()) throw err("unexpected end of input", i);
                char c = s.charAt(i);
                if (c == ',') { i++; continue; }
                if (c == '}') { i++; return map; }
                throw err("expected ',' or '}'", i);
            }
        }
    }

    static String num(Num n) {
        String l = n.lit;
        try {
            BigDecimal bd = new BigDecimal(l);
            if (bd.signum() == 0) return l.startsWith("-") ? "-0" : "0";
            bd = bd.stripTrailingZeros();
            long mag = (long) bd.precision() - bd.scale();
            if (Math.abs(mag) > 1000 || Math.abs((long) bd.scale()) > 1000) return l;
            return bd.toPlainString();
        } catch (NumberFormatException e) {
            return l;
        }
    }

    static void quote(StringBuilder sb, String s) {
        sb.append('"');
        for (int k = 0; k < s.length(); k++) {
            char c = s.charAt(k);
            switch (c) {
                case '"': sb.append("\\\""); break;
                case '\\': sb.append("\\\\"); break;
                case '\n': sb.append("\\n"); break;
                case '\r': sb.append("\\r"); break;
                case '\t': sb.append("\\t"); break;
                case '\b': sb.append("\\b"); break;
                case '\f': sb.append("\\f"); break;
                default:
                    if (c < 0x20) sb.append(String.format("\\u%04x", (int) c));
                    else sb.append(c);
            }
        }
        sb.append('"');
    }

    @SuppressWarnings("unchecked")
    static void compact(StringBuilder sb, Object v) {
        if (v == null) sb.append("null");
        else if (v instanceof Boolean || v instanceof Num) sb.append(v instanceof Num ? num((Num) v) : v.toString());
        else if (v instanceof String) quote(sb, (String) v);
        else if (v instanceof List) {
            sb.append('[');
            boolean first = true;
            for (Object o : (List<Object>) v) {
                if (!first) sb.append(',');
                first = false;
                compact(sb, o);
            }
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
    static String shape(Object v, int budget) {
        if (v == null) return "null";
        if (v instanceof Boolean) return "boolean";
        if (v instanceof Num) return "number";
        if (v instanceof String) return "string";
        if (v instanceof List) {
            List<Object> l = (List<Object>) v;
            String h = "array[" + l.size() + "]";
            if (budget <= 0 || l.isEmpty()) return h;
            return h + " of " + shape(l.get(0), budget - 1);
        }
        Map<String, Object> m = (Map<String, Object>) v;
        if (budget <= 0) return "object{" + m.size() + "}";
        if (m.isEmpty()) return "object{}";
        StringBuilder sb = new StringBuilder("object{");
        boolean first = true;
        for (Map.Entry<String, Object> e : m.entrySet()) {
            if (!first) sb.append(", ");
            first = false;
            sb.append(e.getKey()).append(": ").append(shape(e.getValue(), budget - 1));
        }
        return sb.append('}').toString();
    }

    // Returns list of String (key) / Integer (index) segments, or null if malformed.
    static List<Object> parsePath(String p) {
        List<Object> segs = new ArrayList<>();
        if (p.isEmpty() || p.equals(".")) return segs;
        int i = 0, n = p.length();
        if (p.charAt(0) == '.') i = 1;
        boolean needKey = i == 1 || false;
        boolean expectSep = false;
        while (i < n) {
            char c = p.charAt(i);
            if (c == '[') {
                int j = p.indexOf(']', i);
                if (j < 0) return null;
                String d = p.substring(i + 1, j);
                if (d.isEmpty() || d.length() > 9) return null;
                for (int k = 0; k < d.length(); k++) if (d.charAt(k) < '0' || d.charAt(k) > '9') return null;
                segs.add(Integer.parseInt(d));
                i = j + 1;
                needKey = false;
                expectSep = true;
            } else if (c == '.') {
                if (!expectSep) return null;
                i++;
                if (i >= n) return null;
                if (p.charAt(i) == '[' || p.charAt(i) == '.') return null;
                expectSep = false;
                needKey = true;
            } else {
                if (expectSep) return null;
                int j = i;
                while (j < n && p.charAt(j) != '.' && p.charAt(j) != '[') j++;
                segs.add(p.substring(i, j));
                i = j;
                expectSep = true;
                needKey = false;
            }
        }
        if (needKey) return null;
        return segs;
    }

    static final Object MISSING = new Object();

    @SuppressWarnings("unchecked")
    static Object navigate(Object root, List<Object> segs) {
        Object cur = root;
        for (Object sg : segs) {
            if (sg instanceof String) {
                if (!(cur instanceof Map)) return MISSING;
                Map<String, Object> m = (Map<String, Object>) cur;
                if (!m.containsKey(sg)) return MISSING;
                cur = m.get(sg);
            } else {
                if (!(cur instanceof List)) return MISSING;
                List<Object> l = (List<Object>) cur;
                int idx = (Integer) sg;
                if (idx >= l.size()) return MISSING;
                cur = l.get(idx);
            }
        }
        return cur;
    }

    static PrintStream out;
    static PrintStream err;

    static int usage(String msg) {
        err.print(msg + "\n");
        err.flush();
        return 2;
    }

    static final String HELP =
        "Usage: jsonpick PATH [FILE] [--shape] [--depth N]\n"
        + "\n"
        + "Extract a value by path from JSON.\n"
        + "\n"
        + "Arguments:\n"
        + "  PATH      path such as a.b[0].c ('.' or '' selects the whole document)\n"
        + "  FILE      JSON file to read; omitted or '-' means stdin\n"
        + "\n"
        + "Options:\n"
        + "  --shape   print a depth-limited type summary of the subtree at PATH\n"
        + "  --depth N levels to describe with --shape (default 2)\n"
        + "  --help    show this message\n";

    static int run(String[] args) throws Exception {
        String path = null, file = null;
        boolean shape = false, noMoreFlags = false;
        int depth = 2;
        for (int k = 0; k < args.length; k++) {
            String a = args[k];
            if (!noMoreFlags && a.equals("--")) { noMoreFlags = true; continue; }
            if (!noMoreFlags && a.startsWith("-") && !a.equals("-")) {
                if (a.equals("--help")) { out.print(HELP); out.flush(); return 0; }
                if (a.equals("--shape")) { shape = true; continue; }
                if (a.equals("--depth") || a.startsWith("--depth=")) {
                    String val;
                    if (a.equals("--depth")) {
                        if (k + 1 >= args.length) return usage("error: --depth requires a value");
                        val = args[++k];
                    } else val = a.substring(8);
                    try {
                        depth = Integer.parseInt(val);
                    } catch (NumberFormatException e) {
                        return usage("error: invalid --depth value: " + val);
                    }
                    if (depth < 0) return usage("error: invalid --depth value: " + val);
                    continue;
                }
                return usage("unknown flag: " + a);
            }
            if (path == null) path = a;
            else if (file == null) file = a;
            else return usage("error: unexpected argument: " + a);
        }
        if (path == null) return usage("error: missing required argument: PATH");

        byte[] data;
        try {
            if (file == null || file.equals("-")) data = System.in.readAllBytes();
            else data = Files.readAllBytes(Paths.get(file));
        } catch (IOException e) {
            return usage("error: cannot read " + (file == null ? "stdin" : file));
        }
        String text;
        try {
            text = StandardCharsets.UTF_8.newDecoder()
                .onMalformedInput(CodingErrorAction.REPORT)
                .onUnmappableCharacter(CodingErrorAction.REPORT)
                .decode(ByteBuffer.wrap(data)).toString();
        } catch (CharacterCodingException e) {
            int off = 0;
            // locate first invalid byte offset
            CharsetDecoder d = StandardCharsets.UTF_8.newDecoder()
                .onMalformedInput(CodingErrorAction.REPORT);
            ByteBuffer bb = ByteBuffer.wrap(data);
            java.nio.CharBuffer cb = java.nio.CharBuffer.allocate(data.length + 1);
            d.decode(bb, cb, true);
            off = bb.position();
            return usage("error: invalid JSON: invalid UTF-8 at byte offset " + off);
        }
        if (text.startsWith("﻿")) text = text.substring(1);

        Object root;
        try {
            root = new Parser(text).parseDocument();
        } catch (ParseError e) {
            return usage("error: invalid JSON: " + e.getMessage());
        }

        List<Object> segs = parsePath(path);
        Object v = segs == null ? MISSING : navigate(root, segs);
        if (v == MISSING) {
            err.print("error: path not found: " + path + "\n");
            err.flush();
            return 1;
        }
        if (shape) {
            out.print(shape(v, depth) + "\n");
        } else if (v instanceof String) {
            out.print((String) v + "\n");
        } else {
            StringBuilder sb = new StringBuilder();
            compact(sb, v);
            out.print(sb.append('\n'));
        }
        out.flush();
        return 0;
    }

    public static void main(String[] args) throws Exception {
        out = new PrintStream(new FileOutputStream(FileDescriptor.out), false, "UTF-8");
        err = new PrintStream(new FileOutputStream(FileDescriptor.err), false, "UTF-8");
        int[] code = {2};
        Thread t = new Thread(null, () -> {
            try { code[0] = run(args); } catch (Throwable e) { code[0] = usage("error: " + e); }
        }, "main2", 1L << 29);
        t.start();
        t.join();
        out.flush();
        err.flush();
        System.exit(code[0]);
    }
}
