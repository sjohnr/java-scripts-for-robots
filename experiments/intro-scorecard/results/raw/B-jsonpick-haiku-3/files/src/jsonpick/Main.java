package jsonpick;

import java.io.IOException;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.InvalidPathException;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.List;

/** Command-line entry point: {@code jsonpick PATH [FILE] [--shape] [--depth N]}. */
public final class Main {
    private static final String USAGE = """
            Usage: jsonpick PATH [FILE] [--shape] [--depth N]

              PATH          dot-separated keys and [N] array indexes, e.g. a.b[0].c.
                            "." or "" selects the whole document.
              FILE          JSON file to read. Omitted or "-" reads standard input.
              --shape       print a depth-limited type summary of the value at PATH
              --depth N     how many levels --shape describes (default 2)
              --help        show this message and exit

            Exit codes: 0 success, 1 path not found, 2 usage or invalid input.
            """;

    private static final int DEFAULT_DEPTH = 2;

    private Main() {}

    public static void main(String[] args) {
        System.exit(run(args));
    }

    static int run(String[] args) {
        for (String arg : args) {
            if (arg.equals("--help")) {
                writeOut(USAGE);
                return 0;
            }
        }

        List<String> positional = new ArrayList<>();
        boolean shape = false;
        String depthText = null;
        for (int i = 0; i < args.length; i++) {
            String arg = args[i];
            if (arg.equals("--shape")) {
                shape = true;
            } else if (arg.equals("--depth")) {
                if (i + 1 >= args.length) {
                    return usageError("--depth requires a value");
                }
                depthText = args[++i];
            } else if (arg.startsWith("--depth=")) {
                depthText = arg.substring("--depth=".length());
            } else if (arg.startsWith("-") && arg.length() > 1) {
                int eq = arg.indexOf('=');
                writeErr("unknown flag: " + (eq < 0 ? arg : arg.substring(0, eq)));
                return 2;
            } else {
                positional.add(arg);
            }
        }

        if (positional.isEmpty()) {
            return usageError("missing required argument: PATH");
        }
        if (positional.size() > 2) {
            return usageError("unexpected argument: " + positional.get(2));
        }
        String path = positional.get(0);
        String file = positional.size() == 2 ? positional.get(1) : null;

        int depth = DEFAULT_DEPTH;
        if (depthText != null) {
            depth = parseDepth(depthText);
            if (depth < 0) {
                return usageError("invalid --depth value: " + depthText);
            }
        }

        List<Object> steps;
        try {
            steps = JsonPath.parse(path);
        } catch (IllegalArgumentException e) {
            return usageError("invalid path: " + path);
        }

        byte[] input;
        try {
            boolean stdin = file == null || file.equals("-");
            input = stdin ? System.in.readAllBytes() : Files.readAllBytes(Paths.get(file));
        } catch (IOException | InvalidPathException e) {
            return usageError("cannot read input: " + file);
        }

        Object document;
        try {
            document = JsonParser.parse(input);
        } catch (JsonParser.SyntaxError e) {
            return usageError(e.getMessage());
        }

        Object target = JsonPath.resolve(document, steps);
        if (target == null) {
            writeErr("error: path not found: " + path);
            return 1;
        }

        writeOut(shape ? Shape.describe(target, depth) + "\n" : render(target));
        return 0;
    }

    /** Scalars print bare, strings print raw, containers print as compact JSON. */
    private static String render(Object value) {
        if (value instanceof String s) {
            return s + "\n";
        }
        StringBuilder sb = new StringBuilder();
        Json.writeCompact(value, sb);
        return sb.append('\n').toString();
    }

    /** Returns the depth as a non-negative int, or -1 if it is not one. */
    private static int parseDepth(String text) {
        if (!text.matches("[0-9]+")) {
            return -1;
        }
        try {
            return Integer.parseInt(text);
        } catch (NumberFormatException e) {
            return -1;
        }
    }

    private static int usageError(String message) {
        writeErr("error: " + message);
        return 2;
    }

    private static void writeOut(String text) {
        writeBytes(System.out, text);
    }

    private static void writeErr(String text) {
        writeBytes(System.err, text + "\n");
    }

    private static void writeBytes(PrintStream stream, String text) {
        byte[] bytes = text.getBytes(StandardCharsets.UTF_8);
        stream.write(bytes, 0, bytes.length);
        stream.flush();
    }
}
