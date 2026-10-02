package logsum;

import java.io.FileDescriptor;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;

/** Command-line entry point: parses arguments, streams the input, prints the summary. */
public final class Main {

    static final int DEFAULT_TOP = 5;

    static final String USAGE = String.join("\n",
            "usage: logsum [FILE] [--since TIMESTAMP] [--level LEVEL] [--top N]",
            "",
            "Summarize a log file (or stdin) with lines of the form:",
            "  TIMESTAMP LEVEL MESSAGE",
            "",
            "positional:",
            "  FILE              log file to read; omitted or '-' reads stdin",
            "",
            "options:",
            "  --since TIMESTAMP only count lines at or after TIMESTAMP (YYYY-MM-DDTHH:MM:SS)",
            "  --level LEVEL     only count lines at LEVEL (DEBUG, INFO, WARN, ERROR)",
            "  --top N           number of error groups to list (default 5)",
            "  --help            show this message and exit",
            "") + "\n";

    private Main() {
    }

    public static void main(String[] args) {
        PrintStream out = new PrintStream(new FileOutputStream(FileDescriptor.out), false, StandardCharsets.UTF_8);
        PrintStream err = new PrintStream(new FileOutputStream(FileDescriptor.err), false, StandardCharsets.UTF_8);
        int code = run(args, out, err);
        out.flush();
        err.flush();
        System.exit(code);
    }

    /** Runs the tool and returns the process exit code. */
    static int run(String[] args, PrintStream out, PrintStream err) {
        String file = null;
        String since = null;
        Level level = null;
        int top = DEFAULT_TOP;

        for (int i = 0; i < args.length; i++) {
            String arg = args[i];
            switch (arg) {
                case "--help":
                    out.print(USAGE);
                    return 0;
                case "--since":
                case "--level":
                case "--top":
                    if (i + 1 >= args.length) {
                        return usageError(err, "missing value for " + arg);
                    }
                    String value = args[++i];
                    if (arg.equals("--since")) {
                        if (!LineParser.isValidTimestamp(value)) {
                            return usageError(err, "invalid value for --since: " + value);
                        }
                        since = value;
                    } else if (arg.equals("--level")) {
                        level = Level.parse(value);
                        if (level == null) {
                            return usageError(err, "invalid value for --level: " + value);
                        }
                    } else {
                        Integer n = parseTop(value);
                        if (n == null) {
                            return usageError(err, "invalid value for --top: " + value);
                        }
                        top = n;
                    }
                    break;
                default:
                    if (arg.startsWith("-") && !arg.equals("-")) {
                        err.print("unknown flag: " + arg + "\n");
                        return 2;
                    }
                    if (file != null) {
                        return usageError(err, "unexpected argument: " + arg);
                    }
                    file = arg;
                    break;
            }
        }

        Summary summary = new Summary(since, level);
        try {
            if (file == null || file.equals("-")) {
                summarize(System.in, summary);
            } else {
                try (InputStream in = new FileInputStream(file)) {
                    summarize(in, summary);
                }
            }
        } catch (IOException e) {
            err.print("error: cannot read " + (file == null ? "-" : file) + "\n");
            return 2;
        }

        out.print(summary.render(top));
        return 0;
    }

    private static void summarize(InputStream in, Summary summary) throws IOException {
        LineReader reader = new LineReader(in);
        byte[] line;
        while ((line = reader.next()) != null) {
            summary.accept(LineParser.parse(new String(line, StandardCharsets.UTF_8)));
        }
    }

    /** Non-negative integer, clamped to Integer.MAX_VALUE; null if the value is not one. */
    private static Integer parseTop(String value) {
        if (value.isEmpty() || !value.chars().allMatch(c -> c >= '0' && c <= '9')) {
            return null;
        }
        try {
            return Integer.valueOf(value);
        } catch (NumberFormatException e) {
            return Integer.MAX_VALUE;
        }
    }

    private static int usageError(PrintStream err, String message) {
        err.print("error: " + message + "\n");
        return 2;
    }
}
