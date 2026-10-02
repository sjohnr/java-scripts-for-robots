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
import java.util.ArrayList;
import java.util.List;

/** Command-line entry point: {@code jsonpick PATH [FILE] [--shape] [--depth N]}. */
public final class Main {

    static final int OK = 0;
    static final int NOT_FOUND = 1;
    static final int USAGE_ERROR = 2;

    static final String USAGE = """
            Usage: jsonpick PATH [FILE] [--shape] [--depth N]

            Extract a value from JSON by path.

            Arguments:
              PATH          Path to select, e.g. a.b[0].c. "." or "" selects the whole document.
              FILE          JSON file to read. Omitted or "-" reads standard input.

            Options:
              --shape       Print a depth-limited type/array-length summary of the value at PATH.
              --depth N     Levels to describe with --shape (default 2).
              --help        Show this help and exit.
            """;

    private Main() {
    }

    public static void main(String[] args) {
        PrintStream out = new PrintStream(System.out, false, StandardCharsets.UTF_8);
        PrintStream err = new PrintStream(System.err, false, StandardCharsets.UTF_8);
        int code = run(args, System.in, out, err);
        out.flush();
        err.flush();
        System.exit(code);
    }

    static int run(String[] args, InputStream stdin, PrintStream out, PrintStream err) {
        boolean shape = false;
        int depth = 2;
        boolean flagsEnded = false;
        List<String> positional = new ArrayList<>();

        for (int i = 0; i < args.length; i++) {
            String arg = args[i];
            if (flagsEnded) {
                positional.add(arg);
            } else if (arg.equals("--")) {
                flagsEnded = true;
            } else if (arg.equals("-") || !arg.startsWith("-")) {
                positional.add(arg);
            } else {
                String flag = arg;
                String value = null;
                int eq = arg.indexOf('=');
                if (arg.startsWith("--") && eq > 0) {
                    flag = arg.substring(0, eq);
                    value = arg.substring(eq + 1);
                }
                switch (flag) {
                    case "--help" -> {
                        out.print(USAGE);
                        return OK;
                    }
                    case "--shape" -> {
                        if (value != null) {
                            return usageError(err, "--shape does not take a value");
                        }
                        shape = true;
                    }
                    case "--depth" -> {
                        if (value == null) {
                            if (i + 1 >= args.length) {
                                return usageError(err, "--depth requires a value");
                            }
                            value = args[++i];
                        }
                        if (!value.matches("\\d+")) {
                            return usageError(err, "--depth must be a non-negative integer");
                        }
                        depth = new BigInteger(value).min(BigInteger.valueOf(Integer.MAX_VALUE)).intValue();
                    }
                    default -> {
                        err.print("unknown flag: " + flag + "\n");
                        return USAGE_ERROR;
                    }
                }
            }
        }

        if (positional.isEmpty()) {
            return usageError(err, "PATH is required");
        }
        if (positional.size() > 2) {
            return usageError(err, "unexpected argument: " + positional.get(2));
        }
        String path = positional.get(0);
        String file = positional.size() == 2 ? positional.get(1) : "-";

        List<Object> steps;
        try {
            steps = Selector.parse(path);
        } catch (IllegalArgumentException e) {
            return usageError(err, e.getMessage());
        }

        byte[] bytes;
        try {
            bytes = file.equals("-") ? stdin.readAllBytes() : Files.readAllBytes(Paths.get(file));
        } catch (IOException e) {
            return usageError(err, "cannot read file: " + file);
        }

        Object root;
        try {
            root = Json.parse(decodeUtf8(bytes));
        } catch (Json.ParseException e) {
            return usageError(err, "invalid JSON: " + e.getMessage());
        }

        Object node = Selector.resolve(root, steps);
        if (node == null) {
            err.print("error: path not found: " + path + "\n");
            return NOT_FOUND;
        }
        out.print((shape ? Shape.describe(node, depth) : Json.render(node)) + "\n");
        return OK;
    }

    private static int usageError(PrintStream err, String message) {
        err.print("error: " + message + "\n");
        return USAGE_ERROR;
    }

    /** Decodes strictly as UTF-8; the error names the byte offset of the first bad byte. */
    private static String decodeUtf8(byte[] bytes) throws Json.ParseException {
        CharsetDecoder decoder = StandardCharsets.UTF_8.newDecoder()
                .onMalformedInput(CodingErrorAction.REPORT)
                .onUnmappableCharacter(CodingErrorAction.REPORT);
        ByteBuffer in = ByteBuffer.wrap(bytes);
        CharBuffer out = CharBuffer.allocate(bytes.length);
        CoderResult result = decoder.decode(in, out, true);
        if (result.isError()) {
            throw new Json.ParseException("invalid UTF-8 at byte offset " + in.position());
        }
        decoder.flush(out);
        out.flip();
        return out.toString();
    }
}
