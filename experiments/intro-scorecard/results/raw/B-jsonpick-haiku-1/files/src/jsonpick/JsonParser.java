package jsonpick;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Strict RFC 8259 JSON parser. Values are represented as:
 * Map (LinkedHashMap, document order), List (ArrayList), String,
 * BigDecimal (exact number), Boolean, and null for JSON null.
 */
final class JsonParser {

    /** Thrown for any malformed input; the message is the reason text only. */
    static final class JsonException extends RuntimeException {
        private static final long serialVersionUID = 1L;

        JsonException(String message) {
            super(message);
        }
    }

    /** Guards the recursive descent against stack exhaustion. */
    static final int MAX_DEPTH = 1000;

    private final String src;
    private int pos;

    private JsonParser(String src) {
        this.src = src;
    }

    static Object parse(String text) {
        JsonParser p = new JsonParser(text);
        p.skipWhitespace();
        Object value = p.parseValue(0);
        p.skipWhitespace();
        if (p.pos < p.src.length()) {
            throw p.error("unexpected content after top-level value");
        }
        return value;
    }

    private Object parseValue(int depth) {
        if (pos >= src.length()) {
            throw error("unexpected end of input");
        }
        char c = src.charAt(pos);
        switch (c) {
            case '{':
                return parseObject(depth + 1);
            case '[':
                return parseArray(depth + 1);
            case '"':
                return parseString();
            case 't':
                return parseLiteral("true", Boolean.TRUE);
            case 'f':
                return parseLiteral("false", Boolean.FALSE);
            case 'n':
                return parseLiteral("null", null);
            default:
                if (c == '-' || (c >= '0' && c <= '9')) {
                    return parseNumber();
                }
                throw error("unexpected character " + describe(c));
        }
    }

    private Map<String, Object> parseObject(int depth) {
        checkDepth(depth);
        pos++; // '{'
        Map<String, Object> map = new LinkedHashMap<>();
        skipWhitespace();
        if (peekIs('}')) {
            pos++;
            return map;
        }
        while (true) {
            if (!peekIs('"')) {
                throw error("expected string key in object");
            }
            String key = parseString();
            skipWhitespace();
            if (!peekIs(':')) {
                throw error("expected ':' after object key");
            }
            pos++;
            skipWhitespace();
            map.put(key, parseValue(depth));
            skipWhitespace();
            if (peekIs(',')) {
                pos++;
                skipWhitespace();
            } else if (peekIs('}')) {
                pos++;
                return map;
            } else {
                throw error("expected ',' or '}' in object");
            }
        }
    }

    private List<Object> parseArray(int depth) {
        checkDepth(depth);
        pos++; // '['
        List<Object> list = new ArrayList<>();
        skipWhitespace();
        if (peekIs(']')) {
            pos++;
            return list;
        }
        while (true) {
            list.add(parseValue(depth));
            skipWhitespace();
            if (peekIs(',')) {
                pos++;
                skipWhitespace();
            } else if (peekIs(']')) {
                pos++;
                return list;
            } else {
                throw error("expected ',' or ']' in array");
            }
        }
    }

    private String parseString() {
        pos++; // opening '"'
        StringBuilder sb = new StringBuilder();
        while (true) {
            if (pos >= src.length()) {
                throw error("unterminated string");
            }
            char c = src.charAt(pos);
            if (c == '"') {
                pos++;
                return sb.toString();
            }
            if (c < 0x20) {
                throw error("unescaped control character in string");
            }
            if (c != '\\') {
                sb.append(c);
                pos++;
                continue;
            }
            pos++; // backslash
            if (pos >= src.length()) {
                throw error("unterminated string");
            }
            char e = src.charAt(pos);
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
                    sb.append(parseUnicodeEscape());
                    continue;
                default:
                    throw error("invalid escape sequence \\" + describe(e));
            }
            pos++;
        }
    }

    /** Called with pos on the 'u'; leaves pos after the four hex digits. */
    private char parseUnicodeEscape() {
        int value = 0;
        for (int i = 1; i <= 4; i++) {
            int idx = pos + i;
            int digit = idx < src.length() ? Character.digit(src.charAt(idx), 16) : -1;
            if (digit < 0) {
                pos = idx;
                throw error("invalid \\u escape: expected 4 hex digits");
            }
            value = value * 16 + digit;
        }
        pos += 5;
        return (char) value;
    }

    private BigDecimal parseNumber() {
        int start = pos;
        if (peekIs('-')) {
            pos++;
        }
        if (peekIs('0')) {
            pos++;
        } else if (peekDigit()) {
            skipDigits();
        } else {
            throw error("invalid number: expected digit");
        }
        if (peekIs('.')) {
            pos++;
            if (!peekDigit()) {
                throw error("invalid number: expected digit after '.'");
            }
            skipDigits();
        }
        if (peekIs('e') || peekIs('E')) {
            pos++;
            if (peekIs('+') || peekIs('-')) {
                pos++;
            }
            if (!peekDigit()) {
                throw error("invalid number: expected digit in exponent");
            }
            skipDigits();
        }
        try {
            return new BigDecimal(src.substring(start, pos));
        } catch (NumberFormatException ex) {
            throw error("number out of range");
        }
    }

    private Object parseLiteral(String word, Object value) {
        if (!src.startsWith(word, pos)) {
            throw error("invalid literal: expected '" + word + "'");
        }
        pos += word.length();
        return value;
    }

    private void checkDepth(int depth) {
        if (depth > MAX_DEPTH) {
            throw error("nesting too deep (limit " + MAX_DEPTH + ")");
        }
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
        while (peekDigit()) {
            pos++;
        }
    }

    private boolean peekIs(char c) {
        return pos < src.length() && src.charAt(pos) == c;
    }

    private boolean peekDigit() {
        return pos < src.length() && src.charAt(pos) >= '0' && src.charAt(pos) <= '9';
    }

    private static String describe(char c) {
        if (c >= 0x20 && c <= 0x7E) {
            return "'" + c + "'";
        }
        return String.format("U+%04X", (int) c);
    }

    /** Builds an error whose message names the 1-based line and column of pos. */
    private JsonException error(String reason) {
        int line = 1;
        int lineStart = 0;
        for (int i = 0; i < pos && i < src.length(); i++) {
            if (src.charAt(i) == '\n') {
                line++;
                lineStart = i + 1;
            }
        }
        int column = pos - lineStart + 1;
        return new JsonException("line " + line + ", column " + column + ": " + reason);
    }
}
