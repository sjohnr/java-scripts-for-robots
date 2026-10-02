///usr/bin/env jbang "$0" "$@" ; exit $?
//DEPS com.fasterxml.jackson.core:jackson-databind:2.18.2

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;
import java.util.Map;

public class tool {

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

    static final JsonNode NOT_FOUND = null;

    static JsonNode navigate(JsonNode doc, List<Step> steps) {
        JsonNode cur = doc;
        for (Step step : steps) {
            if (step instanceof KeyStep(String key)) {
                if (!cur.isObject() || !cur.has(key)) return NOT_FOUND;
                cur = cur.get(key);
            } else if (step instanceof IndexStep(int idx)) {
                if (!cur.isArray() || idx < 0 || idx >= cur.size()) return NOT_FOUND;
                cur = cur.get(idx);
            }
        }
        return cur;
    }

    public static void main(String[] args) throws IOException {
        String path = null, file = null;
        boolean shape = false;
        int depth = 2;
        List<String> pos = new ArrayList<>();
        for (int i = 0; i < args.length; i++) {
            String a = args[i];
            if (a.equals("--help")) { System.out.println("usage: jsonpick PATH [FILE] [--shape] [--depth N]"); return; }
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

        ObjectMapper mapper = new ObjectMapper();
        JsonNode doc;
        try {
            doc = mapper.readTree(content);
        } catch (Exception e) {
            String msg = e.getMessage();
            String oneLine = msg == null ? "parse error" : msg.replaceAll("\\R.*", "");
            System.err.println("error: invalid JSON: " + oneLine);
            System.exit(2);
            return;
        }

        List<Step> steps = parsePath(path);
        JsonNode val = navigate(doc, steps);
        if (val == null) {
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

    static void printValue(JsonNode v) {
        if (v.isTextual()) {
            System.out.println(v.asText());
        } else if (v.isNull()) {
            System.out.println("null");
        } else if (v.isBoolean()) {
            System.out.println(v.asBoolean() ? "true" : "false");
        } else if (v.isNumber()) {
            System.out.println(numToString(v));
        } else {
            System.out.println(v.toString());
        }
    }

    static String numToString(JsonNode n) {
        if (n.isIntegralNumber()) return n.bigIntegerValue().toString();
        double d = n.asDouble();
        if (d == Math.rint(d) && !Double.isInfinite(d) && Math.abs(d) < 1e15) {
            return String.valueOf((long) d);
        }
        return String.valueOf(d);
    }

    static String shapeOf(JsonNode v, int budget) {
        if (v.isNull()) return "null";
        if (v.isTextual()) return "string";
        if (v.isBoolean()) return "boolean";
        if (v.isNumber()) return "number";
        if (v.isArray()) {
            if (v.size() == 0) return "array[0]";
            if (budget <= 0) return "array[" + v.size() + "]";
            return "array[" + v.size() + "] of " + shapeOf(v.get(0), budget - 1);
        }
        if (v.isObject()) {
            if (budget <= 0) return "object{" + v.size() + " keys}";
            if (v.size() == 0) return "object{}";
            StringBuilder sb = new StringBuilder("object{");
            boolean first = true;
            Iterator<Map.Entry<String, JsonNode>> it = v.fields();
            while (it.hasNext()) {
                var e = it.next();
                if (!first) sb.append(", ");
                first = false;
                sb.append(e.getKey()).append(": ").append(shapeOf(e.getValue(), budget - 1));
            }
            sb.append('}');
            return sb.toString();
        }
        return "unknown";
    }
}
