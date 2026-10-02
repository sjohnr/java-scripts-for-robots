import java.io.FileInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStreamWriter;
import java.io.PrintWriter;
import java.io.Reader;
import java.nio.charset.CodingErrorAction;
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
import java.util.regex.Pattern;

/** logsum: streaming summary of a log file (or stdin). See SPEC.md. */
public final class Logsum {

    private static final String[] LEVELS = {"DEBUG", "INFO", "WARN", "ERROR"};
    private static final int ERROR = 3;

    private static final DateTimeFormatter TS_FORMAT =
            DateTimeFormatter.ofPattern("uuuu-MM-dd'T'HH:mm:ss").withResolverStyle(ResolverStyle.STRICT);
    private static final Pattern TS_SHAPE = Pattern.compile("[0-9]{4}-[0-9]{2}-[0-9]{2}T[0-9]{2}:[0-9]{2}:[0-9]{2}");

    private static final Pattern UUID = Pattern.compile(
            "[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}");
    private static final Pattern HEX = Pattern.compile(
            "\\b(?=[0-9a-fA-F]*[a-fA-F])[0-9a-fA-F]{6,}\\b", Pattern.UNICODE_CHARACTER_CLASS);
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
            "  --level LEVEL        only consider lines at LEVEL (DEBUG, INFO, WARN or ERROR)",
            "  --top N              number of top error groups to show (default 5)",
            "  --help               show this help and exit",
            "");

    /** Signals a usage error: message goes to stderr, exit code 2. */
    private static final class UsageError extends Exception {
        UsageError(String message) {
            super(message, null, false, false);
        }
    }

    private static final class Group {
        final String message;
        long count;

        Group(String message) {
            this.message = message;
        }
    }

    public static void main(String[] args) {
        PrintWriter out = new PrintWriter(new OutputStreamWriter(System.out, StandardCharsets.UTF_8), false);
        try {
            run(args, out);
            out.flush();
            System.exit(0);
        } catch (UsageError e) {
            out.flush();
            PrintWriter err = new PrintWriter(new OutputStreamWriter(System.err, StandardCharsets.UTF_8), false);
            err.print(e.getMessage() + "\n");
            err.flush();
            System.exit(2);
        }
    }

    private static void run(String[] args, PrintWriter out) throws UsageError {
        String file = null;
        String since = null;
        int level = -1;
        int top = 5;

        for (int i = 0; i < args.length; i++) {
            String arg = args[i];
            switch (arg) {
                case "--help" -> {
                    out.print(HELP);
                    return;
                }
                case "--since" -> {
                    String value = requireValue(args, ++i, arg);
                    if (!isValidTimestamp(value)) {
                        throw new UsageError("error: invalid value for --since: " + value);
                    }
                    since = value;
                }
                case "--level" -> {
                    String value = requireValue(args, ++i, arg);
                    level = levelIndex(value);
                    if (level < 0) {
                        throw new UsageError("error: invalid value for --level: " + value);
                    }
                }
                case "--top" -> {
                    String value = requireValue(args, ++i, arg);
                    if (!value.matches("[0-9]+")) {
                        throw new UsageError("error: invalid value for --top: " + value);
                    }
                    String digits = value.replaceFirst("^0+(?=.)", "");
                    top = digits.length() > 9 ? Integer.MAX_VALUE : Integer.parseInt(digits);
                }
                default -> {
                    if (arg.startsWith("-") && !arg.equals("-")) {
                        throw new UsageError("unknown flag: " + arg);
                    }
                    if (file != null) {
                        throw new UsageError("error: unexpected argument: " + arg);
                    }
                    file = arg;
                }
            }
        }

        boolean stdin = file == null || file.equals("-");
        InputStream in;
        if (stdin) {
            in = System.in;
        } else {
            Path path = Path.of(file);
            if (Files.isDirectory(path) || !Files.isReadable(path)) {
                throw new UsageError("error: cannot read " + file);
            }
            try {
                in = new FileInputStream(path.toFile());
            } catch (IOException e) {
                throw new UsageError("error: cannot read " + file);
            }
        }

        try (Reader reader = new InputStreamReader(in, StandardCharsets.UTF_8.newDecoder()
                .onMalformedInput(CodingErrorAction.REPLACE)
                .onUnmappableCharacter(CodingErrorAction.REPLACE))) {
            summarize(reader, since, level, top, out);
        } catch (IOException e) {
            throw new UsageError("error: cannot read " + (stdin ? "-" : file));
        }
    }

    private static String requireValue(String[] args, int i, String flag) throws UsageError {
        if (i >= args.length) {
            throw new UsageError("error: missing value for " + flag);
        }
        return args[i];
    }

    private static boolean isValidTimestamp(String s) {
        if (s.length() != 19 || !TS_SHAPE.matcher(s).matches()) {
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

    private static void summarize(Reader reader, String since, int levelFilter, int top, PrintWriter out)
            throws IOException {
        long total = 0;
        long[] byLevel = new long[LEVELS.length];
        String first = null;
        String last = null;
        Map<String, Group> groups = new HashMap<>();

        LineReader lines = new LineReader(reader);
        String line;
        while ((line = lines.next()) != null) {
            // Fixed layout: TIMESTAMP(19) ' ' LEVEL ' ' MESSAGE
            if (line.length() < 21 || line.charAt(19) != ' ') {
                continue;
            }
            String ts = line.substring(0, 19);
            int levelEnd = line.indexOf(' ', 20);
            if (levelEnd < 0) {
                continue;
            }
            int level = levelIndex(line.substring(20, levelEnd));
            if (level < 0) {
                continue;
            }
            // MESSAGE starts at the third token; trailing whitespace is dropped so no output line ends in it.
            String message = line.substring(levelEnd + 1).strip();
            if (message.isEmpty() || !isValidTimestamp(ts)) {
                continue;
            }
            if (since != null && ts.compareTo(since) < 0) {
                continue;
            }
            if (levelFilter >= 0 && level != levelFilter) {
                continue;
            }

            total++;
            byLevel[level]++;
            if (first == null) {
                first = ts;
            }
            last = ts;

            if (level == ERROR) {
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
        out.print(sb);
        for (int i = 0; i < sorted.size() && i < top; i++) {
            Group g = sorted.get(i);
            out.print("  " + g.count + " " + g.message + "\n");
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
        return Integer.compare(a.length() - i, b.length() - j);
    }

    /** Splits on LF only, dropping a trailing CR and a leading BOM. */
    private static final class LineReader {
        private final Reader reader;
        private final char[] buf = new char[1 << 16];
        private int pos;
        private int len;
        private boolean eof;
        private boolean firstLine = true;

        LineReader(Reader reader) {
            this.reader = reader;
        }

        String next() throws IOException {
            StringBuilder sb = new StringBuilder();
            boolean any = false;
            while (true) {
                if (pos >= len) {
                    if (eof) {
                        break;
                    }
                    len = reader.read(buf, 0, buf.length);
                    pos = 0;
                    if (len <= 0) {
                        len = 0;
                        eof = true;
                        break;
                    }
                }
                any = true;
                int start = pos;
                while (pos < len && buf[pos] != '\n') {
                    pos++;
                }
                sb.append(buf, start, pos - start);
                if (pos < len) {
                    pos++;
                    return finish(sb);
                }
            }
            return any && sb.length() > 0 ? finish(sb) : null;
        }

        private String finish(StringBuilder sb) {
            int n = sb.length();
            if (n > 0 && sb.charAt(n - 1) == '\r') {
                sb.setLength(n - 1);
            }
            if (firstLine) {
                firstLine = false;
                if (sb.length() > 0 && sb.charAt(0) == '﻿') {
                    sb.deleteCharAt(0);
                }
            }
            return sb.toString();
        }
    }
}
