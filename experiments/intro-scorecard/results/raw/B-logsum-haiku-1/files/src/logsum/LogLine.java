package logsum;

import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;
import java.time.format.ResolverStyle;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** One input line of the form {@code TIMESTAMP LEVEL MESSAGE}. */
record LogLine(String timestamp, String level, String message) {

    static final Set<String> LEVELS = Set.of("DEBUG", "INFO", "WARN", "ERROR");

    private static final Pattern TIMESTAMP_SHAPE =
            Pattern.compile("\\d{4}-\\d{2}-\\d{2}T\\d{2}:\\d{2}:\\d{2}");
    private static final DateTimeFormatter TIMESTAMP_FORMAT =
            DateTimeFormatter.ofPattern("uuuu-MM-dd'T'HH:mm:ss").withResolverStyle(ResolverStyle.STRICT);

    // TIMESTAMP and LEVEL are whitespace-separated tokens. MESSAGE starts after exactly one separator
    // and keeps everything else verbatim; trailing whitespace is dropped because output may not have it.
    private static final Pattern LINE =
            Pattern.compile("^\\s*(\\S+)\\s+(\\S+)\\s(.*\\S)\\s*$", Pattern.DOTALL);

    /** True if {@code s} is exactly a real calendar date-time in {@code YYYY-MM-DDTHH:MM:SS} form. */
    static boolean isTimestamp(String s) {
        if (!TIMESTAMP_SHAPE.matcher(s).matches()) {
            return false;
        }
        try {
            LocalDateTime.parse(s, TIMESTAMP_FORMAT);
            return true;
        } catch (DateTimeParseException e) {
            return false;
        }
    }

    /** Parses a raw line, or returns null if it is not a valid entry (such lines are silently skipped). */
    static LogLine parse(String raw) {
        Matcher m = LINE.matcher(raw);
        if (!m.matches()) {
            return null;
        }
        String timestamp = m.group(1);
        String level = m.group(2);
        if (!LEVELS.contains(level) || !isTimestamp(timestamp)) {
            return null;
        }
        return new LogLine(timestamp, level, m.group(3));
    }
}
