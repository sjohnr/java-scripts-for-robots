import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.PrintStream;
import java.math.BigInteger;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.InvalidPathException;
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

/**
 * logsum: streaming summary of a log file (or stdin). Behaviour is defined by SPEC.md.
 */
public final class Logsum {

    private static final String[] LEVELS = {"DEBUG", "INFO", "WARN", "ERROR"};
    private static final int ERROR = 3;
    private static final int DEFAULT_TOP = 5;

    private static final String USAGE = String.join("\n",
            "usage: logsum [FILE] [--since TIMESTAMP] [--level LEVEL] [--top N]",
            "",
            "Summarize a log file, or stdin when FILE is omitted or '-'.",
            "",
            "positional arguments:",
            "  FILE                log file to read (default: stdin)",
            "",
            "options:",
            "  --since TIMESTAMP   only count lines at or after TIMESTAMP (YYYY-MM-DDTHH:MM:SS)",
            "  --level LEVEL       only count lines at LEVEL (DEBUG, INFO, WARN, ERROR)",
            "  --top N             number of error groups to list (default: 5)",
            "  --help              show this help and exit",
            "");

    private static final Pattern TIMESTAMP_SHAPE =
            Pattern.compile("[0-9]{4}-[0-9]{2}-[0-9]{2}T[0-9]{2}:[0-9]{2}:[0-9]{2}");
    private static final DateTimeFormatter TIMESTAMP_FORMAT =
            DateTimeFormatter.ofPattern("uuuu-MM-dd'T'HH:mm:ss").withResolverStyle(ResolverStyle.STRICT);

    // Normalization for top-error grouping, applied in this order.
    private static final Pattern UUID_PATTERN =
            Pattern.compile("[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}");
    // UNICODE_CHARACTER_CLASS so \b agrees with Unicode word characters, as the reference regex does.
    private static final Pattern HEX_PATTERN = Pattern.compile(
            "\\b(?=[0-9a-fA-F]*[a-fA-F])[0-9a-fA-F]{6,}\\b", Pattern.UNICODE_CHARACTER_CLASS);
    private static final Pattern NUM_PATTERN = Pattern.compile("[0-9]+");

    private Logsum() {
    }

    public static void main(String[] args) {
        System.exit(run(args));
    }

    static int run(String[] args) {
        Options opts;
        try {
            opts = parseArgs(args);
        } catch (UsageException e) {
            return fail(e.getMessage());
        }
        if (opts.help) {
            return write(System.out, USAGE);
        }

        Summary summary;
        try (BufferedReader in = open(opts.file)) {
            summary = summarize(in, opts);
        } catch (IOException | InvalidPathException e) {
            return fail("error: cannot read " + opts.file);
        }
        return write(System.out, render(summary, opts.top));
    }

    // ---- arguments -------------------------------------------------------

    static final class Options {
        boolean help;
        String file = "-";
        String since;
        String level;
        int top = DEFAULT_TOP;
    }

    static final class UsageException extends Exception {
        private static final long serialVersionUID = 1L;

        UsageException(String message) {
            super(message);
        }
    }

    static Options parseArgs(String[] args) throws UsageException {
        Options o = new Options();
        List<String> positional = new ArrayList<>();
        boolean flagsEnded = false;

        for (int i = 0; i < args.length; i++) {
            String arg = args[i];
            if (flagsEnded || arg.equals("-") || !arg.startsWith("-")) {
                positional.add(arg);
                continue;
            }
            if (arg.equals("--")) {
                flagsEnded = true;
                continue;
            }

            String name = arg;
            String inline = null;
            int eq = arg.indexOf('=');
            if (arg.startsWith("--") && eq > 0) {
                name = arg.substring(0, eq);
                inline = arg.substring(eq + 1);
            }

            if (name.equals("--since") || name.equals("--level") || name.equals("--top")) {
                String value = inline;
                if (value == null) {
                    if (i + 1 >= args.length) {
                        throw new UsageException("error: missing value for " + name);
                    }
                    value = args[++i];
                }
                if (name.equals("--since")) {
                    o.since = value;
                } else if (name.equals("--level")) {
                    o.level = value;
                } else {
                    o.top = parseTop(value);
                }
            } else if (name.equals("--help") && inline == null) {
                o.help = true;
                return o;
            } else {
                throw new UsageException("unknown flag: " + name);
            }
        }

        if (positional.size() > 1) {
            throw new UsageException("error: unexpected argument: " + positional.get(1));
        }
        if (positional.size() == 1) {
            o.file = positional.get(0);
        }
        if (o.since != null && !isTimestamp(o.since)) {
            throw new UsageException("error: invalid value for --since: " + o.since);
        }
        if (o.level != null && levelIndex(o.level) < 0) {
            throw new UsageException("error: invalid value for --level: " + o.level);
        }
        return o;
    }

