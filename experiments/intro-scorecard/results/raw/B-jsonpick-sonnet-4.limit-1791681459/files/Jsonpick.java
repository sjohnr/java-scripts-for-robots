import java.io.*;
import java.math.BigDecimal;
import java.nio.ByteBuffer;
import java.nio.charset.*;
import java.nio.file.*;
import java.util.*;

public class Jsonpick {
    static final class Num {
        final String text;
        Num(String t) { text = t; }
        String canonical() {
            BigDecimal b = new BigDecimal(text);
            if (b.signum() == 0) return text.startsWith("-") ? "-0" : "0";
            return b.stripTrailingZeros().toPlainString();
        }
    }

    static final class Usage extends RuntimeException {
        Usage(String m) { super(m); }
    }

    static final class ParseError extends RuntimeException {
        ParseError(String m) { super(m); }
    }

    static final class Parser {
        final String s;
        int i = 0;
        Parser(String s) { this.s = s; }

        ParseError err(String msg) { return err(msg, i); }

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

        Object parseDocument() {
            ws();
            if (i >= s.length()) throw err("unexpected end of input");
            Object v = value();
            ws();
            if (i < s.length()) throw err("unexpected trailing character '" + s.charAt(i) + "'");
            return v;
        }

        Object value() {
            if (i >= s.length()) throw err("unexpected end of input");
            char c = s.charAt(i);
            switch (c) {
                case '{': return object();
                case '[': return array();
                case '"': return string();
                case 't': lit("true"); return Boolean.TRUE;
                case 'f': lit("false"); return Boolean.FALSE;
                case 'n': lit("null"); return JNULL;
                default:
                    if (c == '-' || (c >= '0' && c <= '9')) return number();
                    throw err("unexpected character '" + c + "'");
            }
        }

        void lit(String w) {
            if (!s.startsWith(w, i)) throw err("invalid literal");
            i += w.length();
        }

        Num number() {
            int st = i;
            if (s.charAt(i) == '-') i++;
            if (i >= s.length()) throw err("invalid number", st);
            char c = s.charAt(i);
            if (c == '0') i++;
            else if (c >= '1' && c <= '9') { while (i < s.length() && isDigit(s.charAt(i))) i++; }
            else throw err("invalid number");
            if (i < s.length() && s.charAt(i) == '.') {
                i++;
                if (i >= s.length() || !isDigit(s.charAt(i))) throw err("invalid number: digit expected after '.'");
                while (i < s.length() && isDigit(s.charAt(i))) i++;
            }
            if (i < s.length() && (s.charAt(i) == 'e' || s.charAt(i) == 'E')) {
                i++;
                if (i < s.length() && (s.charAt(i) == '+' || s.charAt(i) == '-')) i++;
                if (i >= s.length() || !isDigit(s.charAt(i))) throw err("invalid number: digit expected in exponent");
                while (i < s.length() && isDigit(s.charAt(i))) i++;
            }
            return new Num(s.substring(st, i));
        }

        static boolean isDigit(char c) { return c >= '0' && c <= '9'; }

        String string() {
            i++; // opening quote
            StringBuilder sb = new StringBuilder();
            while (true) {
                if (i >= s.length()) throw err("unterminated string");
                char c = s.charAt(i);
                if (c == '"') { i++; return sb.toString(); }
                if (c < 0x20) throw err("unescaped control character in string");
                if (c == '\\') {
                    i++;
                    if (i >= s.length()) throw err("unterminated string");
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
                        case 'u':
                            if (i + 4 >= s.length()) throw err("invalid unicode escape");
                            int v = 0;
                            for (int k = 1; k <= 4; k++) {
                                int d = Character.digit(s.charAt(i + k), 16);
                                if (d < 0) throw err("invalid unicode escape", i + k);
                                v = v * 16 + d;
                            }
                            sb.append((char) v);
                            i += 4;
                            break;
                        default: throw err("invalid escape '\\" + e + "'");
                    }
                    i++;
                } else {
                    sb.append(c);
                    i++;
                }
            }
        }

        List<Object> array() {
            i++;
            List<Object> list = new ArrayList<>();
            ws();
            if (i < s.length() && s.charAt(i) == ']') { i++; return list; }
            while (true) {
                ws();
                list.add(value());
                ws();
                if (i >= s.length()) throw err("unexpected end of input in array");
                char c = s.charAt(i);
                if (c == ',') { i++; continue; }
                if (c == ']') { i++; return list; }
                throw err("expected ',' or ']'");
            }
        }

