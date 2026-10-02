import java.io.BufferedOutputStream;
import java.io.FileInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.PrintStream;
import java.io.Reader;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;
import java.time.format.ResolverStyle;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Pattern;

/** logsum: streaming summary of a log file (or stdin). See SPEC.md. */
public final class Logsum {

    private static final int EXIT_OK = 0;
    private static final int EXIT_USAGE = 2;

    private static final String[] LEVELS = {"DEBUG", "INFO", "WARN", "ERROR"};
    private static final int ERROR_INDEX = 3;

    private static final DateTimeFormatter TS_FORMAT =
            DateTimeFormatter.ofPattern("uuuu-MM-dd'T'HH:mm:ss").withResolverStyle(ResolverStyle.STRICT);
    private static final Pattern TS_SHAPE = Pattern.compile("[0-9]{4}-[0-9]{2}-[0-9]{2}T[0-9]{2}:[0-9]{2}:[0-9]{2}");

    private static final Pattern UUID = Pattern.compile(
            "[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}");
    private static final Pattern HEX = Pattern.compile("\\b(?=[0-9a-fA-F]*[a-fA-F])[0-9a-fA-F]{6,}\\b");
    private static final Pattern NUM = Pattern.compile("[0-9]+");

    private static final String HELP = String.join("\n",
            "usage: logsum [FILE] [--since TIMESTAMP] [--level LEVEL] [--top N]",
            "",
            "Summarize a log file whose lines look like: TIMESTAMP LEVEL MESSAGE",
            "",
            "arguments:",
            "  FILE                 log file to read; omitted or '-' reads stdin",
            "",
            "options:",
            "  --since TIMESTAMP    only consider lines at or after TIMESTAMP (YYYY-MM-DDTHH:MM:SS)",
            "  --level LEVEL        only consider lines at LEVEL (DEBUG, INFO, WARN, ERROR)",
            "  --top N              number of top error groups to show (default 5)",
            "  --help               show this help and exit",
            "");

    /** Signals a usage error: message goes to stderr, exit code 2. */
    private static final class UsageException extends Exception {
        UsageException(String message) {
            super(message);
        }
    }

    private static final class Group {
        final String display;
        long count;

        Group(String display) {
            this.display = display;
        }
    }

    private String file;
    private String since;
    private int levelFilter = -1;
    private int top = 5;

    private final long[] levelCounts = new long[LEVELS.length];
    private long total;
    private String first;
    private String last;
    private final Map<String, Group> groups = new HashMap<>();

    public static void main(String[] args) {
        PrintStream out = new PrintStream(new BufferedOutputStream(System.out, 1 << 16), false, StandardCharsets.UTF_8);
        PrintStream err = new PrintStream(System.err, true, StandardCharsets.UTF_8);
        int code;
        try {
            code = new Logsum().run(args, out);
        } catch (UsageException e) {
            err.print(e.getMessage() + "\n");
            code = EXIT_USAGE;
        }
        out.flush();
        err.flush();
        System.exit(code);
    }

    private int run(String[] args, PrintStream out) throws UsageException {
        if (parseArgs(args)) {
            out.print(HELP);
            return EXIT_OK;
        }
        try (InputStream in = openInput()) {
            process(in);
        } catch (IOException e) {
            throw new UsageException("error: cannot read " + file);
        }
        printSummary(out);
        return EXIT_OK;
    }

    /** Returns true if --help was requested. */
    private boolean parseArgs(String[] args) throws UsageException {
        String sinceRaw = null;
        String levelRaw = null;
        String topRaw = null;
        boolean endOfOptions = false;
        for (int i = 0; i < args.length; i++) {
            String arg = args[i];
            if (endOfOptions || arg.equals("-") || !arg.startsWith("-")) {
                if (file != null) {
                    throw new UsageException("error: unexpected argument: " + arg);
                }
                file = arg;
                continue;
            }
            if (arg.equals("--")) {
                endOfOptions = true;
                continue;
            }
            if (arg.equals("--help")) {
                return true;
            }
            String name = arg;
            String value = null;
            int eq = arg.indexOf('=');
            if (arg.startsWith("--") && eq > 0) {
                name = arg.substring(0, eq);
                value = arg.substring(eq + 1);
            }
            if (!name.equals("--since") && !name.equals("--level") && !name.equals("--top")) {
                throw new UsageException("unknown flag: " + arg);
            }
            if (value == null) {
                if (i + 1 >= args.length) {
                    throw new UsageException("error: missing value for " + name);
                }
                value = args[++i];
            }
            switch (name) {
                case "--since" -> sinceRaw = value;
                case "--level" -> levelRaw = value;
                default -> topRaw = value;
            }
        }

        if (sinceRaw != null) {
            if (!isValidTimestamp(sinceRaw)) {
                throw new UsageException("error: invalid value for --since: " + sinceRaw);
            }
            since = sinceRaw;
        }
        if (levelRaw != null) {
            levelFilter = levelIndex(levelRaw);
            if (levelFilter < 0) {
                throw new UsageException("error: invalid value for --level: " + levelRaw);
            }
        }
        if (topRaw != null) {
            try {
                if (!topRaw.matches("[0-9]+")) {
                    throw new NumberFormatException();
                }
                top = Integer.parseInt(topRaw);
            } catch (NumberFormatException e) {
                throw new UsageException("error: invalid value for --top: " + topRaw);
            }
        }
        return false;
    }

