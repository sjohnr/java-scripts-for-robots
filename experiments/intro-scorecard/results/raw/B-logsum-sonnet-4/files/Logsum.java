import java.io.*;
import java.nio.charset.*;
import java.nio.file.*;
import java.time.LocalDateTime;
import java.time.format.DateTimeParseException;
import java.util.*;
import java.util.regex.*;

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
        "  FILE               log file to read; omitted or - means stdin",
        "  --since TIMESTAMP  only lines with timestamp >= TIMESTAMP (YYYY-MM-DDTHH:MM:SS)",
        "  --level LEVEL      only lines at exactly LEVEL (DEBUG, INFO, WARN, ERROR)",
        "  --top N            number of top error groups to show (default 5)",
        "  --help             show this message") + "\n";

    static boolean validTs(String s) {
        if (!TS.matcher(s).matches()) return false;
        try { LocalDateTime.parse(s); return true; } catch (DateTimeParseException e) { return false; }
    }

    static int usage(String msg) {
        System.err.print(msg + "\n");
        System.err.flush();
        return 2;
    }

    public static void main(String[] args) {
        System.exit(run(args));
    }

    static int run(String[] args) {
        for (String a : args) if (a.equals("--help")) {
            System.out.print(HELP);
            System.out.flush();
            return 0;
        }
        String file = null, since = null, level = null;
        int top = 5;
        boolean fileSet = false;
        for (int i = 0; i < args.length; i++) {
            String a = args[i];
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
                        since = val; break;
                    case "--level":
                        if (!Arrays.asList(LEVELS).contains(val)) return usage("error: invalid value for --level: " + val);
                        level = val; break;
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
                if (fileSet) return usage("error: too many arguments");
                fileSet = true;
                file = a;
            }
        }

        InputStream in;
        if (file == null || file.equals("-")) {
            in = System.in;
        } else {
            try {
                if (Files.isDirectory(Paths.get(file))) return usage("error: cannot read " + file);
                in = new FileInputStream(file);
            } catch (IOException | InvalidPathException e) {
                return usage("error: cannot read " + file);
            }
        }

        long total = 0;
        long[] counts = new long[4];
        String first = null, last = null;
        Map<String, Object[]> groups = new HashMap<>(); // normalized -> {count(Long), original}

        try (Reader r = new BufferedReader(new InputStreamReader(in, StandardCharsets.UTF_8), 1 << 16)) {
            StringBuilder sb = new StringBuilder();
            int c;
            while ((c = r.read()) != -1) {
                if (c == '\n') {
                    String line = sb.toString();
                    sb.setLength(0);
                    // inline processing
                    int[] lv = new int[1];
                    String[] parsed = parse(line, lv);
                    if (parsed != null) {
                        String ts = parsed[0];
                        if ((since == null || ts.compareTo(since) >= 0)
                            && (level == null || level.equals(LEVELS[lv[0]]))) {
                            total++;
                            counts[lv[0]]++;
                            if (first == null) first = ts;
                            last = ts;
                            if (lv[0] == 3) record(groups, parsed[1]);
                        }
                    }
                } else sb.append((char) c);
            }
            if (sb.length() > 0) {
                int[] lv = new int[1];
                String[] parsed = parse(sb.toString(), lv);
                if (parsed != null) {
                    String ts = parsed[0];
                    if ((since == null || ts.compareTo(since) >= 0)
                        && (level == null || level.equals(LEVELS[lv[0]]))) {
                        total++;
                        counts[lv[0]]++;
                        if (first == null) first = ts;
                        last = ts;
                        if (lv[0] == 3) record(groups, parsed[1]);
                    }
                }
            }
        } catch (IOException e) {
            return usage("error: cannot read " + (file == null ? "-" : file));
        }

        List<Object[]> list = new ArrayList<>(groups.values());
        list.sort((x, y) -> {
            int cmp = Long.compare((Long) y[0], (Long) x[0]);
            return cmp != 0 ? cmp : ((String) x[1]).compareTo((String) y[1]);
        });

        StringBuilder out = new StringBuilder();
        out.append("total: ").append(total).append('\n');
        out.append("by level:");
        for (int i = 0; i < 4; i++) out.append(' ').append(LEVELS[i]).append('=').append(counts[i]);
        out.append('\n');
        out.append("first: ").append(first == null ? "n/a" : first).append('\n');
        out.append("last: ").append(last == null ? "n/a" : last).append('\n');
        out.append("top errors:\n");
        for (int i = 0; i < Math.min(top, list.size()); i++)
            out.append("  ").append(list.get(i)[0]).append(' ').append(list.get(i)[1]).append('\n');
        PrintStream ps = new PrintStream(new FileOutputStream(FileDescriptor.out), false, StandardCharsets.UTF_8);
        ps.print(out);
        ps.flush();
        return 0;
    }

    static void record(Map<String, Object[]> groups, String msg) {
        String n = UUID.matcher(msg).replaceAll("<uuid>");
        n = HEX.matcher(n).replaceAll("<hex>");
        n = NUM.matcher(n).replaceAll("<num>");
        Object[] g = groups.get(n);
        if (g == null) groups.put(n, new Object[]{1L, msg});
        else g[0] = (Long) g[0] + 1;
    }

    /** Returns {timestamp, message} and sets lv[0] to level index, or null if line is not valid. */
    static String[] parse(String line, int[] lv) {
        int end = line.length();
        while (end > 0 && Character.isWhitespace(line.charAt(end - 1))) end--;
        line = line.substring(0, end);
        int s1 = line.indexOf(' ');
        if (s1 != 19) return null;
        int s2 = line.indexOf(' ', s1 + 1);
        if (s2 < 0) return null;
        String ts = line.substring(0, s1);
        if (!validTs(ts)) return null;
        String lvl = line.substring(s1 + 1, s2);
        int idx = -1;
        for (int i = 0; i < 4; i++) if (LEVELS[i].equals(lvl)) idx = i;
        if (idx < 0) return null;
        String msg = line.substring(s2 + 1);
        if (msg.isEmpty()) return null;
        lv[0] = idx;
        return new String[]{ts, msg};
    }
}
