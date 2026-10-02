package logsum;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.PrintStream;
import java.io.Reader;
import java.nio.file.Files;
import java.nio.file.InvalidPathException;
import java.nio.file.Paths;

import static java.nio.charset.StandardCharsets.UTF_8;

public final class Main {

    static final String USAGE = """
            Usage: logsum [FILE] [--since TIMESTAMP] [--level LEVEL] [--top N]

            Summarize a log file, or stdin, whose lines look like:
              TIMESTAMP LEVEL MESSAGE

            Arguments:
              FILE                 file to read; "-" or omitted reads stdin

            Options:
              --since TIMESTAMP    only count lines at or after TIMESTAMP (YYYY-MM-DDTHH:MM:SS)
              --level LEVEL        only count lines at exactly LEVEL (DEBUG, INFO, WARN, ERROR)
              --top N              number of top error groups to show (default 5)
              --help               show this help and exit
            """;

    private Main() {
    }

    public static void main(String[] args) {
        System.exit(run(args, System.in, System.out, System.err));
    }

    static int run(String[] args, InputStream stdin, PrintStream out, PrintStream err) {
        Options opts;
        try {
            opts = Options.parse(args);
        } catch (UsageException e) {
            write(err, e.getMessage() + "\n");
            return 2;
        }
        if (opts.help) {
            write(out, USAGE);
            return 0;
        }

        String name = opts.file == null ? "stdin" : opts.file;
        InputStream in = stdin;
        if (opts.file != null) {
            try {
                in = Files.newInputStream(Paths.get(opts.file));
            } catch (IOException | InvalidPathException e) {
                write(err, "error: cannot read " + name + "\n");
                return 2;
            }
        }

        Summary summary = new Summary(opts.since, opts.level);
        try (Reader reader = new BufferedReader(new InputStreamReader(in, UTF_8))) {
            String line;
            while ((line = readLine(reader)) != null) {
                summary.accept(line);
            }
        } catch (IOException e) {
            write(err, "error: cannot read " + name + "\n");
            return 2;
        }

        write(out, summary.report(opts.top));
        return 0;
    }

    /**
     * Reads one line terminated by LF (the terminator is dropped). Unlike
     * BufferedReader.readLine, a lone CR is not treated as a line break.
     * Returns null at end of input.
     */
    private static String readLine(Reader reader) throws IOException {
        StringBuilder sb = new StringBuilder();
        int c;
        while ((c = reader.read()) != -1) {
            if (c == '\n') {
                return sb.toString();
            }
            sb.append((char) c);
        }
        return sb.isEmpty() ? null : sb.toString();
    }

    private static void write(PrintStream stream, String text) {
        byte[] bytes = text.getBytes(UTF_8);
        stream.write(bytes, 0, bytes.length);
        stream.flush();
    }

    static final class UsageException extends Exception {
        private static final long serialVersionUID = 1L;

        UsageException(String message) {
            super(message);
        }
    }

    static final class Options {
        /** null means read stdin. */
        String file;
        String since;
        String level;
        long top = 5;
        boolean help;

        static Options parse(String[] args) throws UsageException {
            Options opts = new Options();
            boolean sawFile = false;
            boolean endOfFlags = false;

            for (int i = 0; i < args.length; i++) {
                String arg = args[i];

                if (endOfFlags || arg.equals("-") || !arg.startsWith("-")) {
                    if (sawFile) {
                        throw new UsageException("error: unexpected argument: " + arg);
                    }
                    sawFile = true;
                    opts.file = arg.equals("-") ? null : arg;
                    continue;
                }
                if (arg.equals("--")) {
                    endOfFlags = true;
                    continue;
                }

                String flag = arg;
                String inline = null;
                int eq = arg.indexOf('=');
                if (arg.startsWith("--") && eq > 0) {
                    flag = arg.substring(0, eq);
                    inline = arg.substring(eq + 1);
                }

                switch (flag) {
                    case "--help" -> {
                        if (inline != null) {
                            throw new UsageException("unknown flag: " + arg);
                        }
                        opts.help = true;
                        return opts;
                    }
                    case "--since" -> {
                        String value = valueOf(flag, inline, args, i);
                        if (inline == null) {
                            i++;
                        }
                        if (!Summary.isTimestamp(value)) {
                            throw new UsageException("error: invalid value for --since: " + value);
                        }
                        opts.since = value;
                    }
                    case "--level" -> {
                        String value = valueOf(flag, inline, args, i);
                        if (inline == null) {
                            i++;
                        }
                        if (!Summary.isLevel(value)) {
                            throw new UsageException("error: invalid value for --level: " + value);
                        }
                        opts.level = value;
                    }
                    case "--top" -> {
                        String value = valueOf(flag, inline, args, i);
                        if (inline == null) {
                            i++;
                        }
                        opts.top = parseCount(value);
                    }
                    default -> throw new UsageException("unknown flag: " + flag);
                }
            }
            return opts;
        }

        private static String valueOf(String flag, String inline, String[] args, int i)
                throws UsageException {
            if (inline != null) {
                return inline;
            }
            if (i + 1 >= args.length) {
                throw new UsageException("error: missing value for " + flag);
            }
            return args[i + 1];
        }

        private static long parseCount(String value) throws UsageException {
            if (value.matches("[0-9]+")) {
                try {
                    return Long.parseLong(value);
                } catch (NumberFormatException e) {
                    // Too large to represent: fall through to the usage error.
                }
            }
            throw new UsageException("error: invalid value for --top: " + value);
        }
    }
}
