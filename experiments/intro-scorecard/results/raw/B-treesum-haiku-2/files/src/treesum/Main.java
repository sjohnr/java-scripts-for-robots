package treesum;

import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.math.BigInteger;
import java.nio.charset.StandardCharsets;
import java.nio.file.FileVisitResult;
import java.nio.file.Files;
import java.nio.file.InvalidPathException;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.SimpleFileVisitor;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

public final class Main {

    private static final Set<String> IGNORED_DIRS = Set.of(
            ".git", "node_modules", "build", ".gradle", "target",
            "dist", ".venv", "__pycache__", ".idea", ".vscode");

    private static final String HELP = """
            Usage: treesum [DIR] [--top N] [--ext-limit M]

            Compact overview of the files under DIR.

              DIR              directory to summarize (default: .)
              --top N          number of largest files to list (default: 10)
              --ext-limit M    number of extensions shown in the by-extension
                               table; the rest are folded into an "other" row
                               (default: 20)
              --help           show this message and exit

            Ignored directory names (anywhere in the tree, never descended into):
              .git, node_modules, build, .gradle, target, dist, .venv,
              __pycache__, .idea, .vscode

            Symbolic links are not followed and are not counted.
            """;

    // Byte-wise (UTF-8) ordering, so ties sort the same on every platform.
    private static final Comparator<String> UTF8_ORDER = (a, b) ->
            Arrays.compareUnsigned(a.getBytes(StandardCharsets.UTF_8), b.getBytes(StandardCharsets.UTF_8));

    private Main() {
    }

    public static void main(String[] args) {
        int code = run(args);
        System.out.flush();
        System.err.flush();
        System.exit(code);
    }

    static int run(String[] args) {
        Options opts;
        try {
            opts = Options.parse(args);
        } catch (UsageException e) {
            writeErr(e.getMessage() + "\n");
            return 2;
        }
        if (opts.help) {
            writeOut(HELP);
            return 0;
        }

        Path root;
        try {
            root = Paths.get(opts.dir).toRealPath();
        } catch (IOException | InvalidPathException e) {
            writeErr("error: cannot read " + opts.dir + "\n");
            return 2;
        }
        if (!Files.isDirectory(root)) {
            writeErr("error: cannot read " + opts.dir + "\n");
            return 2;
        }

        writeOut(render(scan(root), opts));
        return 0;
    }

    // ---- options -----------------------------------------------------------

    private static final class UsageException extends Exception {
        private static final long serialVersionUID = 1L;

        UsageException(String message) {
            super(message);
        }
    }

    private static final class Options {
        String dir = ".";
        int top = 10;
        int extLimit = 20;
        boolean help;

        static Options parse(String[] args) throws UsageException {
            Options o = new Options();
            String dir = null;
            boolean flagsEnded = false;

            for (int i = 0; i < args.length; i++) {
                String arg = args[i];

                if (!flagsEnded && arg.equals("--")) {
                    flagsEnded = true;
                    continue;
                }
                if (flagsEnded || !arg.startsWith("-") || arg.equals("-")) {
                    if (dir != null) {
                        throw new UsageException("unexpected argument: " + arg);
                    }
                    dir = arg;
                    continue;
                }

                String name = arg;
                String value = null;
                int eq = arg.indexOf('=');
                if (eq >= 0) {
                    name = arg.substring(0, eq);
                    value = arg.substring(eq + 1);
                }

                switch (name) {
                    case "--help" -> {
                        if (value != null) {
                            throw new UsageException("unknown flag: " + name);
                        }
                        o.help = true;
                        return o;
                    }
                    case "--top", "--ext-limit" -> {
                        if (value == null) {
                            if (i + 1 >= args.length) {
                                throw new UsageException("missing value for " + name);
                            }
                            value = args[++i];
                        }
                        int n = parseCount(name, value);
                        if (name.equals("--top")) {
                            o.top = n;
                        } else {
                            o.extLimit = n;
                        }
                    }
                    default -> throw new UsageException("unknown flag: " + name);
                }
            }

            if (dir != null) {
                o.dir = dir;
            }
            return o;
        }

        private static int parseCount(String name, String value) throws UsageException {
            if (!value.matches("[0-9]+")) {
                throw new UsageException("invalid value for " + name + ": " + value);
            }
            BigInteger n = new BigInteger(value);
            return n.min(BigInteger.valueOf(Integer.MAX_VALUE)).intValue();
        }
    }

    // ---- scanning ----------------------------------------------------------

    private record FileEntry(String path, long size) {
    }

    private static final class ExtStat {
        long files;
        long loc;
    }

    private record Summary(long files, long dirs, Map<String, ExtStat> byExt, List<FileEntry> entries) {
    }

    private static Summary scan(Path root) {
        Counter counter = new Counter(root);
        try {
            Files.walkFileTree(root, counter);
        } catch (IOException e) {
            // The visitor never throws IOException; this is unreachable.
            throw new UncheckedIOException(e);
        }
        return counter.summary();
    }

