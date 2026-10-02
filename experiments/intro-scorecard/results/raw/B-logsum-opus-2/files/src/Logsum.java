import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.io.PrintStream;
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

/** logsum: streaming summary of a log file or stdin. See SPEC.md. */
public final class Logsum {

    private static final int EXIT_OK = 0;
    private static final int EXIT_USAGE = 2;

    private static final String[] LEVELS = {"DEBUG", "INFO", "WARN", "ERROR"};
    private static final int ERROR_INDEX = 3;

    private static final Pattern TIMESTAMP_SHAPE =
            Pattern.compile("[0-9]{4}-[0-9]{2}-[0-9]{2}T[0-9]{2}:[0-9]{2}:[0-9]{2}");
    private static final DateTimeFormatter TIMESTAMP_FORMAT =
            DateTimeFormatter.ofPattern("uuuu-MM-dd'T'HH:mm:ss").withResolverStyle(ResolverStyle.STRICT);

    private static final Pattern UUID =
            Pattern.compile("[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}");
    private static final Pattern HEX = Pattern.compile("\\b(?=[0-9a-fA-F]*[a-fA-F])[0-9a-fA-F]{6,}\\b");
    private static final Pattern NUM = Pattern.compile("[0-9]+");

    private static final String HELP = String.join("\n",
            "Usage: logsum [FILE] [--since TIMESTAMP] [--level LEVEL] [--top N]",
            "",
            "Summarize a log file (or stdin) with lines of the form:",
            "  YYYY-MM-DDTHH:MM:SS LEVEL MESSAGE",
            "",
            "Arguments:",
            "  FILE               log file to read; omitted or '-' reads stdin",
            "",
            "Options:",
            "  --since TIMESTAMP  only consider lines at or after TIMESTAMP (YYYY-MM-DDTHH:MM:SS)",
            "  --level LEVEL      only consider lines at LEVEL (DEBUG, INFO, WARN, ERROR)",
            "  --top N            number of top error groups to print (default 5)",
            "  --help             print this help and exit",
            "");

    /** Signals a usage error: message goes to stderr, exit code 2. */
    private static final class UsageException extends Exception {
        UsageException(String message) {
            super(message);
        }
    }

    private static final class Options {
        String file;
        String since;
        int levelFilter = -1;
        int top = 5;
        boolean help;
    }

    private static final class Group {
        final String message;
        long count;

        Group(String message) {
            this.message = message;
        }
    }

    public static void main(String[] args) {
        PrintStream out = utf8(System.out);
        PrintStream err = utf8(System.err);
        int code = run(args, out, err);
        out.flush();
        err.flush();
        System.exit(code);
    }

    private static PrintStream utf8(OutputStream stream) {
        return new PrintStream(stream, false, StandardCharsets.UTF_8);
    }

    static int run(String[] args, PrintStream out, PrintStream err) {
        Options opts;
        try {
            opts = parseArgs(args);
        } catch (UsageException e) {
            err.print(e.getMessage() + "\n");
            return EXIT_USAGE;
        }
        if (opts.help) {
            out.print(HELP);
            return EXIT_OK;
        }

        InputStream in;
        if (opts.file == null || opts.file.equals("-")) {
            in = System.in;
        } else {
            Path path = Paths.get(opts.file);
            try {
                if (!Files.isRegularFile(path)) {
                    throw new IOException("not a regular file");
                }
                in = Files.newInputStream(path);
            } catch (IOException | SecurityException e) {
                err.print("error: cannot read " + opts.file + "\n");
                return EXIT_USAGE;
            }
        }

        try (BufferedReader reader = new BufferedReader(new InputStreamReader(in,
                StandardCharsets.UTF_8.newDecoder()
                        .onMalformedInput(CodingErrorAction.REPLACE)
                        .onUnmappableCharacter(CodingErrorAction.REPLACE)), 1 << 16)) {
            summarize(reader, opts, out);
        } catch (IOException e) {
            err.print("error: cannot read " + (opts.file == null ? "-" : opts.file) + "\n");
            return EXIT_USAGE;
        }
        return EXIT_OK;
    }

