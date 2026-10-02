package logsum;

import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;
import java.time.format.ResolverStyle;
import java.util.regex.Pattern;

/**
 * Parses lines of the form {@code TIMESTAMP LEVEL MESSAGE}. Lines that do not fit are skipped
 * by returning null; this is deliberately lenient.
 */
final class LineParser {

    /** A successfully parsed line. The message has its trailing whitespace removed. */
    record Entry(String timestamp, Level level, String message) {
    }

    private static final Pattern TIMESTAMP_SHAPE =
            Pattern.compile("[0-9]{4}-[0-9]{2}-[0-9]{2}T[0-9]{2}:[0-9]{2}:[0-9]{2}");

    private static final DateTimeFormatter TIMESTAMP_FORMAT =
            DateTimeFormatter.ofPattern("uuuu-MM-dd'T'HH:mm:ss").withResolverStyle(ResolverStyle.STRICT);

    private LineParser() {
    }

    /** True if {@code value} is a calendar-valid {@code YYYY-MM-DDTHH:MM:SS} timestamp. */
    static boolean isValidTimestamp(String value) {
        if (!TIMESTAMP_SHAPE.matcher(value).matches()) {
            return false;
        }
        try {
            LocalDateTime.parse(value, TIMESTAMP_FORMAT);
            return true;
        } catch (DateTimeParseException e) {
            return false;
        }
    }

    /** Returns the parsed entry, or null if the line should be skipped. */
    static Entry parse(String line) {
        int n = line.length();

        int tsStart = skipWhitespace(line, 0);
        int tsEnd = skipNonWhitespace(line, tsStart);
        int levelStart = skipWhitespace(line, tsEnd);
        int levelEnd = skipNonWhitespace(line, levelStart);
        int msgStart = skipWhitespace(line, levelEnd);
        if (tsStart == tsEnd || levelStart == levelEnd || msgStart >= n) {
            return null;
        }

        String timestamp = line.substring(tsStart, tsEnd);
        if (!isValidTimestamp(timestamp)) {
            return null;
        }
        Level level = Level.parse(line.substring(levelStart, levelEnd));
        if (level == null) {
            return null;
        }
        return new Entry(timestamp, level, line.substring(msgStart).stripTrailing());
    }

    private static int skipWhitespace(String s, int i) {
        while (i < s.length() && Character.isWhitespace(s.charAt(i))) {
            i++;
        }
        return i;
    }

    private static int skipNonWhitespace(String s, int i) {
        while (i < s.length() && !Character.isWhitespace(s.charAt(i))) {
            i++;
        }
        return i;
    }
}
