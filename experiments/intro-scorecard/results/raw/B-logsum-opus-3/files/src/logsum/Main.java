package logsum;

import java.io.BufferedReader;
import java.io.BufferedWriter;
import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStreamWriter;
import java.io.PrintStream;
import java.io.Writer;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
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
public final class Main {

    static final int EXIT_OK = 0;
    static final int EXIT_USAGE = 2;

    static final String[] LEVELS = {"DEBUG", "INFO", "WARN", "ERROR"};

    static final String HELP =
            "Usage: logsum [FILE] [--since TIMESTAMP] [--level LEVEL] [--top N]\n"
            + "\n"
            + "Summarize a log file whose lines look like: TIMESTAMP LEVEL MESSAGE\n"
            + "\n"
            + "Arguments:\n"
            + "  FILE                 log file to read; omitted or '-' reads stdin\n"
            + "\n"
            + "Options:\n"
            + "  --since TIMESTAMP    only consider lines at or after TIMESTAMP (YYYY-MM-DDTHH:MM:SS)\n"
            + "  --level LEVEL        only consider lines at exactly LEVEL (DEBUG, INFO, WARN, ERROR)\n"
            + "  --top N              number of top error groups to show (default 5)\n"
            + "  --help               show this help and exit\n";

    private static final Pattern TIMESTAMP_SHAPE =
            Pattern.compile("[0-9]{4}-[0-9]{2}-[0-9]{2}T[0-9]{2}:[0-9]{2}:[0-9]{2}");
    private static final DateTimeFormatter TIMESTAMP_FORMAT =
            DateTimeFormatter.ofPattern("uuuu-MM-dd'T'HH:mm:ss").withResolverStyle(ResolverStyle.STRICT);

    private static final Pattern UUID_RE = Pattern.compile(
            "[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}");
    private static final Pattern HEX_RE = Pattern.compile("\\b(?=[0-9a-fA-F]*[a-fA-F])[0-9a-fA-F]{6,}\\b");
    private static final Pattern NUM_RE = Pattern.compile("[0-9]+");

    /** Thrown for usage errors; message is printed verbatim to stderr. */
    static final class UsageException extends Exception {
        UsageException(String message) {
            super(message);
        }
    }

    static final class Options {
        boolean help;
        String file; // null or "-" means stdin
        String since;
        String level;
        int top = 5;
    }

    private static final class Group {
        final String display;
        long count;

        Group(String display) {
            this.display = display;
        }
    }

    public static void main(String[] args) {
        PrintStream out = new PrintStream(new java.io.FileOutputStream(java.io.FileDescriptor.out), false,
                StandardCharsets.UTF_8);
        PrintStream err = new PrintStream(new java.io.FileOutputStream(java.io.FileDescriptor.err), true,
                StandardCharsets.UTF_8);
        int code = run(args, System.in, out, err);
        out.flush();
        err.flush();
        System.exit(code);
    }

    static int run(String[] args, InputStream stdin, PrintStream out, PrintStream err) {
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
        boolean useStdin = opts.file == null || opts.file.equals("-");
        if (useStdin) {
            in = stdin;
        } else {
            File f = new File(opts.file);
            try {
                if (!f.isFile()) {
                    throw new IOException("not a regular file");
                }
                in = new FileInputStream(f);
            } catch (IOException | SecurityException e) {
                err.print("error: cannot read " + opts.file + "\n");
                return EXIT_USAGE;
            }
        }

        String report;
        try {
            report = summarize(in, opts);
        } catch (IOException e) {
            err.print("error: cannot read " + (useStdin ? "-" : opts.file) + "\n");
            return EXIT_USAGE;
        } finally {
            if (!useStdin) {
                try {
                    in.close();
                } catch (IOException ignored) {
                    // nothing useful to do
                }
            }
        }
        out.print(report);
        return EXIT_OK;
    }

