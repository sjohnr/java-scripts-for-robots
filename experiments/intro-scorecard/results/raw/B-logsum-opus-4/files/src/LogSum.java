import java.io.ByteArrayOutputStream;
import java.io.FileInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;
import java.time.format.ResolverStyle;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** logsum: streaming summary of a log file (or stdin). See SPEC.md. */
public final class LogSum {

    private static final String[] LEVELS = {"DEBUG", "INFO", "WARN", "ERROR"};

    private static final Pattern TS_SHAPE = Pattern.compile("\\d{4}-\\d{2}-\\d{2}T\\d{2}:\\d{2}:\\d{2}");
    private static final DateTimeFormatter TS_FORMAT =
            DateTimeFormatter.ofPattern("uuuu-MM-dd'T'HH:mm:ss").withResolverStyle(ResolverStyle.STRICT);

    private static final Pattern LINE = Pattern.compile("^\\s*(\\S+)\\s+(\\S+)\\s+(\\S.*?)\\s*$");

    private static final Pattern UUID = Pattern.compile(
            "[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}");
    private static final Pattern HEX = Pattern.compile("\\b(?=[0-9a-fA-F]*[a-fA-F])[0-9a-fA-F]{6,}\\b");
    private static final Pattern NUM = Pattern.compile("[0-9]+");

    private static final String HELP = String.join("\n",
            "Usage: logsum [FILE] [--since TIMESTAMP] [--level LEVEL] [--top N]",
            "",
            "Summarize a log file whose lines look like: TIMESTAMP LEVEL MESSAGE",
            "",
            "Arguments:",
            "  FILE                 log file to read; omitted or '-' reads stdin",
            "",
            "Options:",
            "  --since TIMESTAMP    only consider lines at or after TIMESTAMP (YYYY-MM-DDTHH:MM:SS)",
            "  --level LEVEL        only consider lines at LEVEL (DEBUG, INFO, WARN, ERROR)",
            "  --top N              number of top error groups to show (default 5)",
            "  --help               show this help and exit",
            "");

    private static final class UsageError extends Exception {
        UsageError(String message) {
            super(message);
        }
    }

    private static final class Group {
        final String firstMessage;
        long count;

        Group(String firstMessage) {
            this.firstMessage = firstMessage;
        }
    }

    private LogSum() {}

    public static void main(String[] args) {
        PrintStream out = new PrintStream(new java.io.FileOutputStream(java.io.FileDescriptor.out), false, StandardCharsets.UTF_8);
        PrintStream err = new PrintStream(new java.io.FileOutputStream(java.io.FileDescriptor.err), true, StandardCharsets.UTF_8);
        int code;
        try {
            code = run(args, out);
        } catch (UsageError e) {
            err.print(e.getMessage() + "\n");
            code = 2;
        }
        out.flush();
        err.flush();
        System.exit(code);
    }

