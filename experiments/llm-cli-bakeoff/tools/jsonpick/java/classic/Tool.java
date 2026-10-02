import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

public class Tool {

    // ---- minimal hand-rolled JSON parser (stdlib only) ----
    static final class JsonParser {
        final String s;
        int pos = 0;
        JsonParser(String s) { this.s = s; }

        Object parse() {
            skipWs();
            Object v = parseValue();
            skipWs();
            if (pos != s.length()) throw err("trailing content");
            return v;
        }

        RuntimeException err(String msg) {
            int line = 1, col = 1;
            for (int i = 0; i < pos && i < s.length(); i++) {
                if (s.charAt(i) == '\n') { line++; col = 1; } else col++;
            }
            return new RuntimeException(msg + " at line " + line + " col " + col);
        }

        void skipWs() { while (pos < s.length() && Character.isWhitespace(s.charAt(pos))) pos++; }

        Object parseValue() {
            if (pos >= s.length()) throw err("unexpected end of input");
            char c = s.charAt(pos);
            return switch (c) {
                case '{' -> parseObject();
                case '[' -> parseArray();
                case '"' -> parseString();
                case 't' -> { expect("true"); yield Boolean.TRUE; }
                case 'f' -> { expect("false"); yield Boolean.FALSE; }
                case 'n' -> { expect("null"); yield null; }
                default -> parseNumber();
            };
        }

        void expect(String lit) {
            if (!s.regionMatches(pos, lit, 0, lit.length())) throw err("expected " + lit);
            pos += lit.length();
        }

        Map<String, Object> parseObject() {
            Map<String, Object> m = new LinkedHashMap<>();
            pos++; // {
            skipWs();
            if (pos < s.length() && s.charAt(pos) == '}') { pos++; return m; }
            while (true) {
                skipWs();
                if (pos >= s.length() || s.charAt(pos) != '"') throw err("expected string key");
                String key = parseString();
                skipWs();
                if (pos >= s.length() || s.charAt(pos) != ':') throw err("expected ':'");
                pos++;
                skipWs();
                Object val = parseValue();
                m.put(key, val);
                skipWs();
                if (pos >= s.length()) throw err("unterminated object");
                char c = s.charAt(pos);
                if (c == ',') { pos++; continue; }
                if (c == '}') { pos++; break; }
                throw err("expected ',' or '}'");
            }
            return m;
        }

        List<Object> parseArray() {
            List<Object> list = new ArrayList<>();
            pos++; // [
            skipWs();
            if (pos < s.length() && s.charAt(pos) == ']') { pos++; return list; }
            while (true) {
                skipWs();
                list.add(parseValue());
                skipWs();
                if (pos >= s.length()) throw err("unterminated array");
                char c = s.charAt(pos);
                if (c == ',') { pos++; continue; }
                if (c == ']') { pos++; break; }
                throw err("expected ',' or ']'");
            }
            return list;
        }

        String parseString() {
            StringBuilder sb = new StringBuilder();
            pos++; // opening quote
            while (true) {
                if (pos >= s.length()) throw err("unterminated string");
                char c = s.charAt(pos++);
                if (c == '"') break;
                if (c == '\\') {
                    char e = s.charAt(pos++);
                    switch (e) {
                        case '"' -> sb.append('"');
                        case '\\' -> sb.append('\\');
                        case '/' -> sb.append('/');
                        case 'b' -> sb.append('\b');
                        case 'f' -> sb.append('\f');
                        case 'n' -> sb.append('\n');
                        case 'r' -> sb.append('\r');
                        case 't' -> sb.append('\t');
                        case 'u' -> { sb.append((char) Integer.parseInt(s.substring(pos, pos + 4), 16)); pos += 4; }
                        default -> throw err("bad escape");
                    }
                } else {
                    sb.append(c);
                }
            }
            return sb.toString();
        }

