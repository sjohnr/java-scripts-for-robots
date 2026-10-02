package treesum;

import java.io.IOException;
import java.io.InputStream;
import java.io.PrintStream;
import java.io.UnsupportedEncodingException;
import java.nio.charset.StandardCharsets;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.Deque;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/** treesum: compact repo overview. See SPEC.md. */
public final class Main {

    static final List<String> IGNORED_DIRS = List.of(
            ".git", "node_modules", "build", ".gradle", "target",
            "dist", ".venv", "__pycache__", ".idea", ".vscode");
    static final Set<String> IGNORED = Set.copyOf(IGNORED_DIRS);

    static final int EXIT_OK = 0;
    static final int EXIT_USAGE = 2;

    /** Thrown for any usage error; message goes to stderr verbatim. */
    static final class UsageException extends Exception {
        UsageException(String message) {
            super(message);
        }
    }

    static final class Options {
        String dir = ".";
        int top = 10;
        int extLimit = 20;
        boolean help;
    }

    static final class ExtStats {
        final String ext;
        long files;
        long loc;

        ExtStats(String ext) {
            this.ext = ext;
        }
    }

    record FileEntry(String path, long size) {}

    public static void main(String[] args) {
        PrintStream out = utf8(System.out);
        PrintStream err = utf8(System.err);
        int code = run(args, out, err);
        out.flush();
        err.flush();
        System.exit(code);
    }

    static PrintStream utf8(PrintStream base) {
        try {
            return new PrintStream(base, false, StandardCharsets.UTF_8.name());
        } catch (UnsupportedEncodingException e) {
            throw new AssertionError(e);
        }
    }

    static int run(String[] args, PrintStream out, PrintStream err) {
        Options opts;
        try {
            opts = parseArgs(args);
        } catch (UsageException e) {
            err.print(e.getMessage() + "\n");
            return EXIT_USAGE;
        }
        if (opts.help) {
            out.print(helpText());
            return EXIT_OK;
        }

        Path root = Paths.get(opts.dir);
        if (!Files.isDirectory(root) || !Files.isReadable(root)) {
            err.print("error: cannot read " + opts.dir + "\n");
            return EXIT_USAGE;
        }

        Scan scan = new Scan();
        try {
            scan.walk(root);
        } catch (IOException e) {
            err.print("error: cannot read " + opts.dir + "\n");
            return EXIT_USAGE;
        }

        StringBuilder sb = new StringBuilder();
        sb.append("files: ").append(scan.fileCount).append('\n');
        sb.append("dirs: ").append(scan.dirCount).append('\n');
        sb.append("by extension:\n");
        for (ExtStats s : extensionRows(scan.byExt, opts.extLimit)) {
            String label = s.ext == null ? "other" : (s.ext.isEmpty() ? "(none)" : s.ext);
            sb.append("  ").append(label)
                    .append("  files=").append(s.files)
                    .append(" loc=").append(s.loc).append('\n');
        }
        sb.append("largest files:\n");
        List<FileEntry> files = new ArrayList<>(scan.files);
        files.sort(Comparator.comparingLong(FileEntry::size).reversed()
                .thenComparing(FileEntry::path, Main::compareCodePoints));
        for (int i = 0; i < files.size() && i < opts.top; i++) {
            FileEntry f = files.get(i);
            sb.append("  ").append(f.size()).append(' ').append(f.path()).append('\n');
        }
        out.print(sb);
        return EXIT_OK;
    }