    private static final class Counter extends SimpleFileVisitor<Path> {
        private final Path root;
        private long files;
        private long dirs;
        private final Map<String, ExtStat> byExt = new HashMap<>();
        private final List<FileEntry> entries = new ArrayList<>();

        Counter(Path root) {
            this.root = root;
        }

        @Override
        public FileVisitResult preVisitDirectory(Path dir, BasicFileAttributes attrs) {
            if (dir.equals(root)) {
                return FileVisitResult.CONTINUE;
            }
            if (IGNORED_DIRS.contains(dir.getFileName().toString())) {
                return FileVisitResult.SKIP_SUBTREE;
            }
            dirs++;
            return FileVisitResult.CONTINUE;
        }

        @Override
        public FileVisitResult visitFile(Path file, BasicFileAttributes attrs) {
            // Symlinks are reported with their own attributes (not followed), so
            // they fail this check along with FIFOs, sockets and devices.
            if (!attrs.isRegularFile()) {
                return FileVisitResult.CONTINUE;
            }
            long loc;
            try {
                loc = countLoc(file);
            } catch (IOException e) {
                // Unreadable files are skipped silently.
                return FileVisitResult.CONTINUE;
            }

            files++;
            ExtStat stat = byExt.computeIfAbsent(extension(file.getFileName().toString()), k -> new ExtStat());
            stat.files++;
            stat.loc += loc;
            entries.add(new FileEntry(relative(file), attrs.size()));
            return FileVisitResult.CONTINUE;
        }

        @Override
        public FileVisitResult visitFileFailed(Path file, IOException exc) {
            // Unreadable entries are skipped silently.
            return FileVisitResult.CONTINUE;
        }

        Summary summary() {
            return new Summary(files, dirs, byExt, entries);
        }

        private String relative(Path file) {
            StringBuilder sb = new StringBuilder();
            for (Path part : root.relativize(file)) {
                if (sb.length() > 0) {
                    sb.append('/');
                }
                sb.append(part);
            }
            return sb.toString();
        }
    }

    /** Extension is the text after the last '.', lowercased; "" if there is no such dot. */
    static String extension(String name) {
        int dot = name.lastIndexOf('.');
        if (dot <= 0) {
            return "";
        }
        return name.substring(dot + 1).toLowerCase(Locale.ROOT);
    }

    /** Newline bytes, plus one if the file is non-empty and lacks a trailing newline. */
    static long countLoc(Path file) throws IOException {
        byte[] buf = new byte[1 << 16];
        long newlines = 0;
        long total = 0;
        int last = '\n';
        try (InputStream in = Files.newInputStream(file)) {
            int n;
            while ((n = in.read(buf)) != -1) {
                for (int i = 0; i < n; i++) {
                    if (buf[i] == '\n') {
                        newlines++;
                    }
                }
                total += n;
                last = buf[n - 1];
            }
        }
        boolean missingFinalNewline = total > 0 && last != '\n';
        return newlines + (missingFinalNewline ? 1 : 0);
    }

    // ---- rendering ---------------------------------------------------------

    private record Row(String ext, long files, long loc) {
    }

    static String render(Summary s, Options o) {
        StringBuilder out = new StringBuilder();
        out.append("files: ").append(s.files()).append('\n');
        out.append("dirs: ").append(s.dirs()).append('\n');
        out.append("by extension:\n");

        List<Row> rows = new ArrayList<>();
        s.byExt().forEach((ext, st) -> rows.add(new Row(ext, st.files, st.loc)));
        rows.sort(Comparator.comparingLong(Row::files).reversed().thenComparing(Row::ext, UTF8_ORDER));

        int shown = Math.min(o.extLimit, rows.size());
        List<Row> table = new ArrayList<>(rows.subList(0, shown));
        if (shown < rows.size()) {
            long files = 0;
            long loc = 0;
            for (Row r : rows.subList(shown, rows.size())) {
                files += r.files();
                loc += r.loc();
            }
            table.add(new Row("other", files, loc));
        }
        for (Row r : table) {
            String label = r.ext().isEmpty() ? "(none)" : r.ext();
            out.append("  ").append(label)
                    .append("  files=").append(r.files())
                    .append(" loc=").append(r.loc())
                    .append('\n');
        }

        out.append("largest files:\n");
        List<FileEntry> largest = new ArrayList<>(s.entries());
        largest.sort(Comparator.comparingLong(FileEntry::size).reversed().thenComparing(FileEntry::path, UTF8_ORDER));
        for (FileEntry e : largest.subList(0, Math.min(o.top, largest.size()))) {
            out.append("  ").append(e.size()).append(' ').append(e.path()).append('\n');
        }
        return out.toString();
    }

    // ---- output (always UTF-8 with LF, regardless of platform) -------------

    private static void writeOut(String s) {
        System.out.writeBytes(s.getBytes(StandardCharsets.UTF_8));
    }

    private static void writeErr(String s) {
        System.err.writeBytes(s.getBytes(StandardCharsets.UTF_8));
    }
}
