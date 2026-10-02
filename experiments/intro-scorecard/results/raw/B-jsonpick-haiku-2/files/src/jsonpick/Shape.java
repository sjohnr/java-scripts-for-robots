package jsonpick;

import java.math.BigDecimal;
import java.util.List;
import java.util.Map;

/** Builds the depth-limited type/length summary printed by {@code --shape}. */
final class Shape {

    private Shape() {
    }

    static String describe(Object node, int depth) {
        StringBuilder sb = new StringBuilder();
        append(sb, node, depth);
        return sb.toString();
    }

    private static void append(StringBuilder sb, Object node, int budget) {
        if (node instanceof Map<?, ?> map) {
            if (budget == 0) {
                sb.append("object{").append(map.size()).append(" keys}");
                return;
            }
            sb.append("object{");
            boolean first = true;
            for (Map.Entry<?, ?> entry : map.entrySet()) {
                if (!first) {
                    sb.append(", ");
                }
                first = false;
                sb.append(entry.getKey()).append(": ");
                append(sb, entry.getValue(), budget - 1);
            }
            sb.append('}');
        } else if (node instanceof List<?> list) {
            sb.append("array[").append(list.size()).append(']');
            if (budget > 0 && !list.isEmpty()) {
                sb.append(" of ");
                append(sb, list.get(0), budget - 1);
            }
        } else if (node instanceof String) {
            sb.append("string");
        } else if (node instanceof BigDecimal) {
            sb.append("number");
        } else if (node instanceof Boolean) {
            sb.append("boolean");
        } else {
            sb.append("null");
        }
    }
}
