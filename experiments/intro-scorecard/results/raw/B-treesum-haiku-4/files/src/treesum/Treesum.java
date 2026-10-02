package treesum;

import java.io.IOException;
import java.io.InputStream;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.FileVisitResult;
import java.nio.file.Files;
import java.nio.file.InvalidPathException;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.SimpleFileVisitor;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.StringJoiner;

/** Compact overview of a directory tree. The behaviour is defined by SPEC.md. */
public final class Treesum {

    private static final Set<String> IGNORED_DIRS = Set.of(
            ".git", "node_modules", "build", ".gradle", "target", "dist",
            ".venv", "__pycache__", ".idea", ".vscode");

    private static final String USAGE = """
            Usage: treesum [DIR] [--top N] [--ext-limit M]

              DIR              directory to summarize (default: .)
              --top N          number of largest files to list (default: 10)
              --ext-limit M    number of extensions shown in the by-extension
                               table; the rest are folded into "other" (default: 20)
              --help           show this message and exit

            Directory names ignored anywhere in the tree (never descended into):
              .git, node_modules, build, .gradle, target, dist, .venv,
              __pycache__, .idea, .vscode
            """;

    private Treesum() {
    }

    public static void main(String[] args) {
        PrintStream out = new PrintStream(System.out, false, StandardCharsets.UTF_8);
        PrintStream err = new PrintStream(System.err, false, StandardCharsets.UTF_8);
        int code = run(args, out, err);
        out.flush();
        err.flush();
        System.exit(code);
    }

    private static int run(String[] args, PrintStream out, PrintStream err) {
        Options opts;
        try {
            opts = parse(args);
        } catch (UsageException e) {
            err.print(e.getMessage() + "\n");
            return 2;
        }
        if (opts.help()) {
            out.print(USAGE);
            return 0;
        }

        String dirArg = opts.dir() == null ? "." : opts.dir();
        Path root;
        try {
            root = Paths.get(dirArg).toRealPath();
            if (!Files.isDirectory(root) || !Files.isReadable(root)) {
                throw new IOException("not a readable directory");
            }
        } catch (IOException | InvalidPathException e) {
            err.print("error: cannot read " + dirArg + "\n");
            return 2;
        }

        Scan scan;
        try {
            scan = scan(root);
        } catch (IOException e) {
            err.print("error: cannot read " + dirArg + "\n");
            return 2;
        }

        out.print(render(scan, opts.top(), opts.extLimit()));
        return 0;
    }

    // ---- argument parsing -------------------------------------------------

    private record Options(String dir, long top, long extLimit, boolean help) {
    }

    /** A usage error; the message is written to stderr verbatim. */
    private static final class UsageException extends Exception {
        UsageException(String message) {
            super(message);
        }
    }

    private static Options parse(String[] args) throws UsageException {
        String dir = null;
        long top = 10;
        long extLimit = 20;
        boolean endOfFlags = false;

        for (int i = 0; i < args.length; i++) {
            String arg = args[i];
            if (endOfFlags || !arg.startsWith("-") || arg.equals("-")) {
                if (dir != null) {
                    throw new UsageException("unexpected argument: " + arg);
                }
                dir = arg;
                continue;
            }
            if (arg.equals("--")) {
                endOfFlags = true;
                continue;
            }

            String flag = arg;
            String inline = null;
            int eq = arg.indexOf('=');
            if (eq >= 0) {
                flag = arg.substring(0, eq);
                inline = arg.substring(eq + 1);
            }

            switch (flag) {
                case "--help" -> {
                    return new Options(dir, top, extLimit, true);
                }
                case "--top", "--ext-limit" -> {
                    String value = inline;
                    if (value == null) {
                        if (++i >= args.length) {
                            throw new UsageException(flag + " requires a value");
                        }
                        value = args[i];
                    }
                    long n = parseCount(flag, value);
                    if (flag.equals("--top")) {
                        top = n;
                    } else {
                        extLimit = n;
                    }
                }
                default -> throw new UsageException("unknown flag: " + flag);
            }
        }
        return new Options(dir, top, extLimit, false);
    }

    private static long parseCount(String flag, String value) throws UsageException {
        try {
            if (value.matches("[0-9]+")) {
                return Long.parseLong(value);
            }
        } catch (NumberFormatException e) {
            // out of range; reported below
        }
        throw new UsageException("invalid value for " + flag + ": " + value);
    }

    // ---- scanning ---------------------------------------------------------

    private record FileEntry(String path, long size) {
    }

    /** Accumulated results of one walk. */
    private static final class Scan {
        long files;
        long dirs;
        /** extension -> {file count, LOC} */
        final Map<String, long[]> byExt = new HashMap<>();
        final List<FileEntry> entries = new ArrayList<>();