    static Options parseArgs(String[] args) throws UsageException {
        Options opts = new Options();
        boolean dirSeen = false;
        boolean endOfFlags = false;
        for (int i = 0; i < args.length; i++) {
            String arg = args[i];
            if (!endOfFlags && arg.startsWith("-") && !arg.equals("-")) {
                if (arg.equals("--")) {
                    endOfFlags = true;
                    continue;
                }
                String name = arg;
                String value = null;
                int eq = arg.indexOf('=');
                if (arg.startsWith("--") && eq > 0) {
                    name = arg.substring(0, eq);
                    value = arg.substring(eq + 1);
                }
                switch (name) {
                    case "--help", "-h" -> {
                        if (value != null) {
                            throw new UsageException("unknown flag: " + arg);
                        }
                        opts.help = true;
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
                            opts.top = n;
                        } else {
                            opts.extLimit = n;
                        }
                    }
                    default -> throw new UsageException("unknown flag: " + name);
                }
            } else {
                if (dirSeen) {
                    throw new UsageException("unexpected argument: " + arg);
                }
                opts.dir = arg;
                dirSeen = true;
            }
        }
        return opts;
    }

    static int parseCount(String flag, String value) throws UsageException {
        if (value.isEmpty() || !value.chars().allMatch(c -> c >= '0' && c <= '9')) {
            throw new UsageException("invalid value for " + flag + ": " + value);
        }
        try {
            return Integer.parseInt(value);
        } catch (NumberFormatException e) {
            throw new UsageException("invalid value for " + flag + ": " + value);
        }
    }

    static String helpText() {
        return "Usage: treesum [DIR] [--top N] [--ext-limit M]\n"
                + "\n"
                + "Print a compact overview of a directory tree: file and directory counts,\n"
                + "files and lines of code per extension, and the largest files.\n"
                + "\n"
                + "Arguments:\n"
                + "  DIR              directory to scan (default: .)\n"
                + "\n"
                + "Flags:\n"
                + "  --top N          number of largest files to list (default: 10)\n"
                + "  --ext-limit M    max extensions shown; the rest are folded into an\n"
                + "                   'other' row (default: 20)\n"
                + "  --help           show this help and exit\n"
                + "\n"
                + "Ignored directories (fixed, not configurable, skipped at any depth):\n"
                + "  " + String.join(", ", IGNORED_DIRS) + "\n"
                + "Symlinks are not followed and not counted.\n";
    }

    /** Sorted rows; a row with ext == null is the synthetic "other" row. */
    static List<ExtStats> extensionRows(Map<String, ExtStats> byExt, int limit) {
        List<ExtStats> rows = new ArrayList<>(byExt.values());
        rows.sort(Comparator.comparingLong((ExtStats s) -> s.files).reversed()
                .thenComparing(s -> s.ext, Main::compareCodePoints));
        if (rows.size() <= limit) {
            return rows;
        }
        ExtStats other = new ExtStats(null);
        for (ExtStats s : rows.subList(limit, rows.size())) {
            other.files += s.files;
            other.loc += s.loc;
        }
        List<ExtStats> result = new ArrayList<>(rows.subList(0, limit));
        result.add(other);
        return result;
    }

    /** Compare by Unicode code point (same order as UTF-8 bytes). */
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

    static String extensionOf(String name) {
        int dot = name.lastIndexOf('.');
        if (dot <= 0) {
            return "";
        }
        return name.substring(dot + 1).toLowerCase(java.util.Locale.ROOT);
    }

    static final class Scan {
        long fileCount;
        long dirCount;
        final Map<String, ExtStats> byExt = new HashMap<>();
        final List<FileEntry> files = new ArrayList<>();
        private final byte[] buf = new byte[1 << 16];

        private record Pending(Path path, String rel) {}

        void walk(Path root) throws IOException {
            Deque<Pending> stack = new ArrayDeque<>();
            boolean isRoot = true;
            stack.push(new Pending(root, ""));
            while (!stack.isEmpty()) {
                Pending dir = stack.pop();
                List<Path> children = new ArrayList<>();
                try (DirectoryStream<Path> ds = Files.newDirectoryStream(dir.path())) {
                    for (Path child : ds) {
                        children.add(child);
                    }
                } catch (IOException | SecurityException e) {
                    if (isRoot) {
                        throw new IOException(e);
                    }
                    continue; // unreadable subdirectory: still counted, contents skipped
                }
                isRoot = false;
                for (Path child : children) {
                    String name = child.getFileName().toString();
                    String rel = dir.rel().isEmpty() ? name : dir.rel() + "/" + name;
                    BasicFileAttributes attrs;
                    try {
                        attrs = Files.readAttributes(child, BasicFileAttributes.class,
                                LinkOption.NOFOLLOW_LINKS);
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
                        stack.push(new Pending(child, rel));
                    } else if (attrs.isRegularFile()) {
                        long loc;
                        try {
                            loc = countLoc(child);
                        } catch (IOException | SecurityException e) {
                            continue; // unreadable file: skipped silently
                        }
                        fileCount++;
                        ExtStats s = byExt.computeIfAbsent(extensionOf(name), ExtStats::new);
                        s.files++;
                        s.loc += loc;
                        files.add(new FileEntry(rel, attrs.size()));
                    }
                }
            }
        }

        long countLoc(Path file) throws IOException {
            long newlines = 0;
            long total = 0;
            byte last = 0;
            try (InputStream in = Files.newInputStream(file)) {
                int n;
                while ((n = in.read(buf)) > 0) {
                    for (int k = 0; k < n; k++) {
                        if (buf[k] == '\n') {
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
}
