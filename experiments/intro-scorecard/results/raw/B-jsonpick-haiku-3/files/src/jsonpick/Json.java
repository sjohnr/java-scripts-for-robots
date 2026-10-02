package jsonpick;

import java.math.BigDecimal;
import java.util.List;

/** JSON value helpers: the null sentinel, canonical numbers and compact serialization. */
final class Json {
    /** JSON null. Java null is reserved for "absent". */
    static final Object NULL = new Object();

    private Json() {}

    /** Integers print without a decimal point; otherwise no trailing zeros. */
    static String canonicalNumber(BigDecimal number) {
        if (number.signum() == 0) {
            return "0";
        }
        return number.stripTrailingZeros().toPlainString();
    }

    /** Appends {@code value} as compact JSON with no extra whitespace. */
    static void writeCompact(Object value, StringBuilder sb) {
        if (value instanceof String s) {
            writeString(s, sb);
        } else if (value instanceof BigDecimal n) {
            sb.append(canonicalNumber(n));
        } else if (value instanceof Boolean b) {
            sb.append(b.booleanValue() ? "true" : "false");
        } else if (value == NULL) {
            sb.append("null");
        } else if (value instanceof JsonObject obj) {
            sb.append('{');
            for (int i = 0; i < obj.size(); i++) {
                if (i > 0) {
                    sb.append(',');
                }
                writeString(obj.keys.get(i), sb);
                sb.append(':');
                writeCompact(obj.values.get(i), sb);
            }
            sb.append('}');
        } else {
            List<?> list = (List<?>) value;
            sb.append('[');
            for (int i = 0; i < list.size(); i++) {
                if (i > 0) {
                    sb.append(',');
                }
                writeCompact(list.get(i), sb);
            }
            sb.append(']');
        }
    }

    private static void writeString(String s, StringBuilder sb) {
        sb.append('"');
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            switch (c) {
                case '"' -> sb.append("\\\"");
                case '\\' -> sb.append("\\\\");
                case '\b' -> sb.append("\\b");
                case '\f' -> sb.append("\\f");
                case '\n' -> sb.append("\\n");
                case '\r' -> sb.append("\\r");
                case '\t' -> sb.append("\\t");
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
}
