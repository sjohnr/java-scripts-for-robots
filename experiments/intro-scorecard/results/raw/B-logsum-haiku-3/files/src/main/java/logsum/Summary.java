package logsum;

import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;
import java.time.format.ResolverStyle;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Pattern;

/** Accumulates statistics over the lines of a log, one line at a time. */
final class Summary {

    static final List<String> LEVELS = List.of("DEBUG", "INFO", "WARN", "ERROR");
    private static final String ERROR = "ERROR";

    private static final Pattern TIMESTAMP_SHAPE =
            Pattern.compile("[0-9]{4}-[0-9]{2}-[0-9]{2}T[0-9]{2}:[0-9]{2}:[0-9]{2}");
    private static final DateTimeFormatter TIMESTAMP =
            DateTimeFormatter.ofPattern("uuuu-MM-dd'T'HH:mm:ss").withResolverStyle(ResolverStyle.STRICT);

    private static final Pattern UUID =
            Pattern.compile("[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}");
    private static final Pattern HEX =
            Pattern.compile("\\b(?=[0-9a-fA-F]*[a-fA-F])[0-9a-fA-F]{6,}\\b");
    private static final Pattern NUMBER = Pattern.compile("[0-9]+");

    private static final Comparator<Group> BY_COUNT_THEN_MESSAGE =
            Comparator.<Group>comparingLong(g -> g.count).reversed()
                    .thenComparing(g -> g.message, Summary::compareCodePoints);

    private final String since;
    private final String level;
    private final int top;

    private final long[] levelCounts = new long[LEVELS.size()];
    private long total;
    private String firstTimestamp;
    private String lastTimestamp;
    private final Map<String, Group> errorGroups = new HashMap<>();

    Summary(String since, String level, int top) {
        this.since = since;
        this.level = level;
        this.top = top;
    }

    /** Feeds one raw input line; malformed or filtered-out lines are ignored. */
    void accept(String line) {
        Entry entry = parse(line);
        if (entry == null) {
            return;
        }
        if (since != null && entry.timestamp().compareTo(since) < 0) {
            return;
        }
        if (level != null && !entry.level().equals(level)) {
            return;
        }

        total++;
        levelCounts[LEVELS.indexOf(entry.level())]++;
        if (firstTimestamp == null) {
            firstTimestamp = entry.timestamp();
        }
        lastTimestamp = entry.timestamp();

        if (entry.level().equals(ERROR)) {
            errorGroups.computeIfAbsent(normalize(entry.message()), k -> new Group(entry.message())).count++;
        }
    }

    String render() {
        StringBuilder sb = new StringBuilder();
        sb.append("total: ").append(total).append('\n');
        sb.append("by level:");
        for (int i = 0; i < LEVELS.size(); i++) {
            sb.append(' ').append(LEVELS.get(i)).append('=').append(levelCounts[i]);
        }
        sb.append('\n');
        sb.append("first: ").append(total == 0 ? "n/a" : firstTimestamp).append('\n');
        sb.append("last: ").append(total == 0 ? "n/a" : lastTimestamp).append('\n');
        sb.append("top errors:\n");

        List<Group> groups = new ArrayList<>(errorGroups.values());
        groups.sort(BY_COUNT_THEN_MESSAGE);
        for (Group g : groups.subList(0, Math.min(top, groups.size()))) {
            sb.append("  ").append(g.count).append(' ').append(g.message).append('\n');
        }
        return sb.toString();
    }

    /** True if the text is exactly a YYYY-MM-DDTHH:MM:SS timestamp with a valid calendar date and time. */
    static boolean isTimestamp(String text) {
        if (!TIMESTAMP_SHAPE.matcher(text).matches()) {
            return false;
        }
        try {
            LocalDateTime.parse(text, TIMESTAMP);
            return true;
        } catch (DateTimeParseException e) {
            return false;
        }
    }

    /**
     * Splits a line into TIMESTAMP, LEVEL and MESSAGE. The first two whitespace-separated tokens
     * must be a valid timestamp and level; MESSAGE is the rest of the line with trailing whitespace
     * removed. Returns null for lines that do not match.
     */
    static Entry parse(String line) {
        int tsStart = skipSpace(line, 0);
        int tsEnd = skipToken(line, tsStart);
        int lvStart = skipSpace(line, tsEnd);
        int lvEnd = skipToken(line, lvStart);
        int msgStart = skipSpace(line, lvEnd);
        int msgEnd = line.length();
        while (msgEnd > msgStart && Character.isWhitespace(line.charAt(msgEnd - 1))) {
            msgEnd--;
        }
        if (tsStart == tsEnd || lvStart == lvEnd || msgStart >= msgEnd) {
            return null;
        }

        String timestamp = line.substring(tsStart, tsEnd);
        String lvl = line.substring(lvStart, lvEnd);
        if (!LEVELS.contains(lvl) || !isTimestamp(timestamp)) {
            return null;
        }
        return new Entry(timestamp, lvl, line.substring(msgStart, msgEnd));
    }

    /** Collapses variable parts of a message so that similar errors group together. */
    static String normalize(String message) {
        String s = UUID.matcher(message).replaceAll("<uuid>");
        s = HEX.matcher(s).replaceAll("<hex>");
        return NUMBER.matcher(s).replaceAll("<num>");
    }

    /** Orders strings by Unicode code point, which matches UTF-8 byte order. */
    static int compareCodePoints(String a, String b) {
        int i = 0;
        int j = 0;
        while (i < a.length() && j < b.length()) {
            int ca = a.codePointAt(i);
            int cb = b.codePointAt(j);
            if (ca != cb) {
                return Integer.compare(ca, cb);
            }
            i += Character.charCount(ca);
            j += Character.charCount(cb);
        }
        return Integer.compare(a.length() - i, b.length() - j);
    }

    private static int skipSpace(String s, int i) {
        while (i < s.length() && Character.isWhitespace(s.charAt(i))) {
            i++;
        }
        return i;
    }

    private static int skipToken(String s, int i) {
        while (i < s.length() && !Character.isWhitespace(s.charAt(i))) {
            i++;
        }
        return i;
    }

    /** One parsed input line. */
    record Entry(String timestamp, String level, String message) {
    }

    /** Error lines sharing a normalized form; {@code message} is the first-seen original. */
    private static final class Group {
        final String message;
        long count;

        Group(String message) {
            this.message = message;
        }
    }
}
