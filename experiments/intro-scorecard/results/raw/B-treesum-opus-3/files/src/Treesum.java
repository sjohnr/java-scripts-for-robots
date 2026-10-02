import java.io.IOException;
import java.io.InputStream;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.Deque;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/** treesum: compact repo overview. See SPEC.md. */
public final class Treesum {

    static final List<String> IGNORED_DIRS = List.of(
            ".git", "node_modules", "build", ".gradle", "target",
            "dist", ".venv", "__pycache__", ".idea", ".vscode");
    static final Set<String> IGNORED = Set.copyOf(IGNORED_DIRS);

    static final int EXIT_OK = 0;
    static final int EXIT_USAGE = 2;

    /** Thrown for any usage error; message is printed verbatim to stderr. */
    static final class UsageException extends Exception {
        UsageException(String message) {
            super(message);
        }
    }

    record Options(String dir, long top, long extLimit, boolean help) {}

    record FileEntry(String path, long size) {}

    static final class ExtStats {
        final String ext;
        long files;
        long loc;

        ExtStats(String ext) {
            this.ext = ext;
        }
    }

    public static void main(String[] args) {
        PrintStream out = new PrintStream(new java.io.FileOutputStream(java.io.FileDescriptor.out), false, StandardCharsets.UTF_8);
        PrintStream err = new PrintStream(new java.io.FileOutputStream(java.io.FileDescriptor.err), true, StandardCharsets.UTF_8);
        int code = run(args, out, err);
        out.flush();
        err.flush();
        System.exit(code);
    }

    static int run(String[] args, PrintStream out, PrintStream err) {
        Options opts;
        try {
            opts = parseArgs(args);
        } catch (UsageException e) {
            err.print(e.getMessage() + "\n");
            return EXIT_USAGE;
        }
        if (opts.help()) {
            out.print(usage());
            return EXIT_OK;
        }

        Path root = Paths.get(opts.dir());
        if (!Files.isDirectory(root) || !Files.isReadable(root)) {
            err.print("error: cannot read " + opts.dir() + "\n");
            return EXIT_USAGE;
        }

        StringBuilder sb = new StringBuilder();
        try {
            summarize(root, opts, sb);
        } catch (IOException e) {
            // Root directory could not be listed.
            err.print("error: cannot read " + opts.dir() + "\n");
            return EXIT_USAGE;
        }
        out.print(sb);
        return EXIT_OK;
    }

    static String usage() {
        return "Usage: treesum [DIR] [--top N] [--ext-limit M]\n"
                + "\n"
                + "Print a compact overview of a directory tree: file and directory\n"
                + "counts, files and lines of code per extension, and the largest files.\n"
                + "\n"
                + "Arguments:\n"
                + "  DIR            directory to summarize (default: .)\n"
                + "\n"
                + "Flags:\n"
                + "  --top N        number of largest files to list (default: 10)\n"
                + "  --ext-limit M  max distinct extensions shown; the rest are folded\n"
                + "                 into an 'other' row (default: 20)\n"
                + "  --help         show this help and exit\n"
                + "\n"
                + "Ignored directories (fixed, anywhere in the tree):\n"
                + "  " + String.join(", ", IGNORED_DIRS) + "\n"
                + "Symlinks are not followed or counted.\n";
    }

    static Options parseArgs(String[] args) throws UsageException {
        String dir = null;
        long top = 10;
        long extLimit = 20;
        boolean help = false;
        boolean endOfFlags = false;

        for (int i = 0; i < args.length; i++) {
            String a = args[i];
            if (!endOfFlags && a.equals("--")) {
                endOfFlags = true;
                continue;
            }
            if (!endOfFlags && a.startsWith("-") && !a.equals("-")) {
                String name = a;
                String value = null;
                int eq = a.indexOf('=');
                if (a.startsWith("--") && eq > 0) {
                    name = a.substring(0, eq);
                    value = a.substring(eq + 1);
                }
                switch (name) {
                    case "--help" -> {
                        if (value != null) {
                            throw new UsageException("unknown flag: " + a);
                        }
                        help = true;
                    }
                    case "--top", "--ext-limit" -> {
                        if (value == null) {
                            if (i + 1 >= args.length) {
                                throw new UsageException("missing value for " + name);
                            }
                            value = args[++i];
                        }
                        long n = parseCount(name, value);
                        if (name.equals("--top")) {
                            top = n;
                        } else {
                            extLimit = n;
                        }
                    }
                    default -> throw new UsageException("unknown flag: " + name);
                }
                continue;
            }
            if (dir != null) {
                throw new UsageException("unexpected argument: " + a);
            }
            dir = a;
        }
        return new Options(dir == null ? "." : dir, top, extLimit, help);
    }

    static long parseCount(String flag, String value) throws UsageException {
        if (value.isEmpty() || !value.chars().allMatch(c -> c >= '0' && c <= '9')) {
            throw new UsageException("invalid value for " + flag + ": " + value);
        }
        try {
            return Long.parseLong(value);
        } catch (NumberFormatException e) {
            return Long.MAX_VALUE; // absurdly large but valid: means "no limit"
        }
    }

