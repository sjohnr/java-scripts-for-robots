package logsum;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.InvalidPathException;
import java.nio.file.Path;

/** Command-line entry point: parses arguments, streams the log, prints the summary. */
public final class Main {

    static final int DEFAULT_TOP = 5;

    static final String USAGE = """
            usage: logsum [FILE] [--since TIMESTAMP] [--level LEVEL] [--top N]

            Summarize a log file, or stdin when FILE is omitted or '-'.
            Lines look like: TIMESTAMP LEVEL MESSAGE (malformed lines are skipped).

              FILE                 log file to read (default: stdin)
              --since TIMESTAMP    only count lines at or after TIMESTAMP (YYYY-MM-DDTHH:MM:SS)
              --level LEVEL        only count lines at LEVEL (DEBUG, INFO, WARN, ERROR)
              --top N              number of error groups to list (default: 5)
              --help               show this help and exit
            """;

    private Main() {
    }

    public static void main(String[] args) {
        System.exit(run(args));
    }

    static int run(String[] args) {
        Options opts;
        try {
            opts = Options.parse(args);
        } catch (UsageException e) {
            return fail(e.getMessage());
        }
        if (opts.help) {
            emit(System.out, USAGE);
            return 0;
        }

        boolean stdin = opts.file == null || opts.file.equals("-");
        String label = stdin ? "-" : opts.file;
        Summary summary = new Summary(opts.since, opts.level, opts.top);
        try (BufferedReader reader = stdin ? utf8(System.in) : openFile(opts.file)) {
            String line;
            while ((line = reader.readLine()) != null) {
                summary.accept(line);
            }
        } catch (IOException | InvalidPathException e) {
            return fail("error: cannot read " + label);
        }

        emit(System.out, summary.render());
        return 0;
    }

    private static BufferedReader openFile(String file) throws IOException {
        Path path = Path.of(file);
        if (Files.isDirectory(path)) {
            throw new IOException("is a directory: " + file);
        }
        return utf8(Files.newInputStream(path));
    }

    private static BufferedReader utf8(InputStream in) {
        return new BufferedReader(new InputStreamReader(in, StandardCharsets.UTF_8));
    }

    private static int fail(String message) {
        emit(System.err, message + "\n");
        return 2;
    }

    private static void emit(PrintStream stream, String text) {
        byte[] bytes = text.getBytes(StandardCharsets.UTF_8);
        stream.write(bytes, 0, bytes.length);
        stream.flush();
    }

    /** Parsed command line. */
    static final class Options {
        boolean help;
        String file;
        String since;
        String level;
        int top = DEFAULT_TOP;

        static Options parse(String[] args) {
            Options o = new Options();
            String topText = null;
            boolean endOfFlags = false;

            for (int i = 0; i < args.length; i++) {
                String arg = args[i];
                if (endOfFlags || arg.equals("-") || !arg.startsWith("-")) {
                    if (o.file != null) {
                        throw new UsageException("error: unexpected argument: " + arg);
                    }
                    o.file = arg;
                } else if (arg.equals("--")) {
                    endOfFlags = true;
                } else if (arg.equals("--help")) {
                    o.help = true;
                    return o;
                } else {
                    String name = arg;
                    String value = null;
                    int eq = arg.indexOf('=');
                    if (arg.startsWith("--") && eq > 0) {
                        name = arg.substring(0, eq);
                        value = arg.substring(eq + 1);
                    }
                    if (!name.equals("--since") && !name.equals("--level") && !name.equals("--top")) {
                        throw new UsageException("unknown flag: " + name);
                    }
                    if (value == null) {
                        if (++i >= args.length) {
                            throw new UsageException("error: missing value for " + name);
                        }
                        value = args[i];
                    }
                    switch (name) {
                        case "--since" -> o.since = value;
                        case "--level" -> o.level = value;
                        default -> topText = value;
                    }
                }
            }

            if (o.since != null && !Summary.isTimestamp(o.since)) {
                throw new UsageException("error: invalid value for --since: " + o.since);
            }
            if (o.level != null && !Summary.LEVELS.contains(o.level)) {
                throw new UsageException("error: invalid value for --level: " + o.level);
            }
            if (topText != null) {
                o.top = parseTop(topText);
            }
            return o;
        }

        private static int parseTop(String text) {
            if (!text.matches("[0-9]+")) {
                throw new UsageException("error: invalid value for --top: " + text);
            }
            try {
                return Integer.parseInt(text);
            } catch (NumberFormatException e) {
                return Integer.MAX_VALUE;
            }
        }
    }

    /** A bad command line; the message is printed to stderr verbatim and exit code is 2. */
    static final class UsageException extends RuntimeException {
        private static final long serialVersionUID = 1L;

        UsageException(String message) {
            super(message);
        }
    }
}
