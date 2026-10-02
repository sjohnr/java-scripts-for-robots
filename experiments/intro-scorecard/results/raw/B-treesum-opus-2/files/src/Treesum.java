import java.io.IOException;
import java.io.InputStream;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.FileVisitResult;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.SimpleFileVisitor;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/** treesum: compact repo overview. See SPEC.md. */
public final class Treesum {

    static final Set<String> IGNORED_DIRS = Set.of(
            ".git", "node_modules", "build", ".gradle", "target",
            "dist", ".venv", "__pycache__", ".idea", ".vscode");

    static final String HELP = String.join("\n",
            "Usage: treesum [DIR] [--top N] [--ext-limit M]",
            "",
            "Print a compact overview of a directory tree: file and directory counts,",
            "files and lines of code per extension, and the largest files.",
            "",
            "Arguments:",
            "  DIR             directory to summarize (default: .)",
            "",
            "Options:",
            "  --top N         number of largest files to list (default: 10)",
            "  --ext-limit M   max distinct extensions shown; the rest are folded",
            "                  into an 'other' row (default: 20)",
            "  --help          show this help and exit",
            "",
            "Ignored directories (fixed, not configurable, matched anywhere in the tree):",
            "  .git node_modules build .gradle target dist .venv __pycache__ .idea .vscode",
            "",
            "Symlinks are not followed and not counted.",
            "");

    /** Thrown for usage errors; message goes to stderr verbatim, exit 2. */
    static final class UsageException extends Exception {
        UsageException(String message) {
            super(message);
        }
    }

    record FileEntry(String path, long size) {}

    static final class ExtStats {
        long files;
        long loc;
    }

    public static void main(String[] args) {
        PrintStream out = new PrintStream(System.out, false, StandardCharsets.UTF_8);
        PrintStream err = new PrintStream(System.err, false, StandardCharsets.UTF_8);
        int code;
        try {
            code = run(args, out, err);
        } catch (Throwable t) {
            // Only exit codes 0 and 2 are permitted.
            out.flush();
            err.print("error: " + oneLine(String.valueOf(t.getMessage())) + "\n");
            code = 2;
        }
        out.flush();
        err.flush();
        System.exit(code);
    }

    static int run(String[] args, PrintStream out, PrintStream err) throws IOException {
        String dirArg = null;
        int top = 10;
        int extLimit = 20;
        try {
            for (int i = 0; i < args.length; i++) {
                String a = args[i];
                String flag = a;
                String inlineValue = null;
                if (a.startsWith("--") && a.contains("=")) {
                    flag = a.substring(0, a.indexOf('='));
                    inlineValue = a.substring(a.indexOf('=') + 1);
                }
                switch (flag) {
                    case "--help" -> {
                        if (inlineValue != null) {
                            throw new UsageException("unknown flag: " + a);
                        }
                        out.print(HELP);
                        return 0;
                    }
                    case "--top", "--ext-limit" -> {
                        String value;
                        if (inlineValue != null) {
                            value = inlineValue;
                        } else if (i + 1 < args.length) {
                            value = args[++i];
                        } else {
                            throw new UsageException("missing value for " + flag);
                        }
                        int n = parseCount(flag, value);
                        if (flag.equals("--top")) {
                            top = n;
                        } else {
                            extLimit = n;
                        }
                    }
                    default -> {
                        if (a.startsWith("-") && !a.equals("-")) {
                            throw new UsageException("unknown flag: " + a);
                        }
                        if (dirArg != null) {
                            throw new UsageException("unexpected argument: " + a);
                        }
                        dirArg = a;
                    }
                }
            }
        } catch (UsageException e) {
            err.print(oneLine(e.getMessage()) + "\n");
            return 2;
        }

        if (dirArg == null) {
            dirArg = ".";
        }
        Path root;
        try {
            root = Paths.get(dirArg);
        } catch (RuntimeException e) {
            err.print("error: cannot read " + oneLine(dirArg) + "\n");
            return 2;
        }
        if (!Files.isDirectory(root) || !Files.isReadable(root)) {
            err.print("error: cannot read " + oneLine(dirArg) + "\n");
            return 2;
        }

        Scan scan = scan(root);
        out.print(render(scan, top, extLimit));
        return 0;
    }

    static int parseCount(String flag, String value) throws UsageException {
        if (value.isEmpty() || !value.chars().allMatch(c -> c >= '0' && c <= '9')) {
            throw new UsageException("invalid value for " + flag + ": " + value);
        }
        try {
            return Integer.parseInt(value);
        } catch (NumberFormatException e) {
            // Absurdly large values behave as "unlimited".
            return Integer.MAX_VALUE;
        }
    }

    static final class Scan {
        long files;
        long dirs;
        final Map<String, ExtStats> byExt = new HashMap<>();
        final List<FileEntry> entries = new ArrayList<>();
    }

