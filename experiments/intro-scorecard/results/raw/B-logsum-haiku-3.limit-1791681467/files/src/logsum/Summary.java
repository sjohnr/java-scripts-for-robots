package logsum;

import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;
import java.time.format.ResolverStyle;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static java.nio.charset.StandardCharsets.UTF_8;

/**
 * Accumulates statistics over log lines and renders the logsum report.
 */
final class Summary {

    private static final List<String> LEVELS = List.of("DEBUG", "INFO", "WARN", "ERROR");
    private static final int ERROR_INDEX = LEVELS.indexOf("ERROR");

    /** TIMESTAMP, LEVEL and MESSAGE separated by single spaces; MESSAGE may be empty. */
    private static final Pattern LINE = Pattern.compile("(\\S+) (\\S+) (.*)", Pattern.DOTALL);
    private static final Pattern TIMESTAMP_SHAPE =
            Pattern.compile("\\d{4}-\\d{2}-\\d{2}T\\d{2}:\\d{2}:\\d{2}");
    private static final DateTimeFormatter TIMESTAMP_FORMAT =
            DateTimeFormatter.ofPattern("uuuu-MM-dd'T'HH:mm:ss").withResolverStyle(ResolverStyle.STRICT);

    private static final Pattern UUID = Pattern.compile(
            "[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}");
    // Spelled with explicit ASCII lookarounds instead of \b, so word boundaries
    // mean the same thing on every JDK version.
    private static final Pattern HEX = Pattern.compile(
            "(?<![A-Za-z0-9_])(?=[0-9a-fA-F]*[a-fA-F])[0-9a-fA-F]{6,}(?![A-Za-z0-9_])");
    private static final Pattern NUM = Pattern.compile("[0-9]+");

    /** Orders strings by code point, which is the same as UTF-8 byte order. */
    private static final Comparator<String> CODE_POINT_ORDER =
            (a, b) -> Arrays.compareUnsigned(a.getBytes(UTF_8), b.getBytes(UTF_8));

    private final String since;
    private final String level;
    private final long[] counts = new long[LEVELS.size()];
    private final Map<String, Group> errorGroups = new HashMap<>();
    private long total;
    private String first;
    private String last;

    /**
     * @param since minimum timestamp to include, or null for no lower bound
     * @param level the only level to include, or null for all levels
     */
    Summary(String since, String level) {
        this.since = since;
        this.level = level;
    }

    static boolean isLevel(String value) {
        return LEVELS.contains(value);
    }

    static boolean isTimestamp(String value) {
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

    /**
     * Applies the normalization rules in spec order: UUIDs, then hex blobs,
     * then remaining digit runs.
     */
    static String normalize(String message) {
        String s = UUID.matcher(message).replaceAll("<uuid>");
        s = HEX.matcher(s).replaceAll("<hex>");
        return NUM.matcher(s).replaceAll("<num>");
    }

    /** Feeds one input line. Lines that do not parse or do not pass the filters are ignored. */
    void accept(String line) {
        Matcher m = LINE.matcher(line);
        if (!m.matches()) {
            return;
        }
        String timestamp = m.group(1);
        String lineLevel = m.group(2);
        String message = m.group(3).stripTrailing();
        if (message.isEmpty()) {
            // Fewer than three whitespace-separated tokens.
            return;
        }
        if (!isTimestamp(timestamp)) {
            return;
        }
        int levelIndex = LEVELS.indexOf(lineLevel);
        if (levelIndex < 0) {
            return;
        }
        if (since != null && timestamp.compareTo(since) < 0) {
            return;
        }
        if (level != null && !level.equals(lineLevel)) {
            return;
        }

        total++;
        counts[levelIndex]++;
        if (first == null) {
            first = timestamp;
        }
        last = timestamp;

        if (levelIndex == ERROR_INDEX) {
            errorGroups.computeIfAbsent(normalize(message), k -> new Group(message)).count++;
        }
    }

    String report(long top) {
        StringBuilder sb = new StringBuilder();
        sb.append("total: ").append(total).append('\n');

        sb.append("by level:");
        for (int i = 0; i < LEVELS.size(); i++) {
            sb.append(' ').append(LEVELS.get(i)).append('=').append(counts[i]);
        }
        sb.append('\n');

        sb.append("first: ").append(first == null ? "n/a" : first).append('\n');
        sb.append("last: ").append(last == null ? "n/a" : last).append('\n');

        sb.append("top errors:\n");
        List<Group> groups = new ArrayList<>(errorGroups.values());
        groups.sort(Comparator.comparingLong((Group g) -> g.count).reversed()
                .thenComparing(g -> g.message, CODE_POINT_ORDER));
        for (int i = 0; i < groups.size() && i < top; i++) {
            Group g = groups.get(i);
            sb.append("  ").append(g.count).append(' ').append(g.message).append('\n');
        }
        return sb.toString();
    }

    /** One group of ERROR lines sharing a normalized message. */
    private static final class Group {
        /** The first-seen original message, used for display. */
        final String message;
        long count;

        Group(String message) {
            this.message = message;
        }
    }
}
