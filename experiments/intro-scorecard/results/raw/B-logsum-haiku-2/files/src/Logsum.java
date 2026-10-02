import java.io.BufferedReader;
import java.io.BufferedWriter;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStreamWriter;
import java.io.Writer;
import java.math.BigInteger;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.InvalidPathException;
import java.nio.file.Path;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;
import java.time.format.ResolverStyle;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * logsum: streaming summary of a log file (or stdin). See SPEC.md.
 */
public final class Logsum {

    private static final String[] LEVELS = {"DEBUG", "INFO", "WARN", "ERROR"};
    private static final int ERROR_LEVEL = 3;
    private static final int DEFAULT_TOP = 5;

    private static final String USAGE = String.join("\n",
            "usage: logsum [FILE] [--since TIMESTAMP] [--level LEVEL] [--top N]",
            "",
            "Summarize a log file, or stdin when FILE is omitted or '-'.",
            "",
            "  FILE               log file to read (default: stdin)",
            "  --since TIMESTAMP  only count lines at or after TIMESTAMP (YYYY-MM-DDTHH:MM:SS)",
            "  --level LEVEL      only count lines at LEVEL (DEBUG, INFO, WARN, ERROR)",
            "  --top N            number of error groups to list (default: " + DEFAULT_TOP + ")",
            "  --help             show this help and exit",
            "");

    private static final Pattern LINE =
            Pattern.compile("\\s*(\\S+)\\s+(\\S+)\\s+(\\S.*)", Pattern.DOTALL);
    private static final Pattern TIMESTAMP_SHAPE =
            Pattern.compile("[0-9]{4}-[0-9]{2}-[0-9]{2}T[0-9]{2}:[0-9]{2}:[0-9]{2}");
    private static final DateTimeFormatter TIMESTAMP_FORMAT =
            DateTimeFormatter.ofPattern("uuuu-MM-dd'T'HH:mm:ss").withResolverStyle(ResolverStyle.STRICT);
    private static final Pattern TOP_VALUE = Pattern.compile("[0-9]+");

    private static final Pattern UUID_PATTERN = Pattern.compile(
            "[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}");
    private static final Pattern HEX_PATTERN = Pattern.compile(
            "\\b(?=[0-9a-fA-F]*[a-fA-F])[0-9a-fA-F]{6,}\\b", Pattern.UNICODE_CHARACTER_CLASS);
    private static final Pattern NUM_PATTERN = Pattern.compile("[0-9]+");

    private static final Comparator<Group> GROUP_ORDER = (a, b) -> a.count != b.count
            ? Long.compare(b.count, a.count)
            : comparePoints(a.message, b.message);

    private Logsum() {
    }

    public static void main(String[] args) throws IOException {
        Writer out = new BufferedWriter(new OutputStreamWriter(System.out, StandardCharsets.UTF_8));
        int code;
        try {
            code = run(args, System.in, out);
        } catch (UsageError e) {
            Writer err = new OutputStreamWriter(System.err, StandardCharsets.UTF_8);
            err.write(e.getMessage() + "\n");
            err.flush();
            code = 2;
        }
        out.flush();
        System.exit(code);
    }

    private static int run(String[] args, InputStream stdin, Writer out) throws IOException {
        Options opts = parseArgs(args);
        if (opts.help) {
            out.write(USAGE);
            return 0;
        }

        boolean fromStdin = opts.file == null;
        InputStream source = fromStdin ? stdin : openFile(opts.file);
        Summary summary = new Summary();
        try (BufferedReader reader = new BufferedReader(new InputStreamReader(source, StandardCharsets.UTF_8))) {
            String line;
            while ((line = reader.readLine()) != null) {
                Entry entry = parseLine(line);
                if (entry == null) {
                    continue;
                }
                if (opts.since != null && entry.timestamp().compareTo(opts.since) < 0) {
                    continue;
                }
                if (opts.level >= 0 && entry.level() != opts.level) {
                    continue;
                }
                summary.accept(entry);
            }
        } catch (IOException e) {
            throw new UsageError("error: cannot read " + (fromStdin ? "stdin" : opts.file));
        }

        writeReport(out, summary, opts.top);
        return 0;
    }

    private static InputStream openFile(String name) {
        try {
            Path path = Path.of(name);
            if (Files.isDirectory(path)) {
                throw new UsageError("error: cannot read " + name);
            }
            return Files.newInputStream(path);
        } catch (IOException | InvalidPathException e) {
            throw new UsageError("error: cannot read " + name);
        }
    }