        Object parseNumber() {
            int start = pos;
            if (pos < s.length() && s.charAt(pos) == '-') pos++;
            while (pos < s.length() && Character.isDigit(s.charAt(pos))) pos++;
            boolean isDouble = false;
            if (pos < s.length() && s.charAt(pos) == '.') { isDouble = true; pos++; while (pos < s.length() && Character.isDigit(s.charAt(pos))) pos++; }
            if (pos < s.length() && (s.charAt(pos) == 'e' || s.charAt(pos) == 'E')) {
                isDouble = true; pos++;
                if (pos < s.length() && (s.charAt(pos) == '+' || s.charAt(pos) == '-')) pos++;
                while (pos < s.length() && Character.isDigit(s.charAt(pos))) pos++;
            }
            String num = s.substring(start, pos);
            if (num.isEmpty() || num.equals("-")) throw err("invalid number");
            if (isDouble) return Double.parseDouble(num);
            try { return Long.parseLong(num); } catch (NumberFormatException e) { return Double.parseDouble(num); }
        }
    }

    // ---- path parsing ----
    sealed interface Step permits KeyStep, IndexStep {}
    record KeyStep(String key) implements Step {}
    record IndexStep(int idx) implements Step {}

    static List<Step> parsePath(String path) {
        List<Step> steps = new ArrayList<>();
        if (path == null || path.isEmpty() || path.equals(".")) return steps;
        String p = path.startsWith(".") ? path.substring(1) : path;
        int i = 0;
        StringBuilder cur = new StringBuilder();
        while (i < p.length()) {
            char c = p.charAt(i);
            if (c == '.') {
                if (cur.length() > 0) { steps.add(new KeyStep(cur.toString())); cur.setLength(0); }
                i++;
            } else if (c == '[') {
                if (cur.length() > 0) { steps.add(new KeyStep(cur.toString())); cur.setLength(0); }
                int end = p.indexOf(']', i);
                steps.add(new IndexStep(Integer.parseInt(p.substring(i + 1, end))));
                i = end + 1;
            } else {
                cur.append(c);
                i++;
            }
        }
        if (cur.length() > 0) steps.add(new KeyStep(cur.toString()));
        return steps;
    }

    @SuppressWarnings("unchecked")
    static Object navigate(Object doc, List<Step> steps) {
        Object cur = doc;
        for (Step step : steps) {
            if (step instanceof KeyStep(String key)) {
                if (!(cur instanceof Map)) return NOT_FOUND;
                Map<String, Object> m = (Map<String, Object>) cur;
                if (!m.containsKey(key)) return NOT_FOUND;
                cur = m.get(key);
            } else if (step instanceof IndexStep(int idx)) {
                if (!(cur instanceof List)) return NOT_FOUND;
                List<Object> l = (List<Object>) cur;
                if (idx < 0 || idx >= l.size()) return NOT_FOUND;
                cur = l.get(idx);
            }
        }
        return cur;
    }

    static final Object NOT_FOUND = new Object();

    public static void main(String[] args) throws IOException {
        String path = null, file = null;
        boolean shape = false;
        int depth = 2;
        List<String> pos = new ArrayList<>();
        for (int i = 0; i < args.length; i++) {
            String a = args[i];
            if (a.equals("--help")) { printHelp(); return; }
            else if (a.equals("--shape")) shape = true;
            else if (a.equals("--depth")) depth = Integer.parseInt(args[++i]);
            else if (a.startsWith("--")) { System.err.println("unknown flag: " + a); System.exit(2); return; }
            else pos.add(a);
        }
        if (pos.isEmpty()) { System.err.println("error: missing PATH"); System.exit(2); return; }
        path = pos.get(0);
        file = pos.size() > 1 ? pos.get(1) : null;

        String content;
        if (file == null || file.equals("-")) {
            content = new String(System.in.readAllBytes(), StandardCharsets.UTF_8);
        } else {
            Path p = Path.of(file);
            if (!Files.isReadable(p)) { System.err.println("error: cannot read " + file); System.exit(2); return; }
            content = Files.readString(p, StandardCharsets.UTF_8);
        }

        Object doc;
        try {
            doc = new JsonParser(content).parse();
        } catch (RuntimeException e) {
            System.err.println("error: invalid JSON: " + e.getMessage());
            System.exit(2);
            return;
        }

        List<Step> steps = parsePath(path);
        Object val = navigate(doc, steps);
        if (val == NOT_FOUND) {
            System.err.println("error: path not found: " + path);
            System.exit(1);
            return;
        }

        if (shape) {
            System.out.println(shapeOf(val, depth));
        } else {
            printValue(val);
        }
    }