    private static Options parseArgs(String[] args) throws UsageException {
        Options opts = new Options();
        for (int i = 0; i < args.length; i++) {
            String arg = args[i];
            if (arg.equals("--help")) {
                opts.help = true;
                continue;
            }
            if (arg.equals("-") || !arg.startsWith("-")) {
                if (opts.file != null) {
                    throw new UsageException("error: unexpected argument: " + arg);
                }
                opts.file = arg;
                continue;
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
                case "--since" -> {
                    if (!isValidTimestamp(value)) {
                        throw new UsageException("error: invalid value for --since: " + value);
                    }
                    opts.since = value;
                }
                case "--level" -> {
                    int level = levelIndex(value);
                    if (level < 0) {
                        throw new UsageException("error: invalid value for --level: " + value);
                    }
                    opts.levelFilter = level;
                }
                default -> {
                    int top;
                    try {
                        top = value.matches("[0-9]+") ? Integer.parseInt(value) : -1;
                    } catch (NumberFormatException e) {
                        top = -1;
                    }
                    if (top < 0) {
                        throw new UsageException("error: invalid value for --top: " + value);
                    }
                    opts.top = top;
                }
            }
        }
        return opts;
    }

    private static void summarize(BufferedReader reader, Options opts, PrintStream out) throws IOException {
        long total = 0;
        long[] byLevel = new long[LEVELS.length];
        String first = null;
        String last = null;
        Map<String, Group> groups = new HashMap<>();

        String line;
        while ((line = reader.readLine()) != null) {
            // Tolerate CRLF input and trailing whitespace.
            line = stripTrailing(line);

            int tsEnd = indexOfWhitespace(line, 0);
            if (tsEnd < 0) {
                continue;
            }
            int levelStart = skipWhitespace(line, tsEnd);
            int levelEnd = indexOfWhitespace(line, levelStart);
            if (levelEnd < 0) {
                continue;
            }
            int msgStart = skipWhitespace(line, levelEnd);
            if (msgStart >= line.length()) {
                continue;
            }

            String timestamp = line.substring(0, tsEnd);
            if (!isValidTimestamp(timestamp)) {
                continue;
            }
            int level = levelIndex(line.substring(levelStart, levelEnd));
            if (level < 0) {
                continue;
            }

            if (opts.since != null && timestamp.compareTo(opts.since) < 0) {
                continue;
            }
            if (opts.levelFilter >= 0 && level != opts.levelFilter) {
                continue;
            }

            total++;
            byLevel[level]++;
            if (first == null) {
                first = timestamp;
            }
            last = timestamp;

            if (level == ERROR_INDEX) {
                String message = line.substring(msgStart);
                groups.computeIfAbsent(normalize(message), k -> new Group(message)).count++;
            }
        }

        List<Group> sorted = new ArrayList<>(groups.values());
        sorted.sort((a, b) -> {
            int c = Long.compare(b.count, a.count);
            return c != 0 ? c : compareCodePoints(a.message, b.message);
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
        int limit = Math.min(opts.top, sorted.size());
        for (int i = 0; i < limit; i++) {
            Group g = sorted.get(i);
            sb.append("  ").append(g.count).append(' ').append(g.message).append('\n');
        }
        out.print(sb);
    }

    static String normalize(String message) {
        String s = UUID.matcher(message).replaceAll("<uuid>");
        s = HEX.matcher(s).replaceAll("<hex>");
        return NUM.matcher(s).replaceAll("<num>");
    }

    private static boolean isValidTimestamp(String s) {
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

    private static boolean isWhitespace(char c) {
        return c == ' ' || c == '\t' || c == '\r' || c == '\f' || c == '\u000B';
    }

    private static int indexOfWhitespace(String s, int from) {
        for (int i = from; i < s.length(); i++) {
            if (isWhitespace(s.charAt(i))) {
                return i;
            }
        }
        return -1;
    }

    private static int skipWhitespace(String s, int from) {
        int i = from;
        while (i < s.length() && isWhitespace(s.charAt(i))) {
            i++;
        }
        return i;
    }

    private static String stripTrailing(String s) {
        int end = s.length();
        while (end > 0 && isWhitespace(s.charAt(end - 1))) {
            end--;
        }
        return s.substring(0, end);
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
        return Integer.compare(a.length() - i, b.length() - j);
    }
}