        void addFile(String path, String ext, long size, long loc) {
            files++;
            long[] totals = byExt.computeIfAbsent(ext, k -> new long[2]);
            totals[0]++;
            totals[1] += loc;
            entries.add(new FileEntry(path, size));
        }
    }

    private static Scan scan(Path root) throws IOException {
        Scan scan = new Scan();
        Files.walkFileTree(root, new SimpleFileVisitor<Path>() {
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
                // Symlinks are not followed, so attrs describe the link itself and are skipped here.
                if (!attrs.isRegularFile()) {
                    return FileVisitResult.CONTINUE;
                }
                long loc;
                try {
                    loc = countLoc(file);
                } catch (IOException e) {
                    // Unreadable file: not counted.
                    return FileVisitResult.CONTINUE;
                }
                scan.addFile(relativePath(root, file),
                        extensionOf(file.getFileName().toString()), attrs.size(), loc);
                return FileVisitResult.CONTINUE;
            }

            @Override
            public FileVisitResult visitFileFailed(Path file, IOException exc) {
                return FileVisitResult.CONTINUE;
            }

            @Override
            public FileVisitResult postVisitDirectory(Path dir, IOException exc) {
                return FileVisitResult.CONTINUE;
            }
        });
        return scan;
    }

    /** Lowercased substring after the last '.'; "" when there is no '.' or only a leading one. */
    private static String extensionOf(String name) {
        int dot = name.lastIndexOf('.');
        if (dot <= 0) {
            return "";
        }
        return name.substring(dot + 1).toLowerCase(Locale.ROOT);
    }

    /** Count of '\n' bytes, plus one if the file is non-empty and does not end with '\n'. */
    private static long countLoc(Path file) throws IOException {
        try (InputStream in = Files.newInputStream(file)) {
            byte[] buf = new byte[64 * 1024];
            long newlines = 0;
            int last = -1;
            int n;
            while ((n = in.read(buf)) != -1) {
                for (int i = 0; i < n; i++) {
                    if (buf[i] == '\n') {
                        newlines++;
                    }
                }
                last = buf[n - 1];
            }
            if (last == -1) {
                return 0;
            }
            return last == '\n' ? newlines : newlines + 1;
        }
    }

    private static String relativePath(Path root, Path file) {
        StringJoiner joiner = new StringJoiner("/");
        for (Path part : root.relativize(file)) {
            joiner.add(part.toString());
        }
        return joiner.toString();
    }

    // ---- report -----------------------------------------------------------

    private record ExtRow(String ext, long files, long loc) {
    }

    private static String render(Scan scan, long top, long extLimit) {
        StringBuilder sb = new StringBuilder();
        sb.append("files: ").append(scan.files).append('\n');
        sb.append("dirs: ").append(scan.dirs).append('\n');

        sb.append("by extension:\n");
        for (ExtRow row : extRows(scan, extLimit)) {
            String label = row.ext().isEmpty() ? "(none)" : row.ext();
            sb.append("  ").append(label)
                    .append("  files=").append(row.files())
                    .append(" loc=").append(row.loc())
                    .append('\n');
        }

        sb.append("largest files:\n");
        List<FileEntry> largest = new ArrayList<>(scan.entries);
        largest.sort((a, b) -> a.size() != b.size()
                ? Long.compare(b.size(), a.size())
                : compareCodePoints(a.path(), b.path()));
        int shown = (int) Math.min(top, largest.size());
        for (FileEntry entry : largest.subList(0, shown)) {
            sb.append("  ").append(entry.size()).append(' ').append(entry.path()).append('\n');
        }
        return sb.toString();
    }

    /** Rows sorted by file count desc, then extension asc (empty extension first); folded to "other" past the limit. */
    private static List<ExtRow> extRows(Scan scan, long extLimit) {
        List<ExtRow> rows = new ArrayList<>();
        scan.byExt.forEach((ext, totals) -> rows.add(new ExtRow(ext, totals[0], totals[1])));
        rows.sort((a, b) -> a.files() != b.files()
                ? Long.compare(b.files(), a.files())
                : compareCodePoints(a.ext(), b.ext()));

        if (rows.size() <= extLimit) {
            return rows;
        }
        int limit = (int) extLimit;
        List<ExtRow> shown = new ArrayList<>(rows.subList(0, limit));
        long files = 0;
        long loc = 0;
        for (ExtRow row : rows.subList(limit, rows.size())) {
            files += row.files();
            loc += row.loc();
        }
        shown.add(new ExtRow("other", files, loc));
        return shown;
    }

    /** Orders strings by Unicode code point, which matches UTF-8 byte order. */
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
        return Boolean.compare(i < a.length(), j < b.length());
    }
}
