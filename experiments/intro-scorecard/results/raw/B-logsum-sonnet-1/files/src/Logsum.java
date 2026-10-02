import java.io.*;
import java.nio.ByteBuffer;
import java.nio.CharBuffer;
import java.nio.charset.*;
import java.nio.file.*;
import java.time.LocalDateTime;
import java.time.format.DateTimeParseException;
import java.util.*;
import java.util.regex.Pattern;

public class Logsum {
    static final String[] LEVELS = {"DEBUG", "INFO", "WARN", "ERROR"};
    static final Pattern TS = Pattern.compile("\\d{4}-\\d{2}-\\d{2}T\\d{2}:\\d{2}:\\d{2}");
    static final Pattern UUID = Pattern.compile("[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}");
    static final Pattern HEX = Pattern.compile("\\b(?=[0-9a-fA-F]*[a-fA-F])[0-9a-fA-F]{6,}\\b");
    static final Pattern DIGITS = Pattern.compile("[0-9]+");

    static final String HELP = String.join("\n",
        "Usage: logsum [FILE] [--since TIMESTAMP] [--level LEVEL] [--top N]",
        "",
        "Summarize a log file (or stdin).",
        "",
        "Arguments:",
        "  FILE               log file to read; omitted or - means stdin",
        "",
        "Options:",
        "  --since TIMESTAMP  only lines with timestamp >= TIMESTAMP (YYYY-MM-DDTHH:MM:SS)",
        "  --level LEVEL      only lines at exactly LEVEL (DEBUG, INFO, WARN, ERROR)",
        "  --top N            number of top error groups to show (default 5)",
        "  --help             show this message",
        "");

    static boolean validTs(String s) {
        if (!TS.matcher(s).matches()) return false;
        try { LocalDateTime.parse(s); return true; } catch (DateTimeParseException e) { return false; }
    }

    static int usage(String msg) {
        System.err.print(msg + "\n");
        return 2;
    }

    public static void main(String[] args) {
        System.exit(run(args));
    }

    static int run(String[] args) {
        String file = null, since = null, level = null;
        int top = 5;
        boolean fileSet = false;
        for (int i = 0; i < args.length; i++) {
            String a = args[i];
            if (a.equals("--help") || a.equals("-h")) {
                PrintStream out = new PrintStream(new FileOutputStream(FileDescriptor.out), false, StandardCharsets.UTF_8);
                out.print(HELP);
                out.flush();
                return 0;
            }
            if (a.startsWith("--") || (a.startsWith("-") && a.length() > 1)) {
                String name = a, val = null;
                int eq = a.indexOf('=');
                if (a.startsWith("--") && eq > 0) { name = a.substring(0, eq); val = a.substring(eq + 1); }
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
            } else {
                if (fileSet) return usage("error: unexpected argument: " + a);
                file = a; fileSet = true;
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
        Map<String, long[]> groupCount = new HashMap<>();
        Map<String, String> groupMsg = new HashMap<>();

        try (BufferedReader r = new BufferedReader(new InputStreamReader(in,
                StandardCharsets.UTF_8.newDecoder()
                    .onMalformedInput(CodingErrorAction.REPLACE)
                    .onUnmappableCharacter(CodingErrorAction.REPLACE)), 1 << 16)) {
            StringBuilder sb = new StringBuilder();
            int c;
            while (true) {
                c = r.read();
                if (c == '\n' || c < 0) {
                    if (c < 0 && sb.length() == 0) break;
                    int len = sb.length();
                    if (len > 0 && sb.charAt(len - 1) == '\r') sb.setLength(len - 1);
                    String line = sb.toString();
                    sb.setLength(0);
                    // process
                    if (line.length() >= 22 && line.charAt(19) == ' ') {
                        String ts = line.substring(0, 19);
                        int sp = line.indexOf(' ', 20);
                        if (sp > 0 && validTs(ts)) {
                            String lv = line.substring(20, sp);
                            int li = Arrays.asList(LEVELS).indexOf(lv);
                            String msg = line.substring(sp + 1);
                            if (li >= 0 && !msg.isBlank()
                                    && (since == null || ts.compareTo(since) >= 0)
                                    && (level == null || level.equals(lv))) {
                                total++;
                                counts[li]++;
                                if (first == null) first = ts;
                                last = ts;
                                if (li == 3) {
                                    String n = UUID.matcher(msg).replaceAll("<uuid>");
                                    n = HEX.matcher(n).replaceAll("<hex>");
                                    n = DIGITS.matcher(n).replaceAll("<num>");
                                    long[] g = groupCount.get(n);
                                    if (g == null) {
                                        groupCount.put(n, new long[]{1});
                                        groupMsg.put(n, msg.stripTrailing());
                                    } else g[0]++;
                                }
                            }
                        }
                    }
                    if (c < 0) break;
                } else {
                    sb.append((char) c);
                }
            }
        } catch (IOException e) {
            return usage("error: cannot read " + (file == null ? "-" : file));
        }

        List<Map.Entry<String, long[]>> groups = new ArrayList<>(groupCount.entrySet());
        groups.sort((x, y) -> {
            int d = Long.compare(y.getValue()[0], x.getValue()[0]);
            return d != 0 ? d : groupMsg.get(x.getKey()).compareTo(groupMsg.get(y.getKey()));
        });

        StringBuilder o = new StringBuilder();
        o.append("total: ").append(total).append('\n');
        o.append("by level: DEBUG=").append(counts[0]).append(" INFO=").append(counts[1])
         .append(" WARN=").append(counts[2]).append(" ERROR=").append(counts[3]).append('\n');
        o.append("first: ").append(first == null ? "n/a" : first).append('\n');
        o.append("last: ").append(last == null ? "n/a" : last).append('\n');
        o.append("top errors:\n");
        for (int i = 0; i < Math.min(top, groups.size()); i++) {
            Map.Entry<String, long[]> e = groups.get(i);
            o.append("  ").append(e.getValue()[0]).append(' ').append(groupMsg.get(e.getKey())).append('\n');
        }
        PrintStream out = new PrintStream(new FileOutputStream(FileDescriptor.out), false, StandardCharsets.UTF_8);
        out.print(o);
        out.flush();
        return 0;
    }
}