        Map<String, Object> object() {
            i++;
            Map<String, Object> map = new LinkedHashMap<>();
            ws();
            if (i < s.length() && s.charAt(i) == '}') { i++; return map; }
            while (true) {
                ws();
                if (i >= s.length()) throw err("unexpected end of input in object");
                if (s.charAt(i) != '"') throw err("expected string key");
                String k = string();
                ws();
                if (i >= s.length() || s.charAt(i) != ':') throw err("expected ':'");
                i++;
                ws();
                map.put(k, value());
                ws();
                if (i >= s.length()) throw err("unexpected end of input in object");
                char c = s.charAt(i);
                if (c == ',') { i++; continue; }
                if (c == '}') { i++; return map; }
                throw err("expected ',' or '}'");
            }
        }
    }

    static final Object JNULL = new Object();

    // ---- path ----
    /** Returns null if not found (use JNULL sentinel for JSON null). */
    static Object navigate(Object root, String path) {
        Object cur = root;
        int i = 0, n = path.length();
        if (n > 0 && path.charAt(0) == '.') i = 1;
        boolean first = true;
        while (i < n) {
            char c = path.charAt(i);
            if (c == '[') {
                int close = path.indexOf(']', i);
                if (close < 0) return null;
                String idx = path.substring(i + 1, close);
                if (idx.isEmpty() || !idx.chars().allMatch(ch -> ch >= '0' && ch <= '9')) return null;
                if (!(cur instanceof List)) return null;
                List<?> l = (List<?>) cur;
                int k;
                try { k = Integer.parseInt(idx); } catch (NumberFormatException e) { return null; }
                if (k >= l.size()) return null;
                cur = l.get(k);
                i = close + 1;
                if (i < n && path.charAt(i) != '.' && path.charAt(i) != '[') return null;
            } else {
                if (c == '.') {
                    i++;
                    if (i >= n) return null; // trailing dot
                } else if (!first) {
                    return null;
                }
                int j = i;
                while (j < n && path.charAt(j) != '.' && path.charAt(j) != '[') j++;
                String key = path.substring(i, j);
                if (!(cur instanceof Map)) return null;
                Map<?, ?> m = (Map<?, ?>) cur;
                if (!m.containsKey(key)) return null;
                cur = m.get(key);
                i = j;
            }
            first = false;
        }
        return cur;
    }

    // ---- output ----
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

    static void compact(StringBuilder sb, Object v) {
        if (v instanceof String) quote(sb, (String) v);
        else if (v instanceof Num) sb.append(((Num) v).canonical());
        else if (v instanceof Boolean) sb.append(v.toString());
        else if (v == JNULL) sb.append("null");
        else if (v instanceof List) {
            sb.append('[');
            boolean f = true;
            for (Object o : (List<?>) v) { if (!f) sb.append(','); f = false; compact(sb, o); }
            sb.append(']');
        } else {
            sb.append('{');
            boolean f = true;
            for (Map.Entry<?, ?> e : ((Map<?, ?>) v).entrySet()) {
                if (!f) sb.append(','); f = false;
                quote(sb, (String) e.getKey());
                sb.append(':');
                compact(sb, e.getValue());
            }
            sb.append('}');
        }
    }

    static void shape(StringBuilder sb, Object v, int budget) {
        if (v instanceof String) sb.append("string");
        else if (v instanceof Num) sb.append("number");
        else if (v instanceof Boolean) sb.append("boolean");
        else if (v == JNULL) sb.append("null");
        else if (v instanceof List) {
            List<?> l = (List<?>) v;
            sb.append("array[").append(l.size()).append(']');
            if (budget > 0 && !l.isEmpty()) {
                sb.append(" of ");
                shape(sb, l.get(0), budget - 1);
            }
        } else {
            Map<?, ?> m = (Map<?, ?>) v;
            if (budget == 0) {
                sb.append("object{").append(m.size()).append('}');
            } else {
                sb.append("object{");
                boolean f = true;
                for (Map.Entry<?, ?> e : m.entrySet()) {
                    if (!f) sb.append(", "); f = false;
                    sb.append((String) e.getKey()).append(": ");
                    shape(sb, e.getValue(), budget - 1);
                }
                sb.append('}');
            }
        }
    }

    static final String HELP = String.join("\n",
        "Usage: jsonpick PATH [FILE] [--shape] [--depth N]",
        "",
        "Extract a value by path from JSON.",
        "",
        "Arguments:",
        "  PATH       Path such as a.b[0].c ('.' or \"\" selects the whole document)",
        "  FILE       JSON file to read; omitted or '-' means stdin",
        "",
        "Options:",
        "  --shape    Print a depth-limited type/array-length summary instead of the value",
        "  --depth N  Levels to describe with --shape (default 2)",
        "  --help     Show this message and exit",
        "");

    static int run(String[] args, PrintStream out, PrintStream err) throws IOException {
        String path = null, file = null;
        boolean shape = false, help = false;
        int depth = 2;
        try {
            for (int a = 0; a < args.length; a++) {
                String arg = args[a];
                if (arg.equals("--help")) help = true;
                else if (arg.equals("--shape")) shape = true;
                else if (arg.equals("--depth") || arg.startsWith("--depth=")) {
                    String val;
                    if (arg.equals("--depth")) {
                        if (a + 1 >= args.length) throw new Usage("--depth requires a value");
                        val = args[++a];
                    } else val = arg.substring(8);
                    try {
                        depth = Integer.parseInt(val);
                    } catch (NumberFormatException e) {
                        throw new Usage("invalid --depth value: " + val);
                    }
                    if (depth < 0) throw new Usage("invalid --depth value: " + val);
                } else if (arg.startsWith("-") && arg.length() > 1) {
                    throw new Usage("unknown flag: " + arg);
                } else if (path == null) path = arg;
                else if (file == null) file = arg;
                else throw new Usage("unexpected argument: " + arg);
            }
            if (help) { out.print(HELP); return 0; }
            if (path == null) throw new Usage("missing required argument: PATH");
        } catch (Usage u) {
            if (help && u.getMessage().startsWith("missing")) { out.print(HELP); return 0; }
            err.print(u.getMessage().startsWith("unknown flag") ? u.getMessage() + "\n" : "error: " + u.getMessage() + "\n");
            return 2;
        }

        byte[] data;
        try {
            if (file == null || file.equals("-")) data = System.in.readAllBytes();
            else data = Files.readAllBytes(Paths.get(file));
        } catch (IOException | InvalidPathException e) {
            err.print("error: cannot read file: " + file + "\n");
            return 2;
        }
        String text;
        try {
            text = StandardCharsets.UTF_8.newDecoder()
                .onMalformedInput(CodingErrorAction.REPORT)
                .onUnmappableCharacter(CodingErrorAction.REPORT)
                .decode(ByteBuffer.wrap(data)).toString();
        } catch (CharacterCodingException e) {
            err.print("error: invalid JSON: input is not valid UTF-8\n");
            return 2;
        }
        if (text.startsWith("﻿")) text = text.substring(1);

        Object root;
        try {
            root = new Parser(text).parseDocument();
        } catch (ParseError e) {
            err.print("error: invalid JSON: " + e.getMessage() + "\n");
            return 2;
        }

        Object found = path.equals("") || path.equals(".") ? root : navigate(root, path);
        if (found == null) {
            err.print("error: path not found: " + path + "\n");
            return 1;
        }
        StringBuilder sb = new StringBuilder();
        if (shape) shape(sb, found, depth);
        else if (found instanceof String) sb.append((String) found);
        else compact(sb, found);
        sb.append('\n');
        out.print(sb);
        return 0;
    }

    public static void main(String[] args) throws Exception {
        PrintStream out = new PrintStream(new FileOutputStream(FileDescriptor.out), false, StandardCharsets.UTF_8);
        PrintStream err = new PrintStream(new FileOutputStream(FileDescriptor.err), false, StandardCharsets.UTF_8);
        int[] code = {0};
        Throwable[] fail = {null};
        Thread t = new Thread(null, () -> {
            try { code[0] = run(args, out, err); } catch (Throwable e) { fail[0] = e; }
        }, "main", 1L << 29);
        t.start();
        t.join();
        out.flush();
        err.flush();
        if (fail[0] instanceof StackOverflowError) {
            System.err.print("error: invalid JSON: nesting too deep\n");
            System.exit(2);
        } else if (fail[0] != null) {
            System.err.print("error: " + fail[0] + "\n");
            System.exit(2);
        }
        System.exit(code[0]);
    }
}