    private static int run(String[] args, PrintStream out) throws UsageError {
        String file = null;
        boolean fileSeen = false;
        String since = null;
        String level = null;
        int top = 5;

        for (int i = 0; i < args.length; i++) {
            String arg = args[i];
            if (arg.equals("--help")) {
                out.print(HELP);
                return 0;
            }
            if (arg.startsWith("-") && !arg.equals("-")) {
                String name = arg;
                String value = null;
                int eq = arg.indexOf('=');
                if (arg.startsWith("--") && eq > 0) {
                    name = arg.substring(0, eq);
                    value = arg.substring(eq + 1);
                }
                if (!name.equals("--since") && !name.equals("--level") && !name.equals("--top")) {
                    throw new UsageError("unknown flag: " + arg);
                }
                if (value == null) {
                    if (i + 1 >= args.length) {
                        throw new UsageError("error: missing value for " + name);
                    }
                    value = args[++i];
                }
                switch (name) {
                    case "--since" -> {
                        if (!isValidTimestamp(value)) {
                            throw new UsageError("error: invalid value for --since: " + value);
                        }
                        since = value;
                    }
                    case "--level" -> {
                        if (levelIndex(value) < 0) {
                            throw new UsageError("error: invalid value for --level: " + value);
                        }
                        level = value;
                    }
                    default -> top = parseTop(value);
                }
            } else {
                if (fileSeen) {
                    throw new UsageError("error: unexpected argument: " + arg);
                }
                fileSeen = true;
                file = arg;
            }
        }

        InputStream in;
        if (file == null || file.equals("-")) {
            in = System.in;
        } else {
            Path path = Path.of(file);
            if (!Files.isRegularFile(path) || !Files.isReadable(path)) {
                throw new UsageError("error: cannot read " + file);
            }
            try {
                in = new FileInputStream(path.toFile());
            } catch (IOException e) {
                throw new UsageError("error: cannot read " + file);
            }
        }

        long total = 0;
        long[] byLevel = new long[LEVELS.length];
        String first = null;
        String last = null;
        Map<String, Group> groups = new HashMap<>();

        try (in) {
            LineReader reader = new LineReader(in);
            String line;
            while ((line = reader.readLine()) != null) {
                Matcher m = LINE.matcher(line);
                if (!m.matches()) {
                    continue;
                }
                String ts = m.group(1);
                String lvl = m.group(2);
                int li = levelIndex(lvl);
                if (li < 0 || !isValidTimestamp(ts)) {
                    continue;
                }
                if (since != null && ts.compareTo(since) < 0) {
                    continue;
                }
                if (level != null && !level.equals(lvl)) {
                    continue;
                }
                total++;
                byLevel[li]++;
                if (first == null) {
                    first = ts;
                }
                last = ts;
                if (li == 3) {
                    String message = m.group(3);
                    groups.computeIfAbsent(normalize(message), k -> new Group(message)).count++;
                }
            }
        } catch (IOException e) {
            throw new UsageError("error: cannot read " + (file == null ? "-" : file));
        }

        List<Group> sorted = new ArrayList<>(groups.values());
        sorted.sort((a, b) -> {
            int c = Long.compare(b.count, a.count);
            return c != 0 ? c : compareCodePoints(a.firstMessage, b.firstMessage);
        });

        StringBuilder sb = new StringBuilder();
        sb.append("total: ").append(total).append('\n');
        sb.append("by level:");
        for (int i = 0; i < LEVELS.length; i++) {
            sb.append(' ').append(LEVELS[i]).append('=').append(byLevel[i]);
        }
        sb.append('\n');
        sb.append("first: ").append(first == null ? "n/a" : first).append('\n');
        sb.append("last: ").append(last == null ? "n/a" : last).append('\n');
        sb.append("top errors:\n");
        for (int i = 0; i < sorted.size() && i < top; i++) {
            Group g = sorted.get(i);
            sb.append("  ").append(g.count).append(' ').append(g.firstMessage).append('\n');
        }
        out.print(sb);
        return 0;
    }

    private static int parseTop(String value) throws UsageError {
        if (!value.matches("[0-9]+")) {
            throw new UsageError("error: invalid value for --top: " + value);
        }
        try {
            return Integer.parseInt(value);
        } catch (NumberFormatException e) {
            return Integer.MAX_VALUE;
        }
    }

    private static int levelIndex(String level) {
        for (int i = 0; i < LEVELS.length; i++) {
            if (LEVELS[i].equals(level)) {
                return i;
            }
        }
        return -1;
    }

    private static boolean isValidTimestamp(String ts) {
        if (!TS_SHAPE.matcher(ts).matches()) {
            return false;
        }
        try {
            LocalDateTime.parse(ts, TS_FORMAT);
            return true;
        } catch (DateTimeParseException e) {
            return false;
        }
    }

    static String normalize(String message) {
        String s = UUID.matcher(message).replaceAll("<uuid>");
        s = HEX.matcher(s).replaceAll("<hex>");
        return NUM.matcher(s).replaceAll("<num>");
    }

    private static int compareCodePoints(String a, String b) {
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
        return Boolean.compare(i < a.length(), j < b.length());
    }

    /** Splits a UTF-8 byte stream on LF only, dropping a trailing CR from each line. */
    private static final class LineReader {
        private final InputStream in;
        private final byte[] buf = new byte[64 * 1024];
        private int pos;
        private int len;
        private final ByteArrayOutputStream line = new ByteArrayOutputStream();

        LineReader(InputStream in) {
            this.in = in;
        }

        String readLine() throws IOException {
            line.reset();
            boolean any = false;
            while (true) {
                if (pos >= len) {
                    len = in.read(buf);
                    pos = 0;
                    if (len <= 0) {
                        len = 0;
                        return any ? decode() : null;
                    }
                }
                any = true;
                int start = pos;
                while (pos < len && buf[pos] != '\n') {
                    pos++;
                }
                line.write(buf, start, pos - start);
                if (pos < len) {
                    pos++;
                    return decode();
                }
            }
        }

        private String decode() {
            byte[] bytes = line.toByteArray();
            int n = bytes.length;
            if (n > 0 && bytes[n - 1] == '\r') {
                n--;
            }
            return new String(bytes, 0, n, StandardCharsets.UTF_8);
        }
    }
}
