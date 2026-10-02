package jsonpick;

import java.math.BigDecimal;
import java.util.List;
import java.util.Map;
import java.util.StringJoiner;

/** JSON values as produced by {@link JsonParser}, and the renderers that print them. */
final class Json {

    /** JSON null. A sentinel, so that a Java null never stands for a value. */
    static final Object NULL = new Object() {
        @Override
        public String toString() {
            return "null";
        }
    };

    /** A JSON number, kept as its source literal until it is printed. */
    record Number(String literal) {

        /** Trailing fraction zeros removed, integers without a decimal point, never exponent form. */
        String canonical() {
            return new BigDecimal(literal).stripTrailingZeros().toPlainString();
        }
    }

    private Json() {
    }

    /** Canonical JSON literal for a number, boolean or null. */
    static String literal(Object value) {
        if (value instanceof Number number) {
            return number.canonical();
        }
        return value.toString();
    }

    /** Compact JSON: no insignificant whitespace, object keys in document order. */
    static String compact(Object value) {
        StringBuilder sb = new StringBuilder();
        writeCompact(sb, value);
        return sb.toString();
    }

    private static void writeCompact(StringBuilder sb, Object value) {
        if (value instanceof Map<?, ?> map) {
            sb.append('{');
            String separator = "";
            for (Map.Entry<?, ?> entry : map.entrySet()) {
                sb.append(separator);
                writeString(sb, (String) entry.getKey());
                sb.append(':');
                writeCompact(sb, entry.getValue());
                separator = ",";
            }
            sb.append('}');
        } else if (value instanceof List<?> list) {
            sb.append('[');
            String separator = "";
            for (Object item : list) {
                sb.append(separator);
                writeCompact(sb, item);
                separator = ",";
            }
            sb.append(']');
        } else if (value instanceof String s) {
            writeString(sb, s);
        } else {
            sb.append(literal(value));
        }
    }

    private static void writeString(StringBuilder sb, String s) {
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
                    if (c < 0x20) {
                        sb.append(String.format("\\u%04x", (int) c));
                    } else {
                        sb.append(c);
                    }
                }
            }
        }
        sb.append('"');
    }

    /**
     * Depth-limited type summary. {@code budget} is the number of container levels still
     * to describe; at budget 0 arrays report only their length and objects only their key count.
     */
    static String shape(Object value, int budget) {
        if (value instanceof String) {
            return "string";
        }
        if (value instanceof Number) {
            return "number";
        }
        if (value instanceof Boolean) {
            return "boolean";
        }
        if (value == NULL) {
            return "null";
        }
        if (value instanceof List<?> list) {
            if (list.isEmpty()) {
                return "array[0]";
            }
            if (budget == 0) {
                return "array[" + list.size() + "]";
            }
            return "array[" + list.size() + "] of " + shape(list.get(0), budget - 1);
        }
        Map<?, ?> map = (Map<?, ?>) value;
        if (budget == 0) {
            return "object{" + map.size() + " keys}";
        }
        if (map.isEmpty()) {
            return "object{}";
        }
        StringJoiner joiner = new StringJoiner(", ", "object{", "}");
        for (Map.Entry<?, ?> entry : map.entrySet()) {
            joiner.add(entry.getKey() + ": " + shape(entry.getValue(), budget - 1));
        }
        return joiner.toString();
    }
}