    private static Options parseArgs(String[] args) {
        Options opts = new Options();
        boolean fileSeen = false;
        boolean flagsDone = false;

        for (int i = 0; i < args.length; i++) {
            String arg = args[i];

            if (flagsDone || arg.equals("-") || !arg.startsWith("-")) {
                if (fileSeen) {
                    throw new UsageError("error: unexpected argument: " + arg);
                }
                fileSeen = true;
                opts.file = arg.equals("-") ? null : arg;
                continue;
            }
            if (arg.equals("--")) {
                flagsDone = true;
                continue;
            }

            String name = arg;
            String inline = null;
            int eq = arg.indexOf('=');
            if (arg.startsWith("--") && eq > 0) {
                name = arg.substring(0, eq);
                inline = arg.substring(eq + 1);
            }

            switch (name) {
                case "--help" -> {
                    opts.help = true;
                    return opts;
                }
                case "--since", "--level", "--top" -> {
                    String value;
                    if (inline != null) {
                        value = inline;
                    } else if (i + 1 < args.length) {
                        value = args[++i];
                    } else {
                        throw new UsageError("error: " + name + " requires a value");
                    }
                    applyValue(opts, name, value);
                }
                default -> throw new UsageError("unknown flag: " + name);
            }
        }
        return opts;
    }

    private static void applyValue(Options opts, String name, String value) {
        switch (name) {
            case "--since" -> {
                if (!isTimestamp(value)) {
                    throw new UsageError("error: invalid value for --since: " + value);
                }
                opts.since = value;
            }
            case "--level" -> {
                int idx = levelIndex(value);
                if (idx < 0) {
                    throw new UsageError("error: invalid value for --level: " + value);
                }
                opts.level = idx;
            }
            case "--top" -> {
                if (!TOP_VALUE.matcher(value).matches()) {
                    throw new UsageError("error: invalid value for --top: " + value);
                }
                // Clamp rather than fail on absurdly large N: it just means "all groups".
                BigInteger n = new BigInteger(value);
                opts.top = n.min(BigInteger.valueOf(Integer.MAX_VALUE)).intValue();
            }
            default -> throw new IllegalStateException(name);
        }
    }

    /** Returns the parsed line, or null if it does not match the input format. */
    private static Entry parseLine(String line) {
        Matcher m = LINE.matcher(line);
        if (!m.matches()) {
            return null;
        }
        String timestamp = m.group(1);
        if (!isTimestamp(timestamp)) {
            return null;
        }
        int level = levelIndex(m.group(2));
        if (level < 0) {
            return null;
        }
        // Trailing whitespace is dropped so no output line ends in spaces.
        return new Entry(timestamp, level, m.group(3).stripTrailing());
    }

    private static boolean isTimestamp(String value) {
        if (!TIMESTAMP_SHAPE.matcher(value).matches()) {
            return false;
        }
        try {
            LocalDateTime.parse(value, TIMESTAMP_FORMAT);
            return true;
        } catch (DateTimeParseException e) {
            return false;
        }
    }

    private static int levelIndex(String value) {
        return Arrays.asList(LEVELS).indexOf(value);
    }

    /** Normalizes a message for grouping. Order of replacements matters. */
    static String normalize(String message) {
        String s = UUID_PATTERN.matcher(message).replaceAll("<uuid>");
        s = HEX_PATTERN.matcher(s).replaceAll("<hex>");
        return NUM_PATTERN.matcher(s).replaceAll("<num>");
    }

    /** Orders strings by Unicode code point, which matches UTF-8 byte order. */
    private static int comparePoints(String a, String b) {
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

    private static void writeReport(Writer out, Summary s, int top) throws IOException {
        StringBuilder sb = new StringBuilder();
        sb.append("total: ").append(s.total).append('\n');
        sb.append("by level:");
        for (int i = 0; i < LEVELS.length; i++) {
            sb.append(' ').append(LEVELS[i]).append('=').append(s.byLevel[i]);
        }
        sb.append('\n');
        sb.append("first: ").append(s.total == 0 ? "n/a" : s.first).append('\n');
        sb.append("last: ").append(s.total == 0 ? "n/a" : s.last).append('\n');
        sb.append("top errors:\n");

        List<Group> groups = new ArrayList<>(s.errorGroups.values());
        groups.sort(GROUP_ORDER);
        for (Group g : groups.subList(0, Math.min(top, groups.size()))) {
            sb.append("  ").append(g.count).append(' ').append(g.message).append('\n');
        }
        out.write(sb.toString());
    }

    private record Entry(String timestamp, int level, String message) {
    }

    private static final class Group {
        final String message;
        long count;

        Group(String message) {
            this.message = message;
        }
    }

    private static final class Summary {
        long total;
        final long[] byLevel = new long[LEVELS.length];
        String first;
        String last;
        final Map<String, Group> errorGroups = new HashMap<>();

        void accept(Entry e) {
            total++;
            byLevel[e.level()]++;
            if (first == null) {
                first = e.timestamp();
            }
            last = e.timestamp();
            if (e.level() == ERROR_LEVEL) {
                errorGroups.computeIfAbsent(normalize(e.message()), k -> new Group(e.message())).count++;
            }
        }
    }

    private static final class Options {
        String file;
        String since;
        int level = -1;
        int top = DEFAULT_TOP;
        boolean help;
    }

    /** A bad flag or input; reported on stderr with exit code 2. */
    private static final class UsageError extends RuntimeException {
        private static final long serialVersionUID = 1L;

        UsageError(String message) {
            super(message);
        }
    }
}
