package jsonpick;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Strict JSON (RFC 8259) reader and compact writer.
 *
 * <p>Values are represented as {@code Map<String, Object>} for objects (keys kept in document
 * order), {@code List<Object>} for arrays, {@code String}, {@code BigDecimal} for numbers (exact,
 * so no precision is lost), {@code Boolean}, and {@link #NULL} for null.
 */
final class Json {

    /** The JSON null literal. A distinct object so that Java {@code null} can mean "absent". */
    static final Object NULL = new Object() {
        @Override
        public String toString() {
            return "null";
        }
    };

    /** Thrown for malformed input. The message names the line and column of the failure. */
    static final class ParseException extends Exception {
        private static final long serialVersionUID = 1L;

        ParseException(String message) {
            super(message);
        }
    }

    private final String src;
    private int pos;

    private Json(String src) {
        this.src = src;
    }

    static Object parse(String text) throws ParseException {
        Json parser = new Json(text);
        parser.skipWhitespace();
        Object value = parser.readValue();
        parser.skipWhitespace();
        if (parser.pos < text.length()) {
            throw parser.error("unexpected content after value");
        }
        return value;
    }

    /** Output form of a node: a string prints its raw characters, anything else prints as compact JSON. */
    static String render(Object node) {
        if (node instanceof String s) {
            return s;
        }
        StringBuilder sb = new StringBuilder();
        writeCompact(node, sb);
        return sb.toString();
    }

    /** Canonical form of a number: exact value, no trailing zeros, no exponent, no decimal point for integers. */
    static String canonicalNumber(BigDecimal n) {
        if (n.signum() == 0) {
            return "0";
        }
        return n.stripTrailingZeros().toPlainString();
    }

    private static void writeCompact(Object node, StringBuilder sb) {
        if (node instanceof Map<?, ?> map) {
            sb.append('{');
            boolean first = true;
            for (Map.Entry<?, ?> entry : map.entrySet()) {
                if (!first) {
                    sb.append(',');
                }
                first = false;
                writeString((String) entry.getKey(), sb);
                sb.append(':');
                writeCompact(entry.getValue(), sb);
            }
            sb.append('}');
        } else if (node instanceof List<?> list) {
            sb.append('[');
            for (int i = 0; i < list.size(); i++) {
                if (i > 0) {
                    sb.append(',');
                }
                writeCompact(list.get(i), sb);
            }
            sb.append(']');
        } else if (node instanceof String s) {
            writeString(s, sb);
        } else if (node instanceof BigDecimal n) {
            sb.append(canonicalNumber(n));
        } else {
            sb.append(node); // true, false or null
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

    private Object readValue() throws ParseException {
        int c = peek();
        switch (c) {
            case -1:
                throw error("unexpected end of input");
            case '{':
                return readObject();
            case '[':
                return readArray();
            case '"':
                return readString();
            case 't':
                expectLiteral("true");
                return Boolean.TRUE;
            case 'f':
                expectLiteral("false");
                return Boolean.FALSE;
            case 'n':
                expectLiteral("null");
                return NULL;
            default:
                if (c == '-' || isDigit(c)) {
                    return readNumber();
                }
                throw error("unexpected character '" + (char) c + "'");
        }
    }

    private Map<String, Object> readObject() throws ParseException {
        pos++; // '{'
        Map<String, Object> map = new LinkedHashMap<>();
        skipWhitespace();
        if (peek() == '}') {
            pos++;
            return map;
        }
        while (true) {
            skipWhitespace();
            if (peek() != '"') {
                throw unexpected("string key");
            }
            String key = readString();
            skipWhitespace();
            if (peek() != ':') {
                throw unexpected("':'");
            }
            pos++;
            skipWhitespace();
            map.put(key, readValue());
            skipWhitespace();
            int c = peek();
            if (c == ',') {
                pos++;
            } else if (c == '}') {
                pos++;
                return map;
            } else {
                throw unexpected("',' or '}'");
            }
        }
    }

    private List<Object> readArray() throws ParseException {
        pos++; // '['
        List<Object> list = new ArrayList<>();
        skipWhitespace();
        if (peek() == ']') {
            pos++;
            return list;
        }
        while (true) {
            skipWhitespace();
            list.add(readValue());
            skipWhitespace();
            int c = peek();
            if (c == ',') {
                pos++;
            } else if (c == ']') {
                pos++;
                return list;
            } else {
                throw unexpected("',' or ']'");
            }
        }
    }

    private String readString() throws ParseException {
        pos++; // opening quote
        StringBuilder sb = new StringBuilder();
        while (true) {
            int c = peek();
            if (c == -1) {
                throw error("unterminated string");
            }
            pos++;
            if (c == '"') {
                return sb.toString();
            }
            if (c < 0x20) {
                pos--;
                throw error("unescaped control character in string");
            }
            if (c != '\\') {
                sb.append((char) c);
                continue;
            }
            int escaped = peek();
            if (escaped == -1) {
                throw error("unterminated string");
            }
            pos++;
            switch (escaped) {
                case '"' -> sb.append('"');
                case '\\' -> sb.append('\\');
                case '/' -> sb.append('/');
                case 'b' -> sb.append('\b');
                case 'f' -> sb.append('\f');
                case 'n' -> sb.append('\n');
                case 'r' -> sb.append('\r');
                case 't' -> sb.append('\t');
                case 'u' -> readUnicodeEscape(sb);
                default -> {
                    pos--;
                    throw error("invalid escape '\\" + (char) escaped + "'");
                }
            }
        }
    }

    private void readUnicodeEscape(StringBuilder sb) throws ParseException {
        int unit = readHex4();
        if (Character.isHighSurrogate((char) unit)) {
            if (!src.startsWith("\\u", pos)) {
                throw error("unpaired surrogate in string");
            }
            pos += 2;
            int low = readHex4();
            if (!Character.isLowSurrogate((char) low)) {
                throw error("unpaired surrogate in string");
            }
            sb.append((char) unit).append((char) low);
        } else if (Character.isLowSurrogate((char) unit)) {
            throw error("unpaired surrogate in string");
        } else {
            sb.append((char) unit);
        }
    }

    private int readHex4() throws ParseException {
        int value = 0;
        for (int i = 0; i < 4; i++) {
            int c = peek();
            int digit = (c >= 0 && c < 128) ? Character.digit(c, 16) : -1;
            if (digit < 0) {
                throw error("invalid \\u escape: expected 4 hex digits");
            }
            value = value * 16 + digit;
            pos++;
        }
        return value;
    }

    private BigDecimal readNumber() throws ParseException {
        int start = pos;
        if (peek() == '-') {
            pos++;
        }
        if (peek() == '0') {
            pos++;
        } else if (isDigit(peek())) {
            skipDigits();
        } else {
            throw unexpected("digit");
        }
        if (peek() == '.') {
            pos++;
            if (!isDigit(peek())) {
                throw unexpected("digit after '.'");
            }
            skipDigits();
        }
        if (peek() == 'e' || peek() == 'E') {
            pos++;
            if (peek() == '+' || peek() == '-') {
                pos++;
            }
            if (!isDigit(peek())) {
                throw unexpected("digit in exponent");
            }
            skipDigits();
        }
        try {
            return new BigDecimal(src.substring(start, pos));
        } catch (NumberFormatException e) {
            throw error("number out of range");
        }
    }

    private void expectLiteral(String word) throws ParseException {
        if (!src.startsWith(word, pos)) {
            throw error("expected '" + word + "'");
        }
        pos += word.length();
    }

    private void skipWhitespace() {
        while (pos < src.length()) {
            char c = src.charAt(pos);
            if (c != ' ' && c != '\t' && c != '\n' && c != '\r') {
                return;
            }
            pos++;
        }
    }

    private void skipDigits() {
        while (isDigit(peek())) {
            pos++;
        }
    }

    private int peek() {
        return pos < src.length() ? src.charAt(pos) : -1;
    }

    private static boolean isDigit(int c) {
        return c >= '0' && c <= '9';
    }

    private ParseException unexpected(String expected) {
        int c = peek();
        if (c == -1) {
            return error("unexpected end of input, expected " + expected);
        }
        return error("expected " + expected + " but found '" + (char) c + "'");
    }

    private ParseException error(String message) {
        int line = 1;
        int lineStart = 0;
        for (int i = 0; i < pos && i < src.length(); i++) {
            if (src.charAt(i) == '\n') {
                line++;
                lineStart = i + 1;
            }
        }
        int column = pos - lineStart + 1;
        return new ParseException("line " + line + ", column " + column + ": " + message);
    }
}
