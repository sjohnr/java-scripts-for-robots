package logsum;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Pattern;

/** Accumulates statistics over filtered entries in a single pass and renders the report. */
final class Summary {

    private static final Pattern UUID =
            Pattern.compile("[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}");
    private static final Pattern HEX =
            Pattern.compile("\\b(?=[0-9a-fA-F]*[a-fA-F])[0-9a-fA-F]{6,}\\b");
    private static final Pattern NUM = Pattern.compile("[0-9]+");

    /** Error lines sharing one normalized message. */
    private static final class Group {
        final String display;
        long count;

        Group(String display) {
            this.display = display;
        }
    }

    private static final Comparator<Group> ERROR_ORDER = Comparator
            .comparingLong((Group g) -> g.count).reversed()
            .thenComparing(g -> g.display.getBytes(StandardCharsets.UTF_8), Arrays::compareUnsigned);

    private final String since;
    private final Level level;

    private final long[] counts = new long[Level.values().length];
    private long total;
    private String first;
    private String last;
    private final Map<String, Group> errorGroups = new LinkedHashMap<>();

    /** {@code since} and {@code level} may be null, meaning no filter. */
    Summary(String since, Level level) {
        this.since = since;
        this.level = level;
    }

    void accept(LineParser.Entry entry) {
        if (entry == null) {
            return;
        }
        if (since != null && entry.timestamp().compareTo(since) < 0) {
            return;
        }
        if (level != null && entry.level() != level) {
            return;
        }

        total++;
        counts[entry.level().ordinal()]++;
        if (first == null) {
            first = entry.timestamp();
        }
        last = entry.timestamp();

        if (entry.level() == Level.ERROR) {
            errorGroups.computeIfAbsent(normalize(entry.message()), k -> new Group(entry.message())).count++;
        }
    }

    String render(int top) {
        StringBuilder sb = new StringBuilder();
        sb.append("total: ").append(total).append('\n');

        sb.append("by level:");
        for (Level l : Level.values()) {
            sb.append(' ').append(l.name()).append('=').append(counts[l.ordinal()]);
        }
        sb.append('\n');

        sb.append("first: ").append(total == 0 ? "n/a" : first).append('\n');
        sb.append("last: ").append(total == 0 ? "n/a" : last).append('\n');

        sb.append("top errors:\n");
        List<Group> groups = new ArrayList<>(errorGroups.values());
        groups.sort(ERROR_ORDER);
        for (Group g : groups.subList(0, Math.min(top, groups.size()))) {
            sb.append("  ").append(g.count).append(' ').append(g.display).append('\n');
        }
        return sb.toString();
    }

    /** Applies the grouping normalization rules in order. */
    static String normalize(String message) {
        String s = UUID.matcher(message).replaceAll("<uuid>");
        s = HEX.matcher(s).replaceAll("<hex>");
        return NUM.matcher(s).replaceAll("<num>");
    }
}