    static void summarize(Path root, Options opts, StringBuilder sb) throws IOException {
        long fileCount = 0;
        long dirCount = 0;
        Map<String, ExtStats> byExt = new HashMap<>();
        List<FileEntry> files = new ArrayList<>();

        // Each entry: absolute-ish Path on disk, and its relative path ("" for root).
        Deque<Object[]> stack = new ArrayDeque<>();
        stack.push(new Object[] {root, ""});
        boolean isRoot = true;

        while (!stack.isEmpty()) {
            Object[] item = stack.pop();
            Path dirPath = (Path) item[0];
            String rel = (String) item[1];

            List<Path> children = new ArrayList<>();
            try (DirectoryStream<Path> ds = Files.newDirectoryStream(dirPath)) {
                for (Path p : ds) {
                    children.add(p);
                }
            } catch (IOException | SecurityException e) {
                if (isRoot) {
                    throw e instanceof IOException io ? io : new IOException(e);
                }
                // Unreadable subdirectory: still counted as a dir, contents skipped.
                continue;
            } finally {
                isRoot = false;
            }

            for (Path child : children) {
                String name = child.getFileName().toString();
                String childRel = rel.isEmpty() ? name : rel + "/" + name;
                BasicFileAttributes attrs;
                try {
                    attrs = Files.readAttributes(child, BasicFileAttributes.class, LinkOption.NOFOLLOW_LINKS);
                } catch (IOException | SecurityException e) {
                    continue;
                }
                if (attrs.isSymbolicLink()) {
                    continue;
                }
                if (attrs.isDirectory()) {
                    if (IGNORED.contains(name)) {
                        continue;
                    }
                    dirCount++;
                    stack.push(new Object[] {child, childRel});
                } else if (attrs.isRegularFile()) {
                    long loc;
                    try {
                        loc = countLoc(child);
                    } catch (IOException | SecurityException e) {
                        // Unreadable file: skipped entirely (nothing may be printed).
                        continue;
                    }
                    fileCount++;
                    String ext = extensionOf(name);
                    ExtStats s = byExt.computeIfAbsent(ext, ExtStats::new);
                    s.files++;
                    s.loc += loc;
                    files.add(new FileEntry(childRel, attrs.size()));
                }
                // Other file types (fifos, sockets, devices) are not counted.
            }
        }

        sb.append("files: ").append(fileCount).append('\n');
        sb.append("dirs: ").append(dirCount).append('\n');

        sb.append("by extension:\n");
        List<ExtStats> exts = new ArrayList<>(byExt.values());
        exts.sort(Comparator.<ExtStats>comparingLong(s -> -s.files)
                .thenComparing((a, b) -> compareUtf8(a.ext, b.ext)));
        int shown = (int) Math.min(exts.size(), opts.extLimit());
        for (int i = 0; i < shown; i++) {
            ExtStats s = exts.get(i);
            appendExtRow(sb, s.ext.isEmpty() ? "(none)" : s.ext, s.files, s.loc);
        }
        if (shown < exts.size()) {
            long otherFiles = 0;
            long otherLoc = 0;
            for (int i = shown; i < exts.size(); i++) {
                otherFiles += exts.get(i).files;
                otherLoc += exts.get(i).loc;
            }
            appendExtRow(sb, "other", otherFiles, otherLoc);
        }

        sb.append("largest files:\n");
        files.sort(Comparator.<FileEntry>comparingLong(f -> -f.size())
                .thenComparing((a, b) -> compareUtf8(a.path(), b.path())));
        int topN = (int) Math.min(files.size(), opts.top());
        for (int i = 0; i < topN; i++) {
            FileEntry f = files.get(i);
            sb.append("  ").append(f.size()).append(' ').append(f.path()).append('\n');
        }
    }

    static void appendExtRow(StringBuilder sb, String label, long files, long loc) {
        sb.append("  ").append(label).append("  files=").append(files)
                .append(" loc=").append(loc).append('\n');
    }

    static String extensionOf(String name) {
        int dot = name.lastIndexOf('.');
        if (dot <= 0) {
            // No dot, or a dotfile like ".gitignore" with no other dot.
            return "";
        }
        return name.substring(dot + 1).toLowerCase(Locale.ROOT);
    }

    /** Byte-wise comparison of UTF-8 encodings (same order as code point order). */
    static int compareUtf8(String a, String b) {
        return Arrays.compareUnsigned(a.getBytes(StandardCharsets.UTF_8), b.getBytes(StandardCharsets.UTF_8));
    }

    static long countLoc(Path file) throws IOException {
        long newlines = 0;
        long total = 0;
        int last = -1;
        byte[] buf = new byte[64 * 1024];
        try (InputStream in = Files.newInputStream(file)) {
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
        if (total > 0 && last != '\n') {
            newlines++;
        }
        return newlines;
    }
}
