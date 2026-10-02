package jsonpick;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Strict recursive-descent JSON parser (RFC 8259). Objects keep key order.
 * A duplicate key keeps its first position and takes the last value.
 */
final class JsonParser {

    private static final int MAX_DEPTH = 10_000;

    private final String text;
    private int pos;
    private int depth;

    private JsonParser(String text) {
        this.text = text;
    }

    /** Parses a complete document; any non-whitespace after the top-level value is an error. */
    static Object parse(String text) {
        JsonParser parser = new JsonParser(text);
        parser.skipWhitespace();
        Object value = parser.parseValue();
        parser.skipWhitespace();
        if (parser.pos < text.length()) {
            throw parser.error("unexpected " + describe(parser.peek()) + " after top-level value");
        }
        return value;
    }

    private Object parseValue() {
        int c = peek();
        switch (c) {
            case -1:
                throw error("unexpected end of input");
            case '{':
                return parseObject();
            case '[':
                return parseArray();
            case '"':
                return parseString();
            case 't':
                return parseWord("true", Boolean.TRUE);
            case 'f':
                return parseWord("false", Boolean.FALSE);
            case 'n':
                return parseWord("null", Json.NULL);
            default:
                if (c == '-' || isDigit(c)) {
                    return parseNumber();
                }
                throw error("unexpected " + describe(c));
        }
    }

    private Map<String, Object> parseObject() {
        enter();
        pos++;
        Map<String, Object> map = new LinkedHashMap<>();
        skipWhitespace();
        if (peek() == '}') {
            pos++;
            depth--;
            return map;
        }
        while (true) {
            skipWhitespace();
            if (peek() != '"') {
                throw error("expected a string key");
            }
            String key = parseString();
            skipWhitespace();
            if (peek() != ':') {
                throw error("expected ':'");
            }
            pos++;
            skipWhitespace();
            map.put(key, parseValue());
            skipWhitespace();
            if (peek() == '}') {
                pos++;
                break;
            }
            if (peek() != ',') {
                throw error("expected ',' or '}'");
            }
            pos++;
        }
        depth--;
        return map;
    }

    private List<Object> parseArray() {
        enter();
        pos++;
        List<Object> list = new ArrayList<>();
        skipWhitespace();
        if (peek() == ']') {
            pos++;
            depth--;
            return list;
        }
        while (true) {
            skipWhitespace();
            list.add(parseValue());
            skipWhitespace();
            if (peek() == ']') {
                pos++;
                break;
            }
            if (peek() != ',') {
                throw error("expected ',' or ']'");
            }
            pos++;
        }
        depth--;
        return list;
    }

    private String parseString() {
        pos++;
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
                throw error("unescaped control character " + describe(c) + " in string");
            }
            if (c != '\\') {
                sb.append((char) c);
                continue;
            }
            int escape = peek();
            if (escape == -1) {
                throw error("unterminated string");
            }
            pos++;
            switch (escape) {
                case '"' -> sb.append('"');
                case '\\' -> sb.append('\\');
                case '/' -> sb.append('/');
                case 'b' -> sb.append('\b');
                case 'f' -> sb.append('\f');
                case 'n' -> sb.append('\n');
                case 'r' -> sb.append('\r');
                case 't' -> sb.append('\t');
                case 'u' -> sb.append(parseHex4());
                default -> {
                    pos -= 2;
                    throw error("invalid escape " + describe(escape));
                }
            }
        }
    }

    private char parseHex4() {
        if (pos + 4 > text.length()) {
            throw error("invalid \\u escape");
        }
        int value = 0;
        for (int i = 0; i < 4; i++) {
            int digit = hexValue(text.charAt(pos));
            if (digit < 0) {
                throw error("invalid \\u escape");
            }
            value = value * 16 + digit;
            pos++;
        }
        return (char) value;
    }

    private Object parseNumber() {
        int start = pos;
        if (peek() == '-') {
            pos++;
        }
        if (peek() == '0') {
            pos++;
        } else if (isDigit(peek())) {
            skipDigits();
        } else {
            throw error("invalid number");
        }
        if (peek() == '.') {
            pos++;
            if (!isDigit(peek())) {
                throw error("invalid number, digit expected after '.'");
            }
            skipDigits();
        }
        if (peek() == 'e' || peek() == 'E') {
            pos++;
            if (peek() == '+' || peek() == '-') {
                pos++;
            }
            if (!isDigit(peek())) {
                throw error("invalid number, digit expected in exponent");
            }
            skipDigits();
        }
        return new Json.Number(text.substring(start, pos));
    }

    private Object parseWord(String word, Object value) {
        if (!text.startsWith(word, pos)) {
            throw error("expected '" + word + "'");
        }
        pos += word.length();
        return value;
    }

    private void enter() {
        if (++depth > MAX_DEPTH) {
            throw error("nesting deeper than " + MAX_DEPTH + " levels");
        }
    }

    private void skipWhitespace() {
        while (pos < text.length()) {
            char c = text.charAt(pos);
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
        return pos < text.length() ? text.charAt(pos) : -1;
    }

    private static boolean isDigit(int c) {
        return c >= '0' && c <= '9';
    }

    private static int hexValue(int c) {
        if (c >= '0' && c <= '9') {
            return c - '0';
        }
        if (c >= 'a' && c <= 'f') {
            return c - 'a' + 10;
        }
        if (c >= 'A' && c <= 'F') {
            return c - 'A' + 10;
        }
        return -1;
    }

    private static String describe(int c) {
        if (c == -1) {
            return "end of input";
        }
        if (c >= 0x20 && c < 0x7f) {
            return "'" + (char) c + "'";
        }
        return String.format("U+%04X", c);
    }

    private CliException error(String reason) {
        int line = 1;
        int lineStart = 0;
        for (int i = 0; i < pos; i++) {
            if (text.charAt(i) == '\n') {
                line++;
                lineStart = i + 1;
            }
        }
        int column = pos - lineStart + 1;
        return new CliException(
                "error: invalid JSON: " + reason + " at line " + line + ", column " + column);
    }
}
