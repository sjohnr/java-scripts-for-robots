package jsonpick;

import java.math.BigDecimal;
import java.util.List;
import java.util.Map;

/** Canonical JSON rendering of parsed values. */
final class JsonWriter {

    private JsonWriter() {
    }

    /**
     * Canonical literal for a number: exact value with trailing zeros removed,
     * never exponent notation, no decimal point for integers.
     */
    static String number(BigDecimal value) {
        if (value.signum() == 0) {
            return "0";
        }
        return value.stripTrailingZeros().toPlainString();
    }

    /** Compact JSON: no whitespace, keys in document order. */
    static String compact(Object value) {
        StringBuilder sb = new StringBuilder();
        append(sb, value);
        return sb.toString();
    }

    private static void append(StringBuilder sb, Object value) {
        if (value == null) {
            sb.append("null");
        } else if (value instanceof Boolean) {
            sb.append(value.toString());
        } else if (value instanceof BigDecimal) {
            sb.append(number((BigDecimal) value));
        } else if (value instanceof String) {
            quote(sb, (String) value);
        } else if (value instanceof List) {
            sb.append('[');
            boolean first = true;
            for (Object item : (List<?>) value) {
                if (!first) {
                    sb.append(',');
                }
                first = false;
                append(sb, item);
            }
            sb.append(']');
        } else if (value instanceof Map) {
            sb.append('{');
            boolean first = true;
            for (Map.Entry<?, ?> entry : ((Map<?, ?>) value).entrySet()) {
                if (!first) {
                    sb.append(',');
                }
                first = false;
                quote(sb, (String) entry.getKey());
                sb.append(':');
                append(sb, entry.getValue());
            }
            sb.append('}');
        } else {
            throw new IllegalStateException("unexpected value type: " + value.getClass());
        }
    }

    /**
     * Writes a JSON string literal. Lone surrogates are escaped so the output
     * stays valid UTF-8 text.
     */
    private static void quote(StringBuilder sb, String s) {
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
                    if (c < 0x20) {
                        appendUnicodeEscape(sb, c);
                    } else if (Character.isHighSurrogate(c)
                            && i + 1 < s.length() && Character.isLowSurrogate(s.charAt(i + 1))) {
                        sb.append(c).append(s.charAt(++i));
                    } else if (Character.isSurrogate(c)) {
                        appendUnicodeEscape(sb, c);
                    } else {
                        sb.append(c);
                    }
            }
        }
        sb.append('"');
    }

    private static void appendUnicodeEscape(StringBuilder sb, char c) {
        sb.append(String.format("\\u%04x", (int) c));
    }
}
