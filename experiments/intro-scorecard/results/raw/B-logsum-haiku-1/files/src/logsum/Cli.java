package logsum;

import java.util.Set;

/** Command-line parsing. Usage errors carry the exact stderr line the spec requires. */
final class Cli {

    static final String USAGE = """
            usage: logsum [FILE] [--since TIMESTAMP] [--level LEVEL] [--top N]

            Summarize a log file, or stdin when FILE is omitted or "-".

              FILE               log file to read (default: stdin)
              --since TIMESTAMP  only count lines at or after TIMESTAMP (YYYY-MM-DDTHH:MM:SS)
              --level LEVEL      only count lines at LEVEL: DEBUG, INFO, WARN or ERROR
              --top N            number of error groups to list (default: 5)
              --help             print this message and exit
            """;

    private static final int DEFAULT_TOP = 5;
    private static final Set<String> VALUE_FLAGS = Set.of("--since", "--level", "--top");

    /** A parsed command line. {@code help} means print usage and exit 0; other fields are then unused. */
    record Options(String file, String since, String level, int top, boolean help) {}

    static final class UsageException extends RuntimeException {
        private static final long serialVersionUID = 1L;

        UsageException(String message) {
            super(message);
        }
    }

    private Cli() {}

    static Options parse(String[] args) {
        String file = null;
        String since = null;
        String level = null;
        int top = DEFAULT_TOP;
        boolean positionalOnly = false;

        for (int i = 0; i < args.length; i++) {
            String arg = args[i];
            if (positionalOnly || arg.equals("-") || !arg.startsWith("-")) {
                if (file != null) {
                    throw new UsageException("error: unexpected argument: " + arg);
                }
                file = arg;
                continue;
            }
            if (arg.equals("--")) {
                positionalOnly = true;
                continue;
            }

            int eq = arg.indexOf('=');
            String name = eq > 0 ? arg.substring(0, eq) : arg;
            String value = eq > 0 ? arg.substring(eq + 1) : null;
            if (VALUE_FLAGS.contains(name) && value == null) {
                if (i + 1 >= args.length) {
                    throw new UsageException("error: missing value for " + name);
                }
                value = args[++i];
            }

            switch (name) {
                case "--help" -> {
                    if (value != null) {
                        throw unknownFlag(name);
                    }
                    return new Options(file, since, level, top, true);
                }
                case "--since" -> since = checkTimestamp(value);
                case "--level" -> level = checkLevel(value);
                case "--top" -> top = parseTop(value);
                default -> throw unknownFlag(name);
            }
        }
        return new Options(file, since, level, top, false);
    }

    private static String checkTimestamp(String value) {
        if (!LogLine.isTimestamp(value)) {
            throw invalid("--since", value);
        }
        return value;
    }

    private static String checkLevel(String value) {
        if (!LogLine.LEVELS.contains(value)) {
            throw invalid("--level", value);
        }
        return value;
    }

    /** Positive integer. Values too large for an int mean "all groups". */
    private static int parseTop(String value) {
        if (value.matches("[0-9]+")) {
            try {
                int n = Integer.parseInt(value);
                if (n > 0) {
                    return n;
                }
            } catch (NumberFormatException tooLarge) {
                return Integer.MAX_VALUE;
            }
        }
        throw invalid("--top", value);
    }

    private static UsageException invalid(String flag, String value) {
        return new UsageException("error: invalid value for " + flag + ": " + value);
    }

    private static UsageException unknownFlag(String flag) {
        return new UsageException("unknown flag: " + flag);
    }
}
