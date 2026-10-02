import java.io.IOException;
import java.io.InputStream;
import java.io.PrintStream;
import java.math.BigInteger;
import java.nio.charset.StandardCharsets;
import java.nio.file.DirectoryStream;
import java.nio.file.FileVisitOption;
import java.nio.file.FileVisitResult;
import java.nio.file.Files;
import java.nio.file.InvalidPathException;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.SimpleFileVisitor;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.ArrayList;
import java.util.EnumSet;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;

/** treesum: compact overview of a directory tree. Behaviour is defined by SPEC.md. */
public final class Treesum {

    private static final Set<String> IGNORED_DIRS = Set.of(
            ".git", "node_modules", "build", ".gradle", "target",
            "dist", ".venv", "__pycache__", ".idea", ".vscode");

    private static final String NONE_LABEL = "(none)";
    private static final String OTHER_LABEL = "other";
    private static final Pattern NUMBER = Pattern.compile("[0-9]+");
    private static final int DEFAULT_TOP = 10;
    private static final int DEFAULT_EXT_LIMIT = 20;

    private static final String HELP = """
            Usage: treesum [DIR] [--top N] [--ext-limit M]

            Print a compact overview of a directory tree: file and directory
            counts, a by-extension table with line counts, and the largest files.

            Arguments:
              DIR              directory to scan (default: .)

            Options:
              --top N          number of largest files to list (default: 10)
              --ext-limit M    maximum number of extensions shown in the
                               by-extension table; the rest are folded into an
                               "other" row (default: 20)
              --help           show this message and exit

            Ignored directory names (anywhere in the tree, not descended into):
              .git, node_modules, build, .gradle, target, dist, .venv,
              __pycache__, .idea, .vscode

            Symbolic links are not followed; a symlink is not counted.
            """;

    /** Parsed command line. */
    private record Options(boolean help, String dir, int top, int extLimit) {}

    /** A usage error; the message is written to stderr verbatim. */
    private static final class UsageException extends Exception {
        UsageException(String message) {
            super(message);
        }
    }

    /** Per-extension totals. */
    private static final class ExtStat {
        long files;
        long loc;
    }

    /** A counted regular file, relative to the scanned root. */
    private record FileInfo(String path, long size) {}

    /** Everything collected while walking the tree. */
    private static final class Scan {
        long files;
        long dirs;
        final Map<String, ExtStat> byExt = new HashMap<>();
        final List<FileInfo> fileInfos = new ArrayList<>();
    }

    private Treesum() {}

    public static void main(String[] args) {
        System.exit(run(args));
    }

    static int run(String[] args) {
        Options options;
        try {
            options = parse(args);
        } catch (UsageException e) {
            return fail(e.getMessage(), 2);
        }
        if (options.help()) {
            write(System.out, HELP);
            return 0;
        }
        String cannotRead = "error: cannot read " + options.dir();
        try {
            Path root = resolveRoot(options.dir());
            Scan scan = scan(root);
            write(System.out, render(scan, options.top(), options.extLimit()));
            return 0;
        } catch (IOException | InvalidPathException e) {
            return fail(cannotRead, 2);
        }
    }

    static Options parse(String[] args) throws UsageException {
        String dir = null;
        int top = DEFAULT_TOP;
        int extLimit = DEFAULT_EXT_LIMIT;
        boolean endOfFlags = false;

        for (int i = 0; i < args.length; i++) {
            String arg = args[i];
            if (!endOfFlags && arg.equals("--")) {
                endOfFlags = true;
                continue;
            }
            if (endOfFlags || !isFlag(arg)) {
                if (dir != null) {
                    throw new UsageException("error: unexpected argument: " + arg);
                }
                dir = arg;
                continue;
            }

            String name = arg;
            String inline = null;
            int eq = arg.indexOf('=');
            if (arg.startsWith("--") && eq >= 0) {
                name = arg.substring(0, eq);
                inline = arg.substring(eq + 1);
            }

            switch (name) {
                case "--help" -> {
                    if (inline != null) {
                        throw unknownFlag(arg);
                    }
                    return new Options(true, ".", top, extLimit);
                }
                case "--top" -> {
                    String value = valueFor(name, inline, args, i);
                    if (inline == null) {
                        i++;
                    }
                    top = parseCount(name, value);
                }
                case "--ext-limit" -> {
                    String value = valueFor(name, inline, args, i);
                    if (inline == null) {
                        i++;
                    }
                    extLimit = parseCount(name, value);
                }
                default -> throw unknownFlag(name);
            }
        }
        return new Options(false, dir == null ? "." : dir, top, extLimit);
    }

    private static boolean isFlag(String arg) {
        return arg.startsWith("-") && !arg.equals("-");
    }

    private static UsageException unknownFlag(String flag) {
        return new UsageException("unknown flag: " + flag);
    }

    private static String valueFor(String flag, String inline, String[] args, int i)
            throws UsageException {
        if (inline != null) {
            return inline;
        }
        if (i + 1 < args.length) {
            return args[i + 1];
        }
        throw new UsageException("error: missing value for " + flag);
    }

