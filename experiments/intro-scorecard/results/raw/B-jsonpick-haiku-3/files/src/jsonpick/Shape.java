package jsonpick;

import java.math.BigDecimal;
import java.util.List;

/** Depth-limited type and length summaries for {@code --shape}. */
final class Shape {
    private Shape() {}

    /** Describes {@code value}, descending at most {@code budget} levels below it. */
    static String describe(Object value, int budget) {
        if (value instanceof String) {
            return "string";
        }
        if (value instanceof BigDecimal) {
            return "number";
        }
        if (value instanceof Boolean) {
            return "boolean";
        }
        if (value == Json.NULL) {
            return "null";
        }
        if (value instanceof List<?> list) {
            if (list.isEmpty()) {
                return "array[0]";
            }
            if (budget == 0) {
                return "array[" + list.size() + "]";
            }
            return "array[" + list.size() + "] of " + describe(list.get(0), budget - 1);
        }
        JsonObject obj = (JsonObject) value;
        if (budget == 0) {
            return "object{" + obj.size() + " keys}";
        }
        if (obj.size() == 0) {
            return "object{}";
        }
        StringBuilder sb = new StringBuilder("object{");
        for (int i = 0; i < obj.size(); i++) {
            if (i > 0) {
                sb.append(", ");
            }
            sb.append(obj.keys.get(i)).append(": ").append(describe(obj.values.get(i), budget - 1));
        }
        return sb.append('}').toString();
    }
}
