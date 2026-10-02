package jsonpick;

import java.io.IOException;
import java.io.InputStream;
import java.io.PrintStream;
import java.math.BigInteger;
import java.nio.ByteBuffer;
import java.nio.CharBuffer;
import java.nio.charset.CharsetDecoder;
import java.nio.charset.CoderResult;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Paths;
import java.util.List;

/** Command-line entry point: {@code jsonpick PATH [FILE] [--shape] [--depth N]}. */
public final class Main {

    static final int EXIT_OK = 0;
    static final int EXIT_NOT_FOUND = 1;
    static final int EXIT_USAGE = 2;

    static final int DEFAULT_DEPTH = 2;

    static final String HELP =
            "Usage: jsonpick PATH [FILE] [--shape] [--depth N]\n"
            + "\n"
            + "Print the value at PATH in a JSON document read from FILE (or stdin).\n"
            + "\n"
            + "Arguments:\n"
            + "  PATH          dot-separated keys and [N] indexes, e.g. a.b[0].c;\n"
            + "                \".\" or \"\" selects the whole document\n"
            + "  FILE          input file; omitted or \"-\" reads stdin\n"
            + "\n"
            + "Options:\n"
            + "  --shape       print a depth-limited type/length summary of the value at PATH\n"
            + "  --depth N     levels to describe with --shape (default 2)\n"
            + "  --help        show this help and exit\n"
            + "  --            treat everything after it as arguments, even if it starts with -\n"
            + "\n"
            + "Exit status: 0 success, 1 path not found, 2 usage or invalid input error.\n";

    /** Carries a usage error to main(); the message goes to stderr as-is. */
    private static final class UsageError extends Exception {
        private static final long serialVersionUID = 1L;

        UsageError(String message) {
            super(message);
        }
    }

    private Main() {
    }

    public static void main(String[] args) {
        System.exit(run(args, System.in, System.out, System.err));
    }

    static int run(String[] args, InputStream stdin, PrintStream out, PrintStream err) {
        try {
            return execute(args, stdin, out, err);
        } catch (UsageError e) {
            writeText(err, e.getMessage() + "\n");
            return EXIT_USAGE;
        }
    }

    private static int execute(String[] args, InputStream stdin, PrintStream out,
                               PrintStream err) throws UsageError {
        String path = null;
        String file = null;
        boolean shape = false;
        int depth = DEFAULT_DEPTH;
        boolean optionsEnded = false;

        for (int i = 0; i < args.length; i++) {
            String arg = args[i];
            boolean isFlag = !optionsEnded && arg.startsWith("-") && !arg.equals("-");
            if (arg.equals("--") && !optionsEnded) {
                optionsEnded = true;
            } else if (!isFlag) {
                if (path == null) {
                    path = arg;
                } else if (file == null) {
                    file = arg;
                } else {
                    throw new UsageError("error: unexpected argument: " + arg);
                }
            } else if (arg.equals("--help")) {
                writeText(out, HELP);
                return EXIT_OK;
            } else if (arg.equals("--shape")) {
                shape = true;
            } else if (arg.startsWith("--shape=")) {
                throw new UsageError("error: --shape takes no value");
            } else if (arg.equals("--depth")) {
                if (i + 1 >= args.length) {
                    throw new UsageError("error: --depth requires a value");
                }
                depth = parseDepth(args[++i]);
            } else if (arg.startsWith("--depth=")) {
                depth = parseDepth(arg.substring("--depth=".length()));
            } else {
                throw new UsageError("unknown flag: " + arg.split("=", 2)[0]);
            }
        }

        if (path == null) {
            throw new UsageError("error: missing required argument: PATH");
        }

        List<Object> segments;
        try {
            segments = JsonPath.parse(path);
        } catch (IllegalArgumentException e) {
            throw new UsageError("error: " + e.getMessage());
        }

        String text;
        try {
            text = readInput(file, stdin);
        } catch (IOException e) {
            throw new UsageError("error: cannot read file: " + file);
        }

        Object document;
        try {
            document = JsonParser.parse(text);
        } catch (JsonParser.JsonException e) {
            throw new UsageError("error: invalid JSON: " + e.getMessage());
        }

        Object value = JsonPath.resolve(document, segments);
        if (value == JsonPath.MISSING) {
            writeText(err, "error: path not found: " + path + "\n");
            return EXIT_NOT_FOUND;
        }

        if (shape) {
            writeText(out, Shape.describe(value, depth) + "\n");
        } else if (value instanceof String) {
            writeText(out, value + "\n");
        } else {
            writeText(out, JsonWriter.compact(value) + "\n");
        }
        return EXIT_OK;
    }

    private static int parseDepth(String text) throws UsageError {
        if (text.isEmpty() || !text.chars().allMatch(ch -> ch >= '0' && ch <= '9')) {
            throw new UsageError("error: --depth must be a non-negative integer: " + text);
        }
        // Depth beyond the document's nesting is irrelevant, so saturate instead of failing.
        BigInteger value = new BigInteger(text);
        return value.min(BigInteger.valueOf(Integer.MAX_VALUE)).intValue();
    }

    /** Reads FILE (or stdin) and decodes it strictly as UTF-8. */
    private static String readInput(String file, InputStream stdin) throws IOException,
            UsageError {
        byte[] bytes = (file == null || file.equals("-"))
                ? stdin.readAllBytes()
                : Files.readAllBytes(Paths.get(file));

        CharsetDecoder decoder = StandardCharsets.UTF_8.newDecoder()
                .onMalformedInput(CodingErrorAction.REPORT)
                .onUnmappableCharacter(CodingErrorAction.REPORT);
        ByteBuffer in = ByteBuffer.wrap(bytes);
        CharBuffer out = CharBuffer.allocate(bytes.length);
        CoderResult result = decoder.decode(in, out, true);
        if (result.isError()) {
            throw new UsageError("error: invalid JSON: invalid UTF-8 at byte offset " + in.position());
        }
        decoder.flush(out);
        out.flip();
        return out.toString();
    }

    private static void writeText(PrintStream stream, String text) {
        byte[] bytes = text.getBytes(StandardCharsets.UTF_8);
        stream.write(bytes, 0, bytes.length);
        stream.flush();
    }
}
