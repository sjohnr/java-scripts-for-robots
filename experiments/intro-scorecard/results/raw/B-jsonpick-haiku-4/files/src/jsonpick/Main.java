package jsonpick;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.io.UncheckedIOException;
import java.nio.ByteBuffer;
import java.nio.CharBuffer;
import java.nio.charset.CharsetDecoder;
import java.nio.charset.CoderResult;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/** Command-line entry point: {@code jsonpick PATH [FILE] [--shape] [--depth N]}. */
public final class Main {

    private static final String USAGE = """
            Usage: jsonpick PATH [FILE] [--shape] [--depth N]

              PATH        path to a value, e.g. a.b[0].c ("." or "" selects the whole document)
              FILE        JSON input file; omitted or "-" reads standard input
              --shape     print a type/array-length summary of the value at PATH
              --depth N   with --shape, how many levels to describe (default 2)
              --help      print this message and exit
            """;

    private Main() {
    }

    public static void main(String[] args) throws Exception {
        // The parser recurses once per nesting level, so give it stack room for the maximum depth.
        int[] status = new int[1];
        Thread worker = new Thread(null, () -> {
            try {
                status[0] = run(args, System.in, System.out, System.err);
            } catch (IOException e) {
                throw new UncheckedIOException(e);
            }
        }, "jsonpick", 256L * 1024 * 1024);
        worker.start();
        worker.join();
        System.exit(status[0]);
    }

    static int run(String[] args, InputStream stdin, OutputStream out, OutputStream err)
            throws IOException {
        try {
            if (wantsHelp(args)) {
                write(out, USAGE);
                return 0;
            }
            Options options = Options.parse(args);
            List<JsonPath.Step> steps = JsonPath.parse(options.path());
            Object root = JsonParser.parse(decodeUtf8(readInput(options.file(), stdin)));
            Object target = JsonPath.resolve(root, steps);
            if (target == null) {
                write(err, "error: path not found: " + options.path() + "\n");
                return 1;
            }
            String text = options.shape()
                    ? Json.shape(target, options.depth())
                    : printable(target);
            write(out, text + "\n");
            return 0;
        } catch (CliException e) {
            write(err, e.getMessage() + "\n");
            return 2;
        }
    }

    /** Strings print raw; containers print as compact JSON; scalars print as JSON literals. */
    private static String printable(Object value) {
        if (value instanceof String s) {
            return s;
        }
        if (value instanceof List<?> || value instanceof Map<?, ?>) {
            return Json.compact(value);
        }
        return Json.literal(value);
    }

    private static boolean wantsHelp(String[] args) {
        for (String arg : args) {
            if (arg.equals("--")) {
                return false;
            }
            if (arg.equals("--help")) {
                return true;
            }
        }
        return false;
    }

    private static byte[] readInput(String file, InputStream stdin) throws IOException {
        if (file == null || file.equals("-")) {
            try {
                return stdin.readAllBytes();
            } catch (IOException e) {
                throw new CliException("error: cannot read standard input");
            }
        }
        try {
            return Files.readAllBytes(Path.of(file));
        } catch (IOException | java.nio.file.InvalidPathException e) {
            throw new CliException("error: cannot read file: " + file);
        }
    }

    private static String decodeUtf8(byte[] data) {
        CharsetDecoder decoder = StandardCharsets.UTF_8.newDecoder()
                .onMalformedInput(CodingErrorAction.REPORT)
                .onUnmappableCharacter(CodingErrorAction.REPORT);
        ByteBuffer in = ByteBuffer.wrap(data);
        // UTF-8 never yields more chars than bytes, so this buffer cannot overflow.
        CharBuffer out = CharBuffer.allocate(data.length);
        CoderResult result = decoder.decode(in, out, true);
        if (result.isError()) {
            throw new CliException(
                    "error: invalid JSON: input is not valid UTF-8 at byte offset " + in.position());
        }
        out.flip();
        return out.toString();
    }

    /** Writes UTF-8 bytes directly, so the output never depends on the platform line separator. */
    private static void write(OutputStream stream, String text) throws IOException {
        stream.write(text.getBytes(StandardCharsets.UTF_8));
        stream.flush();
    }

    /** Parsed command line. */
    record Options(String path, String file, boolean shape, int depth) {

        static Options parse(String[] args) {
            List<String> positional = new ArrayList<>();
            boolean shape = false;
            int depth = 2;
            boolean flagsEnded = false;
            for (int i = 0; i < args.length; i++) {
                String arg = args[i];
                if (flagsEnded || arg.equals("-") || !arg.startsWith("-")) {
                    positional.add(arg);
                } else if (arg.equals("--")) {
                    flagsEnded = true;
                } else if (arg.equals("--shape")) {
                    shape = true;
                } else if (arg.equals("--depth")) {
                    if (i + 1 == args.length) {
                        throw new CliException("error: --depth requires a value");
                    }
                    depth = parseDepth(args[++i]);
                } else if (arg.startsWith("--depth=")) {
                    depth = parseDepth(arg.substring("--depth=".length()));
                } else {
                    throw new CliException("unknown flag: " + arg);
                }
            }
            if (positional.isEmpty()) {
                throw new CliException("error: missing PATH argument");
            }
            if (positional.size() > 2) {
                throw new CliException("error: unexpected argument: " + positional.get(2));
            }
            String file = positional.size() == 2 ? positional.get(1) : null;
            return new Options(positional.get(0), file, shape, depth);
        }

        private static int parseDepth(String value) {
            if (value.isEmpty() || !value.chars().allMatch(c -> c >= '0' && c <= '9')) {
                throw new CliException("error: invalid --depth value: " + value);
            }
            try {
                return Integer.parseInt(value);
            } catch (NumberFormatException e) {
                throw new CliException("error: invalid --depth value: " + value);
            }
        }
    }
}
