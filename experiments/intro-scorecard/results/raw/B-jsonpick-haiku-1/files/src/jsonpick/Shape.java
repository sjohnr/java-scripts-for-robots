package jsonpick;

import java.math.BigDecimal;
import java.util.List;
import java.util.Map;

/** Depth-limited type/length summary used by --shape. */
final class Shape {

    private Shape() {
    }

    static String describe(Object value, int budget) {
        if (value == null) {
            return "null";
        }
        if (value instanceof Boolean) {
            return "boolean";
        }
        if (value instanceof BigDecimal) {
            return "number";
        }
        if (value instanceof String) {
            return "string";
        }
        if (value instanceof List) {
            List<?> list = (List<?>) value;
            if (budget == 0) {
                return "array[" + list.size() + "]";
            }
            if (list.isEmpty()) {
                return "array[0]";
            }
            return "array[" + list.size() + "] of " + describe(list.get(0), budget - 1);
        }
        Map<?, ?> map = (Map<?, ?>) value;
        if (budget == 0) {
            return "object{" + map.size() + "}";
        }
        StringBuilder sb = new StringBuilder("object{");
        boolean first = true;
        for (Map.Entry<?, ?> entry : map.entrySet()) {
            if (!first) {
                sb.append(", ");
            }
            first = false;
            sb.append(entry.getKey()).append(": ").append(describe(entry.getValue(), budget - 1));
        }
        return sb.append('}').toString();
    }
}
