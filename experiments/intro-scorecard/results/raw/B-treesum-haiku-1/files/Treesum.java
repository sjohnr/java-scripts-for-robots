import java.io.IOException;
import java.io.InputStream;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.FileVisitOption;
import java.nio.file.FileVisitResult;
import java.nio.file.Files;
import java.nio.file.InvalidPathException;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.SimpleFileVisitor;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.EnumSet;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/** treesum: compact repo overview. Behaviour is defined by SPEC.md. */
public final class Treesum {

    static final String USAGE = """
            Usage: treesum [DIR] [--top N] [--ext-limit M]

            Print a compact overview of the tree under DIR: file and directory
            counts, LOC by extension, and the largest files.

            Arguments:
              DIR             directory to scan (default: .)

            Options:
              --top N         number of largest files to list (default: 10)
              --ext-limit M   max distinct extensions in the by-extension table
                              (default: 20); the rest are folded into an
                              "other" row
              --help          show this help and exit

            Ignored directory names (fixed; never descended into, at any depth):
              .git, node_modules, build, .gradle, target, dist, .venv,
              __pycache__, .idea, .vscode

            Symlinks are not followed and are not counted.
            """;

    private static final Set<String> IGNORED_DIRS = Set.of(
            ".git", "node_modules", "build", ".gradle", "target", "dist",
            ".venv", "__pycache__", ".idea", ".vscode");

    private Treesum() {
    }

    public static void main(String[] args) {
        System.exit(run(args));
    }

    static int run(String[] args) {
        Options opts;
        try {
            opts = Options.parse(args);
        } catch (UsageException e) {
            err(e.getMessage() + "\n");
            return 2;
        }
        if (opts.help) {
            out(USAGE);
            return 0;
        }

        Path root;
        try {
            root = Paths.get(opts.dir).toRealPath();
        } catch (IOException | InvalidPathException e) {
            return cannotRead(opts.dir);
        }
        if (!Files.isDirectory(root)) {
            return cannotRead(opts.dir);
        }

        Scan scan = new Scan();
        try {
            Files.walkFileTree(root, EnumSet.noneOf(FileVisitOption.class),
                    Integer.MAX_VALUE, new Walker(root, scan));
        } catch (IOException e) {
            return cannotRead(opts.dir);
        }

        out(render(scan, opts));
        return 0;
    }

    static int cannotRead(String dir) {
        err("error: cannot read " + dir + "\n");
        return 2;
    }

    /** Parsed command line. */
    static final class Options {
        String dir = ".";
        boolean dirGiven;
        long top = 10;
        long extLimit = 20;
        boolean help;

        static Options parse(String[] args) throws UsageException {
            Options o = new Options();
            boolean flagsDone = false;
            for (int i = 0; i < args.length; i++) {
                String arg = args[i];
                if (!flagsDone && arg.equals("--")) {
                    flagsDone = true;
                } else if (!flagsDone && arg.startsWith("-") && arg.length() > 1) {
                    int eq = arg.indexOf('=');
                    String name = eq < 0 ? arg : arg.substring(0, eq);
                    String value = eq < 0 ? null : arg.substring(eq + 1);
                    switch (name) {
                        case "--help":
                            if (value != null) {
                                throw new UsageException("--help takes no value");
                            }
                            o.help = true;
                            return o;
                        case "--top":
                            if (value == null) {
                                value = nextValue(args, ++i, name);
                            }
                            o.top = parseCount(name, value);
                            break;
                        case "--ext-limit":
                            if (value == null) {
                                value = nextValue(args, ++i, name);
                            }
                            o.extLimit = parseCount(name, value);
                            break;
                        default:
                            throw new UsageException("unknown flag: " + name);
                    }
                } else {
                    if (o.dirGiven) {
                        throw new UsageException("unexpected argument: " + arg);
                    }
                    o.dir = arg;
                    o.dirGiven = true;
                }
            }
            return o;
        }

        private static String nextValue(String[] args, int index, String flag)
                throws UsageException {
            if (index >= args.length) {
                throw new UsageException(flag + " requires a value");
            }
            return args[index];
        }

        private static long parseCount(String flag, String value) throws UsageException {
            if (value.matches("[0-9]+")) {
                try {
                    return Long.parseLong(value);
                } catch (NumberFormatException e) {
                    // Too large to represent; reported below as invalid.
                }
            }
            throw new UsageException("invalid value for " + flag + ": " + value);
        }
    }

    static final class UsageException extends Exception {
        private static final long serialVersionUID = 1L;

        UsageException(String message) {
            super(message);
        }
    }

    record FileEntry(String path, long size) {
    }

    /** Accumulated results of one walk. byExt maps extension to {files, loc}. */
    static final class Scan {
        final List<FileEntry> files = new ArrayList<>();
        final Map<String, long[]> byExt = new HashMap<>();
        long dirs;
    }

    static final class Walker extends SimpleFileVisitor<Path> {
        private final Path root;
        private final Scan scan;