    static Options parseArgs(String[] args) throws UsageException {
        Options opts = new Options();
        UsageException firstError = null;
        for (int i = 0; i < args.length; i++) {
            String arg = args[i];
            try {
                if (arg.equals("--help")) {
                    opts.help = true;
                } else if (arg.equals("-") || !arg.startsWith("-")) {
                    if (opts.file != null) {
                        throw new UsageException("error: unexpected argument: " + arg);
                    }
                    opts.file = arg;
                } else {
                    String name = arg;
                    String value = null;
                    int eq = arg.indexOf('=');
                    if (arg.startsWith("--") && eq > 2) {
                        name = arg.substring(0, eq);
                        value = arg.substring(eq + 1);
                    }
                    if (!name.equals("--since") && !name.equals("--level") && !name.equals("--top")) {
                        throw new UsageException("unknown flag: " + name);
                    }
                    if (value == null) {
                        if (i + 1 >= args.length) {
                            throw new UsageException("error: missing value for " + name);
                        }
                        value = args[++i];
                    }
                    applyFlag(opts, name, value);
                }
            } catch (UsageException e) {
                if (firstError == null) {
                    firstError = e;
                }
            }
        }
        if (!opts.help && firstError != null) {
            throw firstError;
        }
        return opts;
    }

    private static void applyFlag(Options opts, String name, String value) throws UsageException {
        switch (name) {
            case "--since":
                if (!isValidTimestamp(value)) {
                    throw new UsageException("error: invalid value for --since: " + value);
                }
                opts.since = value;
                break;
            case "--level":
                if (levelIndex(value) < 0) {
                    throw new UsageException("error: invalid value for --level: " + value);
                }
                opts.level = value;
                break;
            case "--top":
                if (!value.matches("[0-9]+")) {
                    throw new UsageException("error: invalid value for --top: " + value);
                }
                int top;
                try {
                    top = Integer.parseInt(value);
                } catch (NumberFormatException e) {
                    top = Integer.MAX_VALUE;
                }
                opts.top = top;
                break;
            default:
                throw new UsageException("unknown flag: " + name);
        }
    }

    static boolean isValidTimestamp(String s) {
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

    static int levelIndex(String s) {
        for (int i = 0; i < LEVELS.length; i++) {
            if (LEVELS[i].equals(s)) {
                return i;
            }
        }
        return -1;
    }

    static String normalize(String message) {
        String s = UUID_RE.matcher(message).replaceAll("<uuid>");
        s = HEX_RE.matcher(s).replaceAll("<hex>");
        return NUM_RE.matcher(s).replaceAll("<num>");
    }

    private static boolean isWs(char c) {
        return c == ' ' || c == '\t' || c == '\f' || c == '\u000B';
    }

    static String summarize(InputStream in, Options opts) throws IOException {
        BufferedReader reader = new BufferedReader(new InputStreamReader(in, StandardCharsets.UTF_8.newDecoder()
                .onMalformedInput(CodingErrorAction.REPLACE)
                .onUnmappableCharacter(CodingErrorAction.REPLACE)), 1 << 16);

        long total = 0;
        long[] byLevel = new long[LEVELS.length];
        String first = null;
        String last = null;
        Map<String, Group> groups = new HashMap<>();
        int errorIdx = levelIndex("ERROR");

        String line;
        while ((line = reader.readLine()) != null) {
            // Tokenize: TIMESTAMP, LEVEL, then MESSAGE from the start of the third token.
            int n = line.length();
            int p = 0;
            while (p < n && isWs(line.charAt(p))) p++;
            int tsStart = p;
            while (p < n && !isWs(line.charAt(p))) p++;
            String ts = line.substring(tsStart, p);
            while (p < n && isWs(line.charAt(p))) p++;
            int lvStart = p;
            while (p < n && !isWs(line.charAt(p))) p++;
            String lv = line.substring(lvStart, p);
            while (p < n && isWs(line.charAt(p))) p++;
            if (p >= n || ts.isEmpty() || lv.isEmpty()) {
                continue; // fewer than 3 tokens
            }
            int end = n;
            while (end > p && isWs(line.charAt(end - 1))) end--;
            String message = line.substring(p, end);

            int li = levelIndex(lv);
            if (li < 0 || !isValidTimestamp(ts)) {
                continue;
            }
            if (opts.since != null && ts.compareTo(opts.since) < 0) {
                continue;
            }
            if (opts.level != null && !opts.level.equals(lv)) {
                continue;
            }

            total++;
            byLevel[li]++;
            if (first == null) {
                first = ts;
            }
            last = ts;

            if (li == errorIdx) {
                groups.computeIfAbsent(normalize(message), k -> new Group(message)).count++;
            }
        }

        List<Group> sorted = new ArrayList<>(groups.values());
        sorted.sort((a, b) -> {
            int c = Long.compare(b.count, a.count);
            return c != 0 ? c : compareCodePoints(a.display, b.display);
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
            sb.append("  ").append(g.count).append(' ').append(g.display).append('\n');
        }
        return sb.toString();
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
