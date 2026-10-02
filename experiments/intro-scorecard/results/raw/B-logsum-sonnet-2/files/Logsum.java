import java.io.*;
import java.nio.charset.*;
import java.nio.file.*;
import java.time.LocalDateTime;
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
        "  --help             show this message",
        "");

    static boolean validTs(String s) {
        if (!TS.matcher(s).matches()) return false;
        try { LocalDateTime.parse(s); return true; } catch (Exception e) { return false; }
    }

    static boolean validLevel(String s) {
        for (String l : LEVELS) if (l.equals(s)) return true;
        return false;
    }

    static int usage(String msg) {
        System.err.print(msg + "\n");
        System.err.flush();
        return 2;
    }

    public static void main(String[] args) {
        int code = run(args);
        System.out.flush();
        System.exit(code);
    }

    static int run(String[] args) {
        PrintStream out = new PrintStream(new FileOutputStream(FileDescriptor.out), false, StandardCharsets.UTF_8);
        System.setOut(out);
        System.setErr(new PrintStream(new FileOutputStream(FileDescriptor.err), false, StandardCharsets.UTF_8));

        for (String a : args) {
            if (a.equals("--help")) { out.print(HELP); out.flush(); return 0; }
        }

        String file = null, since = null, level = null, topStr = null;
        boolean fileSet = false;
        for (int i = 0; i < args.length; i++) {
            String a = args[i];
            if (a.equals("--since") || a.equals("--level") || a.equals("--top")
                    || a.startsWith("--since=") || a.startsWith("--level=") || a.startsWith("--top=")) {
                String name, val;
                int eq = a.indexOf('=');
                if (eq >= 0) { name = a.substring(0, eq); val = a.substring(eq + 1); }
                else {
                    name = a;
                    if (i + 1 >= args.length) return usage("error: missing value for " + name);
                    val = args[++i];
                }
                switch (name) {
                    case "--since": since = val; break;
                    case "--level": level = val; break;
                    default: topStr = val;
                }
            } else if (a.equals("-") || !a.startsWith("-")) {
                if (fileSet) return usage("error: unexpected argument: " + a);
                fileSet = true;
                file = a;
            } else {
                return usage("unknown flag: " + a);
            }
        }

        if (since != null && !validTs(since)) return usage("error: invalid value for --since: " + since);
        if (level != null && !validLevel(level)) return usage("error: invalid value for --level: " + level);
        int top = 5;
        if (topStr != null) {
            try {
                top = Integer.parseInt(topStr);
                if (top < 0) throw new NumberFormatException();
            } catch (NumberFormatException e) {
                return usage("error: invalid value for --top: " + topStr);
            }
        }

        InputStream in;
        if (file == null || file.equals("-")) in = System.in;
        else {
            try {
                if (Files.isDirectory(Paths.get(file))) throw new IOException();
                in = new FileInputStream(file);
            } catch (Exception e) {
                return usage("error: cannot read " + file);
            }
        }

        long total = 0;
        long[] counts = new long[4];
        String first = null, last = null;
        Map<String, Object[]> groups = new HashMap<>(); // key -> {count(Long), first message}

        try (BufferedReader r = new BufferedReader(new InputStreamReader(in, StandardCharsets.UTF_8), 1 << 16)) {
            String line;
            while ((line = r.readLine()) != null) {
                if (line.length() < 22) continue;
                String ts = line.substring(0, 19);
                if (line.charAt(19) != ' ' || !validTs(ts)) continue;
                int sp = line.indexOf(' ', 20);
                if (sp < 0) continue;
                String lv = line.substring(20, sp);
                int li = -1;
                for (int k = 0; k < 4; k++) if (LEVELS[k].equals(lv)) li = k;
                if (li < 0) continue;
                String msg = line.substring(sp + 1);
                if (msg.isBlank()) continue; // fewer than 3 tokens
                if (since != null && ts.compareTo(since) < 0) continue;
                if (level != null && !level.equals(lv)) continue;

                total++;
                counts[li]++;
                if (first == null) first = ts;
                last = ts;
                if (li == 3) {
                    String key = NUM.matcher(HEX.matcher(UUID.matcher(msg).replaceAll("<uuid>"))
                            .replaceAll("<hex>")).replaceAll("<num>");
                    Object[] g = groups.get(key);
                    if (g == null) groups.put(key, new Object[]{1L, msg});
                    else g[0] = (Long) g[0] + 1;
                }
            }
        } catch (IOException e) {
            return usage("error: cannot read " + (file == null ? "-" : file));
        }

        StringBuilder sb = new StringBuilder();
        sb.append("total: ").append(total).append('\n');
        sb.append("by level:");
        for (int k = 0; k < 4; k++) sb.append(' ').append(LEVELS[k]).append('=').append(counts[k]);
        sb.append('\n');
        sb.append("first: ").append(first == null ? "n/a" : first).append('\n');
        sb.append("last: ").append(last == null ? "n/a" : last).append('\n');
        sb.append("top errors:\n");

        List<Object[]> list = new ArrayList<>();
        for (Object[] g : groups.values()) list.add(new Object[]{g[0], stripEnd((String) g[1])});
        list.sort((a, b) -> {
            int c = Long.compare((Long) b[0], (Long) a[0]);
            return c != 0 ? c : ((String) a[1]).compareTo((String) b[1]);
        });
        for (int k = 0; k < list.size() && k < top; k++)
            sb.append("  ").append(list.get(k)[0]).append(' ').append(list.get(k)[1]).append('\n');

        out.print(sb);
        out.flush();
        return 0;
    }

    static String stripEnd(String s) {
        int e = s.length();
        while (e > 0 && Character.isWhitespace(s.charAt(e - 1))) e--;
        return s.substring(0, e);
    }
}