    private static int parseCount(String flag, String value) throws UsageException {
        if (!NUMBER.matcher(value).matches()) {
            throw new UsageException("error: invalid value for " + flag + ": " + value);
        }
        BigInteger n = new BigInteger(value);
        return n.min(BigInteger.valueOf(Integer.MAX_VALUE)).intValue();
    }

    /** Resolves DIR (following a symlink given as the root itself) and checks it can be listed. */
    static Path resolveRoot(String dir) throws IOException {
        Path root = Paths.get(dir).toRealPath();
        if (!Files.isDirectory(root)) {
            throw new java.nio.file.NotDirectoryException(dir);
        }
        try (DirectoryStream<Path> ignored = Files.newDirectoryStream(root)) {
            // Listing once proves the directory is readable.
        }
        return root;
    }

    static Scan scan(Path root) throws IOException {
        Scan scan = new Scan();
        Files.walkFileTree(root, EnumSet.noneOf(FileVisitOption.class), Integer.MAX_VALUE,
                new SimpleFileVisitor<Path>() {
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
                        // Symlinks (not followed) and other non-regular entries are not counted.
                        if (!attrs.isRegularFile()) {
                            return FileVisitResult.CONTINUE;
                        }
                        long loc;
                        try {
                            loc = countLoc(file);
                        } catch (IOException e) {
                            // Unreadable file: skipped silently.
                            return FileVisitResult.CONTINUE;
                        }
                        String ext = extensionOf(file.getFileName().toString());
                        ExtStat stat = scan.byExt.computeIfAbsent(ext, k -> new ExtStat());
                        stat.files++;
                        stat.loc += loc;
                        scan.files++;
                        scan.fileInfos.add(new FileInfo(relativePath(root, file), attrs.size()));
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

    /** Number of '\n' bytes, plus one if the file is non-empty and does not end with '\n'. */
    static long countLoc(Path file) throws IOException {
        long newlines = 0;
        long total = 0;
        int last = -1;
        byte[] buf = new byte[64 * 1024];
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
        if (total > 0 && last != '\n') {
            newlines++;
        }
        return newlines;
    }

    /** Substring after the last '.', lowercased; "" if there is no '.' or the only '.' leads the name. */
    static String extensionOf(String name) {
        int dot = name.lastIndexOf('.');
        if (dot <= 0) {
            return "";
        }
        return name.substring(dot + 1).toLowerCase(Locale.ROOT);
    }

    static String relativePath(Path root, Path file) {
        StringBuilder sb = new StringBuilder();
        for (Path part : root.relativize(file)) {
            if (sb.length() > 0) {
                sb.append('/');
            }
            sb.append(part);
        }
        return sb.toString();
    }

    static String render(Scan scan, int top, int extLimit) {
        StringBuilder out = new StringBuilder();
        out.append("files: ").append(scan.files).append('\n');
        out.append("dirs: ").append(scan.dirs).append('\n');
        out.append("by extension:\n");

        List<Map.Entry<String, ExtStat>> exts = new ArrayList<>(scan.byExt.entrySet());
        exts.sort((a, b) -> {
            int c = Long.compare(b.getValue().files, a.getValue().files);
            return c != 0 ? c : compareCodePoints(a.getKey(), b.getKey());
        });

        int shown = Math.min(extLimit, exts.size());
        for (int i = 0; i < shown; i++) {
            Map.Entry<String, ExtStat> e = exts.get(i);
            String label = e.getKey().isEmpty() ? NONE_LABEL : e.getKey();
            appendExtRow(out, label, e.getValue());
        }
        if (exts.size() > shown) {
            ExtStat other = new ExtStat();
            for (Map.Entry<String, ExtStat> e : exts.subList(shown, exts.size())) {
                other.files += e.getValue().files;
                other.loc += e.getValue().loc;
            }
            appendExtRow(out, OTHER_LABEL, other);
        }

        out.append("largest files:\n");
        List<FileInfo> largest = new ArrayList<>(scan.fileInfos);
        largest.sort((a, b) -> {
            int c = Long.compare(b.size(), a.size());
            return c != 0 ? c : compareCodePoints(a.path(), b.path());
        });
        for (FileInfo f : largest.subList(0, Math.min(top, largest.size()))) {
            out.append("  ").append(f.size()).append(' ').append(f.path()).append('\n');
        }
        return out.toString();
    }

    private static void appendExtRow(StringBuilder out, String label, ExtStat stat) {
        out.append("  ").append(label)
                .append("  files=").append(stat.files)
                .append(" loc=").append(stat.loc)
                .append('\n');
    }

    /** Orders strings by Unicode code point, which matches UTF-8 byte order; "" sorts first. */
    static int compareCodePoints(String a, String b) {
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

    private static void write(PrintStream stream, String text) {
        byte[] bytes = text.getBytes(StandardCharsets.UTF_8);
        stream.write(bytes, 0, bytes.length);
        stream.flush();
    }

    private static int fail(String message, int code) {
        write(System.err, message + "\n");
        return code;
    }
}