    private InputStream openInput() throws UsageException {
        if (file == null || file.equals("-")) {
            return System.in;
        }
        try {
            Path path = Paths.get(file);
            if (Files.isDirectory(path)) {
                throw new UsageException("error: cannot read " + file);
            }
            return new FileInputStream(path.toFile());
        } catch (IOException | RuntimeException e) {
            throw new UsageException("error: cannot read " + file);
        }
    }

    private void process(InputStream in) throws IOException {
        Reader reader = new InputStreamReader(in, StandardCharsets.UTF_8.newDecoder()
                .onMalformedInput(CodingErrorAction.REPLACE)
                .onUnmappableCharacter(CodingErrorAction.REPLACE));
        char[] buf = new char[1 << 16];
        StringBuilder line = new StringBuilder();
        int n;
        while ((n = reader.read(buf)) > 0) {
            int start = 0;
            for (int i = 0; i < n; i++) {
                if (buf[i] == '\n') {
                    line.append(buf, start, i - start);
                    handleLine(line.toString());
                    line.setLength(0);
                    start = i + 1;
                }
            }
            line.append(buf, start, n - start);
        }
        if (line.length() > 0) {
            handleLine(line.toString());
        }
    }

    private void handleLine(String line) {
        // Tokenize: TIMESTAMP, LEVEL, then MESSAGE = rest of line from the third token's start.
        int len = line.length();
        int i = skipSpace(line, 0);
        int tsStart = i;
        i = skipToken(line, i);
        String ts = line.substring(tsStart, i);
        i = skipSpace(line, i);
        int lvStart = i;
        i = skipToken(line, i);
        String lv = line.substring(lvStart, i);
        i = skipSpace(line, i);
        if (i >= len || ts.isEmpty() || lv.isEmpty()) {
            return;
        }
        int end = len;
        while (end > i && Character.isWhitespace(line.charAt(end - 1))) {
            end--;
        }
        String message = line.substring(i, end);

        int level = levelIndex(lv);
        if (level < 0 || !isValidTimestamp(ts)) {
            return;
        }
        if (since != null && ts.compareTo(since) < 0) {
            return;
        }
        if (levelFilter >= 0 && level != levelFilter) {
            return;
        }

        total++;
        levelCounts[level]++;
        if (first == null) {
            first = ts;
        }
        last = ts;

        if (level == ERROR_INDEX) {
            String key = normalize(message);
            groups.computeIfAbsent(key, k -> new Group(message)).count++;
        }
    }

    private static int skipSpace(String s, int i) {
        while (i < s.length() && Character.isWhitespace(s.charAt(i))) {
            i++;
        }
        return i;
    }

    private static int skipToken(String s, int i) {
        while (i < s.length() && !Character.isWhitespace(s.charAt(i))) {
            i++;
        }
        return i;
    }

    static String normalize(String message) {
        String s = UUID.matcher(message).replaceAll("<uuid>");
        s = HEX.matcher(s).replaceAll("<hex>");
        return NUM.matcher(s).replaceAll("<num>");
    }

    private static boolean isValidTimestamp(String s) {
        if (!TS_SHAPE.matcher(s).matches()) {
            return false;
        }
        try {
            LocalDateTime.parse(s, TS_FORMAT);
            return true;
        } catch (DateTimeParseException e) {
            return false;
        }
    }

    private static int levelIndex(String s) {
        for (int i = 0; i < LEVELS.length; i++) {
            if (LEVELS[i].equals(s)) {
                return i;
            }
        }
        return -1;
    }

    /** Compare by Unicode code point (equivalent to UTF-8 byte order). */
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

    private void printSummary(PrintStream out) {
        StringBuilder sb = new StringBuilder();
        sb.append("total: ").append(total).append('\n');
        sb.append("by level:");
        for (int i = 0; i < LEVELS.length; i++) {
            sb.append(' ').append(LEVELS[i]).append('=').append(levelCounts[i]);
        }
        sb.append('\n');
        sb.append("first: ").append(first == null ? "n/a" : first).append('\n');
        sb.append("last: ").append(last == null ? "n/a" : last).append('\n');
        sb.append("top errors:\n");

        List<Group> sorted = new ArrayList<>(groups.values());
        sorted.sort((a, b) -> {
            int c = Long.compare(b.count, a.count);
            return c != 0 ? c : compareCodePoints(a.display, b.display);
        });
        int limit = Math.min(top, sorted.size());
        for (int i = 0; i < limit; i++) {
            Group g = sorted.get(i);
            sb.append("  ").append(g.count).append(' ').append(g.display).append('\n');
        }
        out.print(sb);
    }
}