        Walker(Path root, Scan scan) {
            this.root = root;
            this.scan = scan;
        }

        @Override
        public FileVisitResult preVisitDirectory(Path dir, BasicFileAttributes attrs) {
            if (dir.equals(root)) {
                return FileVisitResult.CONTINUE;
            }
            if (IGNORED_DIRS.contains(dir.getFileName().toString())) {
                return FileVisitResult.SKIP_SUBTREE;
            }
            scan.dirs++;
            return FileVisitResult.CONTINUE;
        }

        @Override
        public FileVisitResult visitFile(Path file, BasicFileAttributes attrs) {
            // Symlinks (not followed), devices and FIFOs are not counted.
            if (!attrs.isRegularFile()) {
                return FileVisitResult.CONTINUE;
            }
            long loc = countLoc(file);
            String ext = extension(file.getFileName().toString());
            scan.files.add(new FileEntry(relative(root, file), attrs.size()));
            long[] stats = scan.byExt.computeIfAbsent(ext, k -> new long[2]);
            stats[0]++;
            stats[1] += loc;
            return FileVisitResult.CONTINUE;
        }

        // Unreadable entries are skipped silently; the spec defines no other error output.
        @Override
        public FileVisitResult visitFileFailed(Path file, IOException exc) {
            return FileVisitResult.CONTINUE;
        }

        @Override
        public FileVisitResult postVisitDirectory(Path dir, IOException exc) {
            return FileVisitResult.CONTINUE;
        }
    }

    /** Substring after the last '.', lowercased; "" when there is no '.' or only a leading one. */
    static String extension(String name) {
        int dot = name.lastIndexOf('.');
        return dot <= 0 ? "" : name.substring(dot + 1).toLowerCase(Locale.ROOT);
    }

    /** Count of '\n' bytes, plus one if the file is non-empty and lacks a trailing newline. */
    static long countLoc(Path file) {
        try (InputStream in = Files.newInputStream(file)) {
            byte[] buf = new byte[64 * 1024];
            long newlines = 0;
            long total = 0;
            byte last = 0;
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
            return newlines + (total > 0 && last != '\n' ? 1 : 0);
        } catch (IOException e) {
            return 0;
        }
    }

    /** Path relative to root with '/' separators and no leading "./". */
    static String relative(Path root, Path file) {
        StringBuilder sb = new StringBuilder();
        for (Path part : root.relativize(file)) {
            if (sb.length() > 0) {
                sb.append('/');
            }
            sb.append(part.toString());
        }
        return sb.toString();
    }

    record ExtRow(String label, long files, long loc) {
    }

    /**
     * Rows for the by-extension table: files descending, then extension ascending
     * (the empty key sorts first). Beyond the limit, the remainder is folded into a
     * trailing "other" row.
     */
    static List<ExtRow> extRows(Scan scan, long limit) {
        List<Map.Entry<String, long[]>> sorted = new ArrayList<>(scan.byExt.entrySet());
        sorted.sort(Comparator.<Map.Entry<String, long[]>>comparingLong(e -> e.getValue()[0])
                .reversed()
                .thenComparing(Map.Entry::getKey));

        List<ExtRow> rows = new ArrayList<>();
        for (Map.Entry<String, long[]> e : sorted) {
            String label = e.getKey().isEmpty() ? "(none)" : e.getKey();
            rows.add(new ExtRow(label, e.getValue()[0], e.getValue()[1]));
        }
        if (rows.size() <= limit) {
            return rows;
        }

        List<ExtRow> shown = new ArrayList<>(rows.subList(0, (int) limit));
        long files = 0;
        long loc = 0;
        for (ExtRow r : rows.subList((int) limit, rows.size())) {
            files += r.files();
            loc += r.loc();
        }
        shown.add(new ExtRow("other", files, loc));
        return shown;
    }

    static String render(Scan scan, Options opts) {
        StringBuilder sb = new StringBuilder();
        sb.append("files: ").append(scan.files.size()).append('\n');
        sb.append("dirs: ").append(scan.dirs).append('\n');
        sb.append("by extension:\n");
        for (ExtRow row : extRows(scan, opts.extLimit)) {
            sb.append("  ").append(row.label())
                    .append("  files=").append(row.files())
                    .append(" loc=").append(row.loc())
                    .append('\n');
        }
        sb.append("largest files:\n");
        scan.files.sort(Comparator.comparingLong(FileEntry::size).reversed()
                .thenComparing(FileEntry::path));
        int shown = (int) Math.min(opts.top, scan.files.size());
        for (FileEntry f : scan.files.subList(0, shown)) {
            sb.append("  ").append(f.size()).append(' ').append(f.path()).append('\n');
        }
        return sb.toString();
    }

    static void out(String text) {
        write(System.out, text);
    }

    static void err(String text) {
        write(System.err, text);
    }

    private static void write(PrintStream stream, String text) {
        byte[] bytes = text.getBytes(StandardCharsets.UTF_8);
        stream.write(bytes, 0, bytes.length);
        stream.flush();
    }
}
