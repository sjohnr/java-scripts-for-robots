package jsonpick;

import java.math.BigDecimal;
import java.nio.ByteBuffer;
import java.nio.CharBuffer;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.CharsetDecoder;
import java.nio.charset.CoderResult;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

/** Strict RFC 8259 parser. Errors report the reason plus a 1-based line and column. */
final class JsonParser {
    /** Thrown for malformed input. The message is the reason followed by the position. */
    static final class SyntaxError extends Exception {
        private static final long serialVersionUID = 1L;

        SyntaxError(String message) {
            super(message);
        }
    }

    private static final int MAX_DEPTH = 10_000;

    private final String text;
    private int pos;
    private int depth;

    private JsonParser(String text) {
        this.text = text;
    }

    /** Decodes strict UTF-8 and parses the document. */
    static Object parse(byte[] input) throws SyntaxError {
        CharsetDecoder decoder = StandardCharsets.UTF_8.newDecoder()
                .onMalformedInput(CodingErrorAction.REPORT)
                .onUnmappableCharacter(CodingErrorAction.REPORT);
        ByteBuffer in = ByteBuffer.wrap(input);
        CharBuffer out = CharBuffer.allocate(input.length);
        CoderResult result = decoder.decode(in, out, true);
        if (result.isError()) {
            throw new SyntaxError("invalid JSON: invalid UTF-8 at byte offset " + in.position());
        }
        try {
            decoder.flush(out);
        } catch (IllegalStateException e) {
            throw new SyntaxError("invalid JSON: invalid UTF-8");
        }
        out.flip();
        return parse(out.toString());
    }

    /** Parses a complete JSON text. Any content other than whitespace after the value is an error. */
    static Object parse(String text) throws SyntaxError {
        JsonParser parser = new JsonParser(text);
        if (!text.isEmpty() && text.charAt(0) == '﻿') {
            parser.pos = 1;
        }
        parser.skipWhitespace();
        Object value = parser.parseValue();
        parser.skipWhitespace();
        if (parser.pos < text.length()) {
            throw parser.error("unexpected trailing content " + describe(text.charAt(parser.pos)));
        }
        return value;
    }

    private Object parseValue() throws SyntaxError {
        char c = current();
        switch (c) {
            case '{':
                return parseObject();
            case '[':
                return parseArray();
            case '"':
                return parseString();
            case 't':
                return parseLiteral("true", Boolean.TRUE);
            case 'f':
                return parseLiteral("false", Boolean.FALSE);
            case 'n':
                return parseLiteral("null", Json.NULL);
            default:
                if (c == '-' || isDigit(c)) {
                    return parseNumber();
                }
                throw error("unexpected character " + describe(c));
        }
    }

    private JsonObject parseObject() throws SyntaxError {
        enter();
        pos++;
        JsonObject obj = new JsonObject();
        skipWhitespace();
        if (current() == '}') {
            pos++;
            depth--;
            return obj;
        }
        while (true) {
            skipWhitespace();
            if (current() != '"') {
                throw error("expected string key but found " + describe(current()));
            }
            String key = parseString();
            skipWhitespace();
            if (current() != ':') {
                throw error("expected ':' but found " + describe(current()));
            }
            pos++;
            skipWhitespace();
            obj.put(key, parseValue());
            skipWhitespace();
            char c = current();
            if (c == ',') {
                pos++;
            } else if (c == '}') {
                pos++;
                depth--;
                return obj;
            } else {
                throw error("expected ',' or '}' but found " + describe(c));
            }
        }
    }

    private List<Object> parseArray() throws SyntaxError {
        enter();
        pos++;
        List<Object> list = new ArrayList<>();
        skipWhitespace();
        if (current() == ']') {
            pos++;
            depth--;
            return list;
        }
        while (true) {
            skipWhitespace();
            list.add(parseValue());
            skipWhitespace();
            char c = current();
            if (c == ',') {
                pos++;
            } else if (c == ']') {
                pos++;
                depth--;
                return list;
            } else {
                throw error("expected ',' or ']' but found " + describe(c));
            }
        }
    }

    private String parseString() throws SyntaxError {
        pos++;
        StringBuilder sb = new StringBuilder();
        while (true) {
            char c = current();
            if (c == '"') {
                pos++;
                return sb.toString();
            }
            if (c < 0x20) {
                throw error("unescaped control character in string");
            }
            pos++;
            if (c != '\\') {
                sb.append(c);
                continue;
            }
            char escape = current();
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
                case 'u' -> sb.append((char) parseHex4());
                default -> {
                    pos--;
                    throw error("invalid escape " + describe(escape));
                }
            }
        }
    }

    private int parseHex4() throws SyntaxError {
        int value = 0;
        for (int i = 0; i < 4; i++) {
            char c = current();
            int digit = hexValue(c);
            if (digit < 0) {
                throw error("invalid unicode escape " + describe(c));
            }
            value = value * 16 + digit;
            pos++;
        }
        return value;
    }

    private BigDecimal parseNumber() throws SyntaxError {
        int start = pos;
        if (current() == '-') {
            pos++;
        }
        if (current() == '0') {
            pos++;
        } else if (isDigit(current())) {
            skipDigits();
        } else {
            throw error("invalid number");
        }
        if (at('.')) {
            pos++;
            if (!isDigit(current())) {
                throw error("expected digit after '.' but found " + describe(current()));
            }
            skipDigits();
        }
        if (at('e') || at('E')) {
            pos++;
            if (at('+') || at('-')) {
                pos++;
            }
            if (!isDigit(current())) {
                throw error("expected digit in exponent but found " + describe(current()));
            }
            skipDigits();
        }
        return new BigDecimal(text.substring(start, pos));
    }

    private Object parseLiteral(String word, Object value) throws SyntaxError {
        for (int i = 0; i < word.length(); i++) {
            if (current() != word.charAt(i)) {
                throw error("invalid literal, expected '" + word + "'");
            }
            pos++;
        }
        return value;
    }

    private void enter() throws SyntaxError {
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
        while (pos < text.length() && isDigit(text.charAt(pos))) {
            pos++;
        }
    }

    private char current() throws SyntaxError {
        if (pos >= text.length()) {
            throw error("unexpected end of input");
        }
        return text.charAt(pos);
    }

    private boolean at(char expected) {
        return pos < text.length() && text.charAt(pos) == expected;
    }

    private SyntaxError error(String reason) {
        int line = 1;
        int column = 1;
        for (int i = 0; i < pos && i < text.length(); i++) {
            if (text.charAt(i) == '\n') {
                line++;
                column = 1;
            } else {
                column++;
            }
        }
        return new SyntaxError("invalid JSON: " + reason + " at line " + line + ", column " + column);
    }

    private static boolean isDigit(char c) {
        return c >= '0' && c <= '9';
    }

    private static int hexValue(char c) {
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

    private static String describe(char c) {
        if (c < 0x20) {
            return String.format("control character U+%04X", (int) c);
        }
        return "'" + c + "'";
    }
}
