package logsum;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStreamWriter;
import java.io.Writer;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.InvalidPathException;
import java.nio.file.Path;

/** Entry point: {@code logsum [FILE] [--since TIMESTAMP] [--level LEVEL] [--top N]}. */
public final class Main {

    private Main() {}

    public static void main(String[] args) throws IOException {
        Writer out = new OutputStreamWriter(System.out, StandardCharsets.UTF_8);
        Writer err = new OutputStreamWriter(System.err, StandardCharsets.UTF_8);
        int status = run(args, out, err);
        out.flush();
        err.flush();
        System.exit(status);
    }

    static int run(String[] args, Writer out, Writer err) throws IOException {
        Cli.Options options;
        try {
            options = Cli.parse(args);
        } catch (Cli.UsageException e) {
            err.write(e.getMessage() + "\n");
            return 2;
        }
        if (options.help()) {
            out.write(Cli.USAGE);
            return 0;
        }

        String source = options.file() == null ? "-" : options.file();
        Summary summary = new Summary(options.top());
        try (BufferedReader reader = open(source)) {
            String raw;
            while ((raw = reader.readLine()) != null) {
                LogLine line = LogLine.parse(raw);
                if (line != null && passesFilters(line, options)) {
                    summary.add(line);
                }
            }
        } catch (IOException | InvalidPathException e) {
            err.write("error: cannot read " + source + "\n");
            return 2;
        }
        summary.write(out);
        return 0;
    }

    private static boolean passesFilters(LogLine line, Cli.Options options) {
        if (options.since() != null && line.timestamp().compareTo(options.since()) < 0) {
            return false;
        }
        return options.level() == null || options.level().equals(line.level());
    }

    private static BufferedReader open(String source) throws IOException {
        InputStream in = source.equals("-") ? System.in : Files.newInputStream(Path.of(source));
        return new BufferedReader(new InputStreamReader(in, StandardCharsets.UTF_8));
    }
}
