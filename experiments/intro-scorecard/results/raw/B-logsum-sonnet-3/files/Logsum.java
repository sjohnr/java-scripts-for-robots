import java.io.*;
import java.nio.charset.StandardCharsets;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.*;
import java.util.regex.Pattern;

public class Logsum {
    static final String[] LEVELS = {"DEBUG", "INFO", "WARN", "ERROR"};
    static final Pattern TS = Pattern.compile("\\d{4}-\\d{2}-\\d{2}T\\d{2}:\\d{2}:\\d{2}");
    static final Pattern UUID = Pattern.compile("[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}");
    static final Pattern HEX = Pattern.compile("\\b(?=[0-9a-fA-F]*[a-fA-F])[0-9a-fA-F]{6,}\\b");
    static final Pattern NUM = Pattern.compile("[0-9]+");

    static final String HELP = String.join("\n",
        "Usage: logsum [FILE] [--since TIMESTAMP] [--level LEVEL] [--top N]",
        "",
        "Summarize a log file (or stdin).",
        "",
        "  FILE               log file to read; omitted or '-' reads stdin",
        "  --since TIMESTAMP  only lines with timestamp >= TIMESTAMP (YYYY-MM-DDTHH:MM:SS)",
        "  --level LEVEL      only lines at LEVEL (DEBUG, INFO, WARN, ERROR)",
        "  --top N            number of top error groups to show (default 5)",
        "  --help             show this message") + "\n";

    static boolean validTs(String s) {
        if (!TS.matcher(s).matches()) return false;
        try {
            LocalDateTime.parse(s, DateTimeFormatter.ISO_LOCAL_DATE_TIME);
            return true;
        } catch (Exception e) {
            return false;
        }
    }

    static int usage(String msg) {
        System.err.print(msg + "\n");
        System.err.flush();
        return 2;
    }

    static String stripEnd(String s) {
        int e = s.length();
        while (e > 0 && Character.isWhitespace(s.charAt(e - 1))) e--;
        return s.substring(0, e);
    }

    public static void main(String[] args) {
        System.exit(run(args));
    }

    static int run(String[] args) {
        String file = null, since = null, level = null;
        int top = 5;
        boolean haveFile = false;
        for (int i = 0; i < args.length; i++) {
            String a = args[i];
            if (a.equals("--help") || a.equals("-h")) {
                System.out.print(HELP);
                System.out.flush();
                return 0;
            }
            if (a.startsWith("--")) {
                String name = a, val = null;
                int eq = a.indexOf('=');
                if (eq > 0) { name = a.substring(0, eq); val = a.substring(eq + 1); }
                if (!name.equals("--since") && !name.equals("--level") && !name.equals("--top"))
                    return usage("unknown flag: " + a);
                if (val == null) {
                    if (i + 1 >= args.length) return usage("error: missing value for " + name);
                    val = args[++i];
                }
                switch (name) {
                    case "--since":
                        if (!validTs(val)) return usage("error: invalid value for --since: " + val);
                        since = val;
                        break;
                    case "--level":
                        if (!Arrays.asList(LEVELS).contains(val))
                            return usage("error: invalid value for --level: " + val);
                        level = val;
                        break;
                    default:
                        try {
                            top = Integer.parseInt(val);
                            if (top < 0) throw new NumberFormatException();
                        } catch (NumberFormatException e) {
                            return usage("error: invalid value for --top: " + val);
                        }
                }
            } else if (a.startsWith("-") && !a.equals("-")) {
                return usage("unknown flag: " + a);
            } else {
                if (haveFile) return usage("error: too many arguments");
                haveFile = true;
                file = a;
            }
        }

        InputStream in;
        if (file == null || file.equals("-")) {
            in = System.in;
        } else {
            File f = new File(file);
            if (!f.isFile()) return usage("error: cannot read " + file);
            try {
                in = new FileInputStream(f);
            } catch (IOException e) {
                return usage("error: cannot read " + file);
            }
        }

        long total = 0;
        long[] counts = new long[4];
        String first = null, last = null;
        Map<String, String> display = new HashMap<>();
        Map<String, Long> groups = new HashMap<>();

        try (BufferedReader r = new BufferedReader(new InputStreamReader(in, StandardCharsets.UTF_8), 1 << 16)) {
            String line;
            while ((line = r.readLine()) != null) {
                int s1 = line.indexOf(' ');
                if (s1 != 19) continue;
                int s2 = line.indexOf(' ', s1 + 1);
                if (s2 < 0) continue;
                String ts = line.substring(0, s1);
                String lv = line.substring(s1 + 1, s2);
                String msg = line.substring(s2 + 1);
                int li = -1;
                for (int k = 0; k < 4; k++) if (LEVELS[k].equals(lv)) li = k;
                if (li < 0) continue;
                if (stripEnd(msg).isEmpty() || Character.isWhitespace(msg.charAt(0)) && stripEnd(msg).isEmpty()) continue;
                if (!validTs(ts)) continue;
                if (since != null && ts.compareTo(since) < 0) continue;
                if (level != null && !level.equals(lv)) continue;
                total++;
                counts[li]++;
                if (first == null) first = ts;
                last = ts;
                if (li == 3) {
                    String n = NUM.matcher(HEX.matcher(UUID.matcher(msg).replaceAll("<uuid>"))
                        .replaceAll("<hex>")).replaceAll("<num>");
                    groups.merge(n, 1L, Long::sum);
                    display.putIfAbsent(n, stripEnd(msg));
                }
            }
        } catch (IOException e) {
            return usage("error: cannot read " + (file == null ? "-" : file));
        }

        List<String> keys = new ArrayList<>(groups.keySet());
        keys.sort((x, y) -> {
            int c = Long.compare(groups.get(y), groups.get(x));
            return c != 0 ? c : display.get(x).compareTo(display.get(y));
        });

        StringBuilder sb = new StringBuilder();
        sb.append("total: ").append(total).append('\n');
        sb.append("by level:");
        for (int k = 0; k < 4; k++) sb.append(' ').append(LEVELS[k]).append('=').append(counts[k]);
        sb.append('\n');
        sb.append("first: ").append(first == null ? "n/a" : first).append('\n');
        sb.append("last: ").append(last == null ? "n/a" : last).append('\n');
        sb.append("top errors:\n");
        for (int k = 0; k < Math.min(top, keys.size()); k++) {
            String key = keys.get(k);
            sb.append("  ").append(groups.get(key)).append(' ').append(display.get(key)).append('\n');
        }
        PrintStream out = new PrintStream(new FileOutputStream(FileDescriptor.out), false, StandardCharsets.UTF_8);
        out.print(sb);
        out.flush();
        return 0;
    }
}