    static void printValue(Object v) {
        if (v instanceof String s) {
            System.out.println(s);
        } else if (v == null) {
            System.out.println("null");
        } else if (v instanceof Boolean b) {
            System.out.println(b ? "true" : "false");
        } else if (v instanceof Long || v instanceof Double) {
            System.out.println(numToString(v));
        } else {
            StringBuilder sb = new StringBuilder();
            writeCompact(v, sb);
            System.out.println(sb);
        }
    }

    static String numToString(Object n) {
        if (n instanceof Long l) return String.valueOf(l);
        double d = (Double) n;
        if (d == Math.rint(d) && !Double.isInfinite(d) && Math.abs(d) < 1e15) {
            return String.valueOf((long) d);
        }
        return String.valueOf(d);
    }

    @SuppressWarnings("unchecked")
    static void writeCompact(Object v, StringBuilder sb) {
        if (v == null) { sb.append("null"); }
        else if (v instanceof String s) { writeJsonString(s, sb); }
        else if (v instanceof Boolean b) { sb.append(b ? "true" : "false"); }
        else if (v instanceof Long || v instanceof Double) { sb.append(numToString(v)); }
        else if (v instanceof Map) {
            Map<String, Object> m = (Map<String, Object>) v;
            sb.append('{');
            boolean first = true;
            for (var e : m.entrySet()) {
                if (!first) sb.append(',');
                first = false;
                writeJsonString(e.getKey(), sb);
                sb.append(':');
                writeCompact(e.getValue(), sb);
            }
            sb.append('}');
        } else if (v instanceof List) {
            List<Object> l = (List<Object>) v;
            sb.append('[');
            for (int i = 0; i < l.size(); i++) {
                if (i > 0) sb.append(',');
                writeCompact(l.get(i), sb);
            }
            sb.append(']');
        }
    }

    static void writeJsonString(String s, StringBuilder sb) {
        sb.append('"');
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            switch (c) {
                case '"' -> sb.append("\\\"");
                case '\\' -> sb.append("\\\\");
                case '\n' -> sb.append("\\n");
                case '\r' -> sb.append("\\r");
                case '\t' -> sb.append("\\t");
                default -> {
                    if (c < 0x20) sb.append(String.format("\\u%04x", (int) c));
                    else sb.append(c);
                }
            }
        }
        sb.append('"');
    }

    @SuppressWarnings("unchecked")
    static String shapeOf(Object v, int budget) {
        if (v == null) return "null";
        if (v instanceof String) return "string";
        if (v instanceof Boolean) return "boolean";
        if (v instanceof Long || v instanceof Double) return "number";
        if (v instanceof List) {
            List<Object> l = (List<Object>) v;
            if (l.isEmpty()) return "array[0]";
            if (budget <= 0) return "array[" + l.size() + "]";
            return "array[" + l.size() + "] of " + shapeOf(l.get(0), budget - 1);
        }
        if (v instanceof Map) {
            Map<String, Object> m = (Map<String, Object>) v;
            if (budget <= 0) return "object{" + m.size() + " keys}";
            if (m.isEmpty()) return "object{}";
            StringBuilder sb = new StringBuilder("object{");
            boolean first = true;
            for (var e : m.entrySet()) {
                if (!first) sb.append(", ");
                first = false;
                sb.append(e.getKey()).append(": ").append(shapeOf(e.getValue(), budget - 1));
            }
            sb.append('}');
            return sb.toString();
        }
        return "unknown";
    }

    static void printHelp() {
        System.out.println("usage: jsonpick PATH [FILE] [--shape] [--depth N]");
    }
}