    static Scan scan(Path root) throws IOException {
        Scan scan = new Scan();
        // Resolve the root itself (it may be a symlink the user passed explicitly);
        // nothing below it is followed.
        Path start = root.toRealPath();
        Files.walkFileTree(start, new SimpleFileVisitor<>() {
            @Override
            public FileVisitResult preVisitDirectory(Path dir, BasicFileAttributes attrs) {
                if (dir.equals(start)) {
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
                // Symlinks, sockets, FIFOs, devices are not counted.
                if (!attrs.isRegularFile()) {
                    return FileVisitResult.CONTINUE;
                }
                long loc;
                try {
                    loc = countLoc(file);
                } catch (IOException e) {
                    return FileVisitResult.CONTINUE; // unreadable: silently skipped
                }
                scan.files++;
                ExtStats s = scan.byExt.computeIfAbsent(extension(file.getFileName().toString()), k -> new ExtStats());
                s.files++;
                s.loc += loc;
                scan.entries.add(new FileEntry(relative(start, file), attrs.size()));
                return FileVisitResult.CONTINUE;
            }

            @Override
            public FileVisitResult visitFileFailed(Path file, IOException exc) {
                // A directory we cannot open still exists and is not ignored, so it counts.
                if (Files.isDirectory(file, LinkOption.NOFOLLOW_LINKS)
                        && !IGNORED_DIRS.contains(file.getFileName().toString())) {
                    scan.dirs++;
                }
                return FileVisitResult.CONTINUE;
            }

            @Override
            public FileVisitResult postVisitDirectory(Path dir, IOException exc) {
                return FileVisitResult.CONTINUE;
            }
        });
        return scan;
    }

    static String render(Scan scan, int top, int extLimit) {
        StringBuilder sb = new StringBuilder();
        sb.append("files: ").append(scan.files).append('\n');
        sb.append("dirs: ").append(scan.dirs).append('\n');

        sb.append("by extension:\n");
        List<Map.Entry<String, ExtStats>> exts = new ArrayList<>(scan.byExt.entrySet());
        exts.sort(Comparator.<Map.Entry<String, ExtStats>>comparingLong(e -> -e.getValue().files)
                .thenComparing(Map.Entry::getKey, Treesum::compareCodePoints));
        int shown = Math.min(extLimit, exts.size());
        for (int i = 0; i < shown; i++) {
            Map.Entry<String, ExtStats> e = exts.get(i);
            appendExtRow(sb, e.getKey().isEmpty() ? "(none)" : e.getKey(), e.getValue());
        }
        if (shown < exts.size()) {
            ExtStats other = new ExtStats();
            for (int i = shown; i < exts.size(); i++) {
                other.files += exts.get(i).getValue().files;
                other.loc += exts.get(i).getValue().loc;
            }
            appendExtRow(sb, "other", other);
        }

        sb.append("largest files:\n");
        List<FileEntry> files = new ArrayList<>(scan.entries);
        files.sort(Comparator.comparingLong((FileEntry f) -> -f.size())
                .thenComparing(FileEntry::path, Treesum::compareCodePoints));
        for (int i = 0; i < Math.min(top, files.size()); i++) {
            FileEntry f = files.get(i);
            sb.append("  ").append(f.size()).append(' ').append(oneLine(f.path())).append('\n');
        }
        return sb.toString();
    }

    static void appendExtRow(StringBuilder sb, String label, ExtStats s) {
        sb.append("  ").append(oneLine(label))
                .append("  files=").append(s.files)
                .append(" loc=").append(s.loc).append('\n');
    }

    static String extension(String name) {
        int dot = name.lastIndexOf('.');
        if (dot <= 0) {
            return ""; // no dot, or a dotfile like .gitignore
        }
        return name.substring(dot + 1).toLowerCase(Locale.ROOT);
    }

    static long countLoc(Path file) throws IOException {
        long newlines = 0;
        long total = 0;
        int last = -1;
        byte[] buf = new byte[1 << 16];
        try (InputStream in = Files.newInputStream(file, LinkOption.NOFOLLOW_LINKS)) {
            int n;
            while ((n = in.read(buf)) > 0) {
                for (int i = 0; i < n; i++) {
                    if (buf[i] == '\n') {
                        newlines++;
                    }
                }
                total += n;
                last = buf[n - 1];
            }
        }
        return (total > 0 && last != '\n') ? newlines + 1 : newlines;
    }

    static String relative(Path root, Path file) {
        Path rel = root.relativize(file);
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < rel.getNameCount(); i++) {
            if (i > 0) {
                sb.append('/');
            }
            sb.append(rel.getName(i).toString());
        }
        return sb.toString();
    }

    /** Compare by Unicode code point (equivalent to UTF-8 byte order). */
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
        return Integer.compare(a.length() - i, b.length() - j);
    }

    /** Keep every output record on a single line (filenames may contain CR/LF). */
    static String oneLine(String s) {
        return s.replace("\r", "?").replace("\n", "?");
    }
}
