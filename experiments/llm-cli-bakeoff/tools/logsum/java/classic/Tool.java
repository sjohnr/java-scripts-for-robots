import java.io.BufferedReader;
import java.io.FileReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.Reader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Pattern;

public class Tool {
    static final List<String> LEVELS = List.of("DEBUG", "INFO", "WARN", "ERROR");
    static final Pattern UUID_RE = Pattern.compile("[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}");
    static final Pattern HEX_RE = Pattern.compile("\\b(?=[0-9a-fA-F]*[a-fA-F])[0-9a-fA-F]{6,}\\b");
    static final Pattern NUM_RE = Pattern.compile("[0-9]+");

    public static void main(String[] args) throws IOException {
        String file = null;
        String since = null, level = null;
        int top = 5;
        for (int i = 0; i < args.length; i++) {
            String a = args[i];
            if (a.equals("--help")) { printHelp(); return; }
            else if (a.equals("--since")) since = args[++i];
            else if (a.equals("--level")) level = args[++i];
            else if (a.equals("--top")) top = Integer.parseInt(args[++i]);
            else if (a.startsWith("--")) { System.err.println("unknown flag: " + a); System.exit(2); return; }
            else file = a;
        }
        if (level != null && !LEVELS.contains(level)) {
            System.err.println("error: invalid value for --level: " + level);
            System.exit(2);
            return;
        }
        if (since != null && !since.matches("\\d{4}-\\d{2}-\\d{2}T\\d{2}:\\d{2}:\\d{2}")) {
            System.err.println("error: invalid value for --since: " + since);
            System.exit(2);
            return;
        }

        Reader source;
        if (file == null || file.equals("-")) {
            source = new InputStreamReader(System.in, StandardCharsets.UTF_8);
        } else {
            Path p = Path.of(file);
            if (!Files.isReadable(p)) {
                System.err.println("error: cannot read " + file);
                System.exit(2);
                return;
            }
            source = new InputStreamReader(Files.newInputStream(p), StandardCharsets.UTF_8);
        }

        long total = 0;
        Map<String, Long> byLevel = new LinkedHashMap<>();
        for (String l : LEVELS) byLevel.put(l, 0L);
        String first = null, last = null;
        Map<String, long[]> groupCount = new LinkedHashMap<>();
        Map<String, String> groupDisplay = new LinkedHashMap<>();

        try (BufferedReader br = new BufferedReader(source)) {
            String line;
            while ((line = br.readLine()) != null) {
                int sp1 = line.indexOf(' ');
                if (sp1 < 0) continue;
                int sp2 = line.indexOf(' ', sp1 + 1);
                if (sp2 < 0) continue;
                String ts = line.substring(0, sp1);
                String lvl = line.substring(sp1 + 1, sp2);
                String msg = line.substring(sp2 + 1);
                if (!ts.matches("\\d{4}-\\d{2}-\\d{2}T\\d{2}:\\d{2}:\\d{2}")) continue;
                if (!LEVELS.contains(lvl)) continue;
                if (since != null && ts.compareTo(since) < 0) continue;
                if (level != null && !lvl.equals(level)) continue;

                total++;
                byLevel.put(lvl, byLevel.get(lvl) + 1);
                if (first == null) first = ts;
                last = ts;

                if (lvl.equals("ERROR")) {
                    String norm = normalize(msg);
                    groupCount.computeIfAbsent(norm, k -> new long[1])[0]++;
                    groupDisplay.putIfAbsent(norm, msg);
                }
            }
        }

        StringBuilder out = new StringBuilder();
        out.append("total: ").append(total).append('\n');
        out.append("by level: ");
        boolean f = true;
        for (String l : LEVELS) { if (!f) out.append(' '); out.append(l).append('=').append(byLevel.get(l)); f = false; }
        out.append('\n');
        out.append("first: ").append(first == null ? "n/a" : first).append('\n');
        out.append("last: ").append(last == null ? "n/a" : last).append('\n');
        out.append("top errors:\n");

        List<String> keys = new ArrayList<>(groupCount.keySet());
        keys.sort((a, b) -> {
            long ca = groupCount.get(a)[0], cb = groupCount.get(b)[0];
            if (ca != cb) return Long.compare(cb, ca);
            return groupDisplay.get(a).compareTo(groupDisplay.get(b));
        });
        for (int i = 0; i < Math.min(top, keys.size()); i++) {
            String k = keys.get(i);
            out.append("  ").append(groupCount.get(k)[0]).append(' ').append(groupDisplay.get(k)).append('\n');
        }
        System.out.print(out);
    }

    static String normalize(String msg) {
        String s = UUID_RE.matcher(msg).replaceAll("<uuid>");
        s = HEX_RE.matcher(s).replaceAll("<hex>");
        s = NUM_RE.matcher(s).replaceAll("<num>");
        return s;
    }

    static void printHelp() {
        System.out.println("usage: logsum [FILE] [--since TIMESTAMP] [--level LEVEL] [--top N]");
    }
}