    private static int parseTop(String value) throws UsageException {
        if (!value.matches("[0-9]+")) {
            throw new UsageException("error: invalid value for --top: " + value);
        }
        // Clamp rather than fail on absurdly large counts; they can never be exceeded anyway.
        return new BigInteger(value).min(BigInteger.valueOf(Integer.MAX_VALUE)).intValue();
    }

    // ---- input lines -----------------------------------------------------

    static final class LogLine {
        final String timestamp;
        final int level;
        final String message;

        LogLine(String timestamp, int level, String message) {
            this.timestamp = timestamp;
            this.level = level;
            this.message = message;
        }
    }

    /** Returns the parsed line, or null if the line does not match the input format. */
    static LogLine parseLine(String line) {
        int first = line.indexOf(' ');
        if (first < 0) {
            return null;
        }
        int second = line.indexOf(' ', first + 1);
        if (second < 0) {
            return null;
        }
        String timestamp = line.substring(0, first);
        int level = levelIndex(line.substring(first + 1, second));
        // MESSAGE is taken verbatim; a message with no visible content means fewer than 3 tokens.
        String message = line.substring(second + 1);
        if (level < 0 || message.isBlank() || !isTimestamp(timestamp)) {
            return null;
        }
        return new LogLine(timestamp, level, message);
    }

    static boolean isTimestamp(String s) {
        if (!TIMESTAMP_SHAPE.matcher(s).matches()) {
            return false;
        }
        try {
            LocalDateTime.parse(s, TIMESTAMP_FORMAT);
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

    static String normalize(String message) {
        String s = UUID_PATTERN.matcher(message).replaceAll("<uuid>");
        s = HEX_PATTERN.matcher(s).replaceAll("<hex>");
        return NUM_PATTERN.matcher(s).replaceAll("<num>");
    }

    // ---- statistics ------------------------------------------------------

    static final class Group {
        final String message; // first-seen original message, used for display
        long count;

        Group(String message) {
            this.message = message;
        }
    }

    static final class Summary {
        long total;
        final long[] byLevel = new long[LEVELS.length];
        String first;
        String last;
        final Map<String, Group> errors = new HashMap<>();
    }

    static Summary summarize(BufferedReader in, Options opts) throws IOException {
        int levelFilter = opts.level == null ? -1 : levelIndex(opts.level);
        Summary s = new Summary();
        String raw;
        while ((raw = in.readLine()) != null) {
            LogLine line = parseLine(raw);
            if (line == null) {
                continue;
            }
            if (opts.since != null && line.timestamp.compareTo(opts.since) < 0) {
                continue;
            }
            if (levelFilter >= 0 && line.level != levelFilter) {
                continue;
            }
            s.total++;
            s.byLevel[line.level]++;
            if (s.first == null) {
                s.first = line.timestamp;
            }
            s.last = line.timestamp;
            if (line.level == ERROR) {
                s.errors.computeIfAbsent(normalize(line.message), k -> new Group(line.message)).count++;
            }
        }
        return s;
    }

    // ---- output ----------------------------------------------------------

    static String render(Summary s, int top) {
        StringBuilder out = new StringBuilder();
        out.append("total: ").append(s.total).append('\n');
        out.append("by level:");
        for (int i = 0; i < LEVELS.length; i++) {
            out.append(' ').append(LEVELS[i]).append('=').append(s.byLevel[i]);
        }
        out.append('\n');
        out.append("first: ").append(s.first == null ? "n/a" : s.first).append('\n');
        out.append("last: ").append(s.last == null ? "n/a" : s.last).append('\n');
        out.append("top errors:\n");

        List<Group> groups = new ArrayList<>(s.errors.values());
        groups.sort((a, b) -> a.count != b.count
                ? Long.compare(b.count, a.count)
                : compareCodePoints(display(a), display(b)));
        for (Group g : groups.subList(0, Math.min(top, groups.size()))) {
            out.append("  ").append(g.count).append(' ').append(display(g)).append('\n');
        }
        return out.toString();
    }

    /** Trailing whitespace is dropped on output so no line ends in whitespace. */
    private static String display(Group g) {
        return g.message.stripTrailing();
    }

    /** Orders strings by Unicode code point, which matches UTF-8 byte order. */
    static int compareCodePoints(String a, String b) {
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

    // ---- io helpers ------------------------------------------------------

    private static BufferedReader open(String file) throws IOException {
        InputStream raw = file.equals("-") ? System.in : Files.newInputStream(Paths.get(file));
        // Undecodable bytes become U+FFFD rather than aborting, since logs are often messy.
        return new BufferedReader(new InputStreamReader(raw, StandardCharsets.UTF_8));
    }

    private static int write(PrintStream stream, String text) {
        byte[] bytes = text.getBytes(StandardCharsets.UTF_8);
        stream.write(bytes, 0, bytes.length);
        stream.flush();
        return 0;
    }

    private static int fail(String message) {
        write(System.err, message + "\n");
        return 2;
    }
}
