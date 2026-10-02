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
    static final Object NULL = new Object();

    static final class Num {
        final String raw;
        Num(String raw) { this.raw = raw; }
    }

    static final class ParseError extends Exception {
        ParseError(String m) { super(m); }
    }

    static final class Usage extends Exception {
        Usage(String m) { super(m); }
    }

    static final String HELP = String.join("\n",
        "Usage: jsonpick PATH [FILE] [--shape] [--depth N]",
        "",
        "Extract a value by path from JSON.",
        "",
        "Arguments:",
        "  PATH      Path such as a.b[0].c ('.' or '' selects the whole document)",
        "  FILE      JSON file to read; omitted or '-' means stdin",
        "",
        "Options:",
        "  --shape   Print a depth-limited type/length summary of the value at PATH",
        "  --depth N Levels to describe in --shape mode (default 2)",
        "  --help    Show this message",
        "");

    // ---------- parser ----------
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
            if (i < s.length()) throw err("unexpected trailing data", i);
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
                case 'n': lit("null"); return NULL;
                default:
                    if (c == '-' || (c >= '0' && c <= '9')) return number();
                    throw err("unexpected character '" + printable(c) + "'", i);
            }
        }

        static String printable(char c) {
            return c < 0x20 ? String.format("\\u%04x", (int) c) : String.valueOf(c);
        }

        void lit(String w) throws ParseError {
            if (!s.startsWith(w, i)) throw err("invalid literal", i);
            i += w.length();
        }

        Object number() throws ParseError {
            int st = i;
            if (s.charAt(i) == '-') i++;
            if (i >= s.length()) throw err("invalid number", i);
            char c = s.charAt(i);
            if (c == '0') {
                i++;
            } else if (c >= '1' && c <= '9') {
                while (i < s.length() && isDigit(s.charAt(i))) i++;
            } else throw err("invalid number", i);
            if (i < s.length() && s.charAt(i) == '.') {
                i++;
                if (i >= s.length() || !isDigit(s.charAt(i))) throw err("invalid number", i);
                while (i < s.length() && isDigit(s.charAt(i))) i++;
            }
            if (i < s.length() && (s.charAt(i) == 'e' || s.charAt(i) == 'E')) {
                i++;
                if (i < s.length() && (s.charAt(i) == '+' || s.charAt(i) == '-')) i++;
                if (i >= s.length() || !isDigit(s.charAt(i))) throw err("invalid number", i);
                while (i < s.length() && isDigit(s.charAt(i))) i++;
            }
            return new Num(s.substring(st, i));
        }

        static boolean isDigit(char c) { return c >= '0' && c <= '9'; }

        String string() throws ParseError {
            i++; // opening quote
            StringBuilder sb = new StringBuilder();
            while (true) {
                if (i >= s.length()) throw err("unterminated string", i);
                char c = s.charAt(i);
                if (c == '"') { i++; return sb.toString(); }
                if (c < 0x20) throw err("control character in string", i);
                if (c == '\\') {
                    int esc = i;
                    i++;
                    if (i >= s.length()) throw err("unterminated string", i);
                    char e = s.charAt(i++);
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
                            if (i + 4 > s.length()) throw err("invalid unicode escape", esc);
                            int cp = 0;
                            for (int k = 0; k < 4; k++) {
                                int d = Character.digit(s.charAt(i + k), 16);
                                if (d < 0 || s.charAt(i + k) > 'f') throw err("invalid unicode escape", esc);
                                cp = cp * 16 + d;
                            }
                            i += 4;
                            sb.append((char) cp);
                            break;
                        default: throw err("invalid escape", esc);
                    }
                } else {
                    sb.append(c);
                    i++;
                }
            }
        }

        List<Object> array() throws ParseError {
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

        Map<String, Object> object() throws ParseError {
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

    // ---------- path ----------
    /** Returns list of String (key) / Integer (index), or null if the path is malformed. */
    static List<Object> parsePath(String p) {
        List<Object> out = new ArrayList<>();
        if (p.isEmpty() || p.equals(".")) return out;
        int i = 0, n = p.length();
        boolean first = true;
        if (p.charAt(0) == '.') i = 1;
        while (i < n) {
            char c = p.charAt(i);
            if (c == '[') {
                int j = p.indexOf(']', i);
                if (j < 0) return null;
                String num = p.substring(i + 1, j);
                if (num.isEmpty()) return null;
                for (int k = 0; k < num.length(); k++) {
                    if (!Parser.isDigit(num.charAt(k))) return null;
                }
                try { out.add(Integer.parseInt(num)); } catch (NumberFormatException e) { out.add(Integer.MAX_VALUE); }
                i = j + 1;
            } else {
                if (c == '.') {
                    if (first) return null;
                    i++;
                    if (i >= n || p.charAt(i) == '.' || p.charAt(i) == '[') return null;
                }
                int j = i;
                while (j < n && p.charAt(j) != '.' && p.charAt(j) != '[') j++;
                out.add(p.substring(i, j));
                i = j;
            }
            first = false;
        }
        return out;
    }

    @SuppressWarnings("unchecked")
    static Object navigate(Object root, List<Object> steps) {
        Object cur = root;
        for (Object st : steps) {
            if (st instanceof Integer) {
                if (!(cur instanceof List)) return null;
                List<Object> l = (List<Object>) cur;
                int idx = (Integer) st;
                if (idx >= l.size()) return null;
                cur = l.get(idx);
            } else {
                if (!(cur instanceof Map)) return null;
                Map<String, Object> m = (Map<String, Object>) cur;
                if (!m.containsKey(st)) return null;
                cur = m.get(st);
            }
        }
        return cur;
    }

    // ---------- output ----------
    static String number(String raw) {
        try {
            BigDecimal bd = new BigDecimal(raw);
            if (bd.signum() == 0) return raw.startsWith("-") ? "-0" : "0";
            bd = bd.stripTrailingZeros();
            int exp = bd.precision() - bd.scale();
            if (exp <= 100 && exp >= -100) {
                return bd.toPlainString();
            }
            return bd.toString();
        } catch (NumberFormatException e) {
            return raw;
        }
    }

    static void quote(StringBuilder sb, String s) {
        sb.append('"');
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            switch (c) {
                case '"': sb.append("\\\""); break;
                case '\\': sb.append("\\\\"); break;
                case '\b': sb.append("\\b"); break;
                case '\f': sb.append("\\f"); break;
                case '\n': sb.append("\\n"); break;
                case '\r': sb.append("\\r"); break;
                case '\t': sb.append("\\t"); break;
                default:
                    if (c < 0x20) sb.append(String.format("\\u%04x", (int) c));
                    else sb.append(c);
            }
        }
        sb.append('"');
    }

    @SuppressWarnings("unchecked")
    static void compact(StringBuilder sb, Object v) {
        if (v == NULL) sb.append("null");
        else if (v instanceof Boolean) sb.append(v.toString());
        else if (v instanceof Num) sb.append(number(((Num) v).raw));
        else if (v instanceof String) quote(sb, (String) v);
        else if (v instanceof List) {
            sb.append('[');
            boolean f = true;
            for (Object e : (List<Object>) v) {
                if (!f) sb.append(',');
                f = false;
                compact(sb, e);
            }
            sb.append(']');
        } else {
            sb.append('{');
            boolean f = true;
            for (Map.Entry<String, Object> e : ((Map<String, Object>) v).entrySet()) {
                if (!f) sb.append(',');
                f = false;
                quote(sb, e.getKey());
                sb.append(':');
                compact(sb, e.getValue());
            }
            sb.append('}');
        }
    }

    @SuppressWarnings("unchecked")
    static void shape(StringBuilder sb, Object v, long budget) {
        if (v == NULL) sb.append("null");
        else if (v instanceof Boolean) sb.append("boolean");
        else if (v instanceof Num) sb.append("number");
        else if (v instanceof String) sb.append("string");
        else if (v instanceof List) {
            List<Object> l = (List<Object>) v;
            sb.append("array[").append(l.size()).append(']');
            if (budget > 0 && !l.isEmpty()) {
                sb.append(" of ");
                shape(sb, l.get(0), budget - 1);
            }
        } else {
            Map<String, Object> m = (Map<String, Object>) v;
            if (budget <= 0) {
                sb.append("object{").append(m.size()).append('}');
            } else {
                sb.append("object{");
                boolean f = true;
                for (Map.Entry<String, Object> e : m.entrySet()) {
                    if (!f) sb.append(", ");
                    f = false;
                    sb.append(e.getKey()).append(": ");
                    shape(sb, e.getValue(), budget - 1);
                }
                sb.append('}');
            }
        }
    }

    // ---------- main ----------
    static void emit(PrintStream ps, String s) {
        byte[] b = s.getBytes(StandardCharsets.UTF_8);
        ps.write(b, 0, b.length);
        ps.flush();
    }

    static int run(String[] args, InputStream stdin, PrintStream out, PrintStream err) {
        try {
            for (String a : args) {
                if (a.equals("--help")) { emit(out, HELP); return 0; }
                if (a.equals("--")) break;
            }
            String path = null, file = null;
            boolean shape = false, rest = false;
            long depth = 2;
            for (int k = 0; k < args.length; k++) {
                String a = args[k];
                if (!rest && a.equals("--")) { rest = true; continue; }
                if (!rest && a.startsWith("-") && !a.equals("-")) {
                    if (a.equals("--shape")) { shape = true; continue; }
                    String val = null;
                    if (a.equals("--depth")) {
                        if (k + 1 >= args.length) throw new Usage("--depth requires a value");
                        val = args[++k];
                    } else if (a.startsWith("--depth=")) {
                        val = a.substring(8);
                    } else {
                        throw new Usage("unknown flag: " + a);
                    }
                    try {
                        depth = Long.parseLong(val);
                    } catch (NumberFormatException e) {
                        throw new Usage("invalid value for --depth: " + val);
                    }
                    if (depth < 0) throw new Usage("invalid value for --depth: " + val);
                    continue;
                }
                if (path == null) path = a;
                else if (file == null) file = a;
                else throw new Usage("unexpected argument: " + a);
            }
            if (path == null) throw new Usage("missing required argument: PATH");

            byte[] data;
            try {
                if (file == null || file.equals("-")) data = stdin.readAllBytes();
                else data = Files.readAllBytes(Paths.get(file));
            } catch (IOException | RuntimeException e) {
                throw new Usage("cannot read " + (file == null ? "stdin" : file));
            }
            String text;
            try {
                text = StandardCharsets.UTF_8.newDecoder()
                    .onMalformedInput(CodingErrorAction.REPORT)
                    .onUnmappableCharacter(CodingErrorAction.REPORT)
                    .decode(ByteBuffer.wrap(data)).toString();
            } catch (CharacterCodingException e) {
                emit(err, "error: invalid JSON: invalid UTF-8 at byte offset " + utf8ErrorOffset(data) + "\n");
                return 2;
            }
            Object root;
            try {
                root = new Parser(text).parseDocument();
            } catch (ParseError e) {
                emit(err, "error: invalid JSON: " + e.getMessage() + "\n");
                return 2;
            } catch (StackOverflowError e) {
                emit(err, "error: invalid JSON: nesting too deep\n");
                return 2;
            }

            List<Object> steps = parsePath(path);
            Object target = steps == null ? null : navigate(root, steps);
            if (target == null) {
                emit(err, "error: path not found: " + path + "\n");
                return 1;
            }
            StringBuilder sb = new StringBuilder();
            if (shape) shape(sb, target, depth);
            else if (target instanceof String) sb.append((String) target);
            else compact(sb, target);
            sb.append('\n');
            emit(out, sb.toString());
            return 0;
        } catch (Usage u) {
            emit(err, (u.getMessage().startsWith("unknown flag:") ? "" : "error: ") + u.getMessage() + "\n");
            return 2;
        }
    }

    static int utf8ErrorOffset(byte[] data) {
        var dec = StandardCharsets.UTF_8.newDecoder()
            .onMalformedInput(CodingErrorAction.REPORT).onUnmappableCharacter(CodingErrorAction.REPORT);
        ByteBuffer in = ByteBuffer.wrap(data);
        java.nio.CharBuffer cb = java.nio.CharBuffer.allocate(data.length + 1);
        dec.decode(in, cb, true);
        return in.position();
    }

    public static void main(String[] args) throws Exception {
        int[] code = {2};
        Thread t = new Thread(null, () -> {
            code[0] = run(args, System.in, System.out, System.err);
        }, "main", 1L << 29);
        t.start();
        t.join();
        System.out.flush();
        System.exit(code[0]);
    }
}
