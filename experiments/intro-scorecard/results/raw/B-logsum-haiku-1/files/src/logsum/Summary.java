package logsum;

import java.io.IOException;
import java.io.Writer;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/** Statistics over the lines that pass the filters, built in a single streaming pass. */
final class Summary {

    private static final List<String> LEVEL_ORDER = List.of("DEBUG", "INFO", "WARN", "ERROR");

    /** Count descending, then message ascending by UTF-8 byte order. */
    private static final Comparator<ErrorGroup> RANKING = Comparator
            .comparingLong((ErrorGroup g) -> g.count).reversed()
            .thenComparing((ErrorGroup a, ErrorGroup b) -> Arrays.compareUnsigned(
                    a.message.getBytes(StandardCharsets.UTF_8), b.message.getBytes(StandardCharsets.UTF_8)));

    private final int topN;
    private final Map<String, Long> countsByLevel = new HashMap<>();
    private final Map<String, ErrorGroup> errorGroups = new HashMap<>();
    private long total;
    private String first;
    private String last;

    Summary(int topN) {
        this.topN = topN;
    }

    void add(LogLine line) {
        total++;
        countsByLevel.merge(line.level(), 1L, Long::sum);
        if (first == null) {
            first = line.timestamp();
        }
        last = line.timestamp();
        if (line.level().equals("ERROR")) {
            errorGroups
                    .computeIfAbsent(Normalizer.normalize(line.message()), key -> new ErrorGroup(line.message()))
                    .count++;
        }
    }

    void write(Writer out) throws IOException {
        out.write("total: " + total + "\n");
        out.write("by level:");
        for (String level : LEVEL_ORDER) {
            out.write(" " + level + "=" + countsByLevel.getOrDefault(level, 0L));
        }
        out.write("\n");
        out.write("first: " + (first == null ? "n/a" : first) + "\n");
        out.write("last: " + (last == null ? "n/a" : last) + "\n");
        out.write("top errors:\n");

        List<ErrorGroup> ranked = new ArrayList<>(errorGroups.values());
        ranked.sort(RANKING);
        for (ErrorGroup group : ranked.subList(0, Math.min(topN, ranked.size()))) {
            out.write("  " + group.count + " " + group.message + "\n");
        }
    }

    private static final class ErrorGroup {
        /** The message as first seen for this group, used for display. */
        final String message;
        long count;

        ErrorGroup(String message) {
            this.message = message;
        }
    }
}
