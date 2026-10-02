import java.io.*;
import java.nio.charset.StandardCharsets;
import java.time.LocalDateTime;
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
        "  --help             show this message",
        "");

    static final class Group {
        final String first;
        long count;
        Group(String first) { this.first = first; }
    }

    static boolean validTs(String s) {
        if (!TS.matcher(s).matches()) return false;
        try {
            LocalDateTime.of(Integer.parseInt(s.substring(0, 4)), Integer.parseInt(s.substring(5, 7)),
                Integer.parseInt(s.substring(8, 10)), Integer.parseInt(s.substring(11, 13)),
                Integer.parseInt(s.substring(14, 16)), Integer.parseInt(s.substring(17, 19)));
            return true;
        } catch (RuntimeException e) {
            return false;
        }
    }

    static int levelIndex(String s) {
        for (int i = 0; i < LEVELS.length; i++) if (LEVELS[i].equals(s)) return i;
        return -1;
    }

    static int fail(String msg) {
        System.err.print(msg + "\n");
        System.err.flush();
        return 2;
    }

    static String normalize(String m) {
        m = UUID.matcher(m).replaceAll("<uuid>");
        m = HEX.matcher(m).replaceAll("<hex>");
        return NUM.matcher(m).replaceAll("<num>");
    }

    static boolean blank(String s) {
        for (int i = 0; i < s.length(); i++) if (!Character.isWhitespace(s.charAt(i))) return false;
        return true;
    }

    static String rtrim(String s) {
        int e = s.length();
        while (e > 0 && Character.isWhitespace(s.charAt(e - 1))) e--;
        return s.substring(0, e);
    }

    // Reads a line terminated by LF only; returns null at EOF.
    static String readLine(Reader r, StringBuilder sb) throws IOException {
        sb.setLength(0);
        int c;
        boolean any = false;
        while ((c = r.read()) != -1) {
            any = true;
            if (c == '\n') return sb.toString();
            sb.append((char) c);
        }
        return any ? sb.toString() : null;
    }

    public static void main(String[] args) throws Exception {
        System.exit(run(args));
    }

    static int run(String[] args) throws Exception {
        String file = null, since = null, level = null;
        int top = 5;
        boolean haveFile = false;
        for (int i = 0; i < args.length; i++) {
            String a = args[i];
            if (a.equals("--help") || a.equals("-h")) {
                PrintStream out = new PrintStream(new FileOutputStream(FileDescriptor.out), false, StandardCharsets.UTF_8);
                out.print(HELP);
                out.flush();
                return 0;
            }
            if (a.startsWith("--")) {
                String name = a, val = null;
                int eq = a.indexOf('=');
                if (eq > 0) { name = a.substring(0, eq); val = a.substring(eq + 1); }
                if (!name.equals("--since") && !name.equals("--level") && !name.equals("--top"))
                    return fail("unknown flag: " + a);
                if (val == null) {
                    if (i + 1 >= args.length) return fail("error: missing value for " + name);
                    val = args[++i];
                }
                switch (name) {
                    case "--since":
                        if (!validTs(val)) return fail("error: invalid value for --since: " + val);
                        since = val;
                        break;
                    case "--level":
                        if (levelIndex(val) < 0) return fail("error: invalid value for --level: " + val);
                        level = val;
                        break;
                    default:
                        try {
                            top = Integer.parseInt(val);
                            if (top < 0) throw new NumberFormatException();
                        } catch (NumberFormatException e) {
                            return fail("error: invalid value for --top: " + val);
                        }
                }
            } else if (a.startsWith("-") && !a.equals("-")) {
                return fail("unknown flag: " + a);
            } else {
                if (haveFile) return fail("error: unexpected argument: " + a);
                haveFile = true;
                file = a;
            }
        }

        InputStream in;
        if (file == null || file.equals("-")) {
            in = System.in;
        } else {
            File f = new File(file);
            if (f.isDirectory()) return fail("error: cannot read " + file);
            try {
                in = new FileInputStream(f);
            } catch (IOException e) {
                return fail("error: cannot read " + file);
            }
        }

        long total = 0;
        long[] counts = new long[4];
        String first = null, last = null;
        Map<String, Group> groups = new HashMap<>();

        try (Reader r = new BufferedReader(new InputStreamReader(in, StandardCharsets.UTF_8), 1 << 16)) {
            StringBuilder sb = new StringBuilder();
            String line;
            while ((line = readLine(r, sb)) != null) {
                if (line.endsWith("\r")) line = line.substring(0, line.length() - 1);
                if (line.length() < 22 || line.charAt(19) != ' ') continue;
                String ts = line.substring(0, 19);
                if (!validTs(ts)) continue;
                int sp = line.indexOf(' ', 20);
                if (sp < 0) continue;
                int li = levelIndex(line.substring(20, sp));
                if (li < 0) continue;
                String msg = line.substring(sp + 1);
                if (blank(msg)) continue;
                msg = rtrim(msg);
                if (since != null && ts.compareTo(since) < 0) continue;
                if (level != null && !LEVELS[li].equals(level)) continue;
                total++;
                counts[li]++;
                if (first == null) first = ts;
                last = ts;
                if (li == 3) {
                    String key = normalize(msg);
                    Group g = groups.get(key);
                    if (g == null) groups.put(key, g = new Group(msg));
                    g.count++;
                }
            }
        } catch (IOException e) {
            return fail("error: cannot read " + (file == null ? "-" : file));
        }

        List<Group> sorted = new ArrayList<>(groups.values());
        sorted.sort((x, y) -> {
            if (x.count != y.count) return Long.compare(y.count, x.count);
            return cmp(x.first, y.first);
        });

        StringBuilder o = new StringBuilder();
        o.append("total: ").append(total).append('\n');
        o.append("by level:");
        for (int i = 0; i < 4; i++) o.append(' ').append(LEVELS[i]).append('=').append(counts[i]);
        o.append('\n');
        o.append("first: ").append(first == null ? "n/a" : first).append('\n');
        o.append("last: ").append(last == null ? "n/a" : last).append('\n');
        o.append("top errors:\n");
        for (int i = 0; i < Math.min(top, sorted.size()); i++)
            o.append("  ").append(sorted.get(i).count).append(' ').append(sorted.get(i).first).append('\n');
        PrintStream out = new PrintStream(new FileOutputStream(FileDescriptor.out), false, StandardCharsets.UTF_8);
        out.print(o);
        out.flush();
        return 0;
    }

    // Code point order (equals UTF-8 byte order).
    static int cmp(String a, String b) {
        int i = 0, j = 0;
        while (i < a.length() && j < b.length()) {
            int ca = a.codePointAt(i), cb = b.codePointAt(j);
            if (ca != cb) return Integer.compare(ca, cb);
            i += Character.charCount(ca);
            j += Character.charCount(cb);
        }
        return Integer.compare(a.length() - i, b.length() - j);
    }
}
