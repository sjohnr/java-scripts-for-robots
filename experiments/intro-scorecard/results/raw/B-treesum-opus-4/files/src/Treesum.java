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

    static final String IGNORE_LIST_TEXT =
            ".git, node_modules, build, .gradle, target, dist, .venv, __pycache__, .idea, .vscode";

    /** Thrown for anything that must exit with code 2. */
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

    record ExtRow(String key, long files, long loc, boolean other) {}

    long fileCount;
    long dirCount;
    final Map<String, ExtStats> byExt = new HashMap<>();
    final List<FileEntry> files = new ArrayList<>();

    public static void main(String[] args) {
        PrintStream out = new PrintStream(System.out, false, StandardCharsets.UTF_8);
        PrintStream err = new PrintStream(System.err, false, StandardCharsets.UTF_8);
        int code = run(args, out, err);
        out.flush();
        err.flush();
        System.exit(code);
    }

    static int run(String[] args, PrintStream out, PrintStream err) {
        String dir = null;
        int top = 10;
        int extLimit = 20;
        try {
            boolean endOfFlags = false;
            for (int i = 0; i < args.length; i++) {
                String a = args[i];
                if (!endOfFlags && a.equals("--")) {
                    endOfFlags = true;
                } else if (!endOfFlags && (a.equals("--help") || a.equals("-h"))) {
                    out.print(usage());
                    return 0;
                } else if (!endOfFlags && (a.equals("--top") || a.startsWith("--top="))) {
                    String v = flagValue(args, i, "--top");
                    if (!a.contains("=")) i++;
                    top = parseCount("--top", v);
                } else if (!endOfFlags && (a.equals("--ext-limit") || a.startsWith("--ext-limit="))) {
                    String v = flagValue(args, i, "--ext-limit");
                    if (!a.contains("=")) i++;
                    extLimit = parseCount("--ext-limit", v);
                } else if (!endOfFlags && a.startsWith("-") && a.length() > 1) {
                    throw new UsageException("unknown flag: " + a);
                } else if (dir == null) {
                    dir = a;
                } else {
                    throw new UsageException("unexpected argument: " + a);
                }
            }
            if (dir == null) dir = ".";

            Path root = Paths.get(dir);
            if (!Files.isDirectory(root) || !Files.isReadable(root)) {
                throw new UsageException("error: cannot read " + dir);
            }
            Treesum ts = new Treesum();
            try {
                ts.walkRoot(root);
            } catch (IOException e) {
                throw new UsageException("error: cannot read " + dir);
            }
            out.print(ts.render(top, extLimit));
            return 0;
        } catch (UsageException e) {
            err.print(e.getMessage() + "\n");
            return 2;
        }
    }

    static String flagValue(String[] args, int i, String flag) throws UsageException {
        String a = args[i];
        int eq = a.indexOf('=');
        if (eq >= 0) return a.substring(eq + 1);
        if (i + 1 >= args.length) throw new UsageException("missing value for " + flag);
        return args[i + 1];
    }

    static int parseCount(String flag, String v) throws UsageException {
        try {
            int n = Integer.parseInt(v);
            if (n < 0) throw new NumberFormatException();
            return n;
        } catch (NumberFormatException e) {
            throw new UsageException("invalid value for " + flag + ": " + v);
        }
    }

    static String usage() {
        return "Usage: treesum [DIR] [--top N] [--ext-limit M]\n"
                + "\n"
                + "Print a compact overview of a directory tree.\n"
                + "\n"
                + "Arguments:\n"
                + "  DIR              directory to summarize (default: .)\n"
                + "\n"
                + "Flags:\n"
                + "  --top N          number of largest files to list (default: 10)\n"
                + "  --ext-limit M    max distinct extensions shown; the rest are folded\n"
                + "                   into an 'other' row (default: 20)\n"
                + "  -h, --help       show this help and exit\n"
                + "\n"
                + "Ignored directories (fixed, not configurable, not descended into):\n"
                + "  " + IGNORE_LIST_TEXT + "\n"
                + "Symlinks are not followed and not counted.\n";
    }

    void walkRoot(Path root) throws IOException {
        try (DirectoryStream<Path> ds = Files.newDirectoryStream(root)) {
            visitEntries(ds, "");
        }
    }

    void walk(Path dir, String rel) {
        try (DirectoryStream<Path> ds = Files.newDirectoryStream(dir)) {
            visitEntries(ds, rel + "/");
        } catch (IOException | SecurityException e) {
            // unreadable subdirectory: skip silently
        }
    }

    void visitEntries(DirectoryStream<Path> ds, String prefix) {
        for (Path p : ds) {
            String name = p.getFileName().toString();
            BasicFileAttributes attrs;
            try {
                attrs = Files.readAttributes(p, BasicFileAttributes.class, LinkOption.NOFOLLOW_LINKS);
            } catch (IOException | SecurityException e) {
                continue;
            }
            if (attrs.isSymbolicLink()) continue;
            String rel = prefix + name;
            if (attrs.isDirectory()) {
                if (IGNORED_DIRS.contains(name)) continue;
                dirCount++;
                walk(p, rel);
            } else if (attrs.isRegularFile()) {
                long loc;
                try {
                    loc = countLoc(p);
                } catch (IOException | SecurityException e) {
                    continue;
                }
                fileCount++;
                ExtStats s = byExt.computeIfAbsent(extensionOf(name), k -> new ExtStats());
                s.files++;
                s.loc += loc;
                files.add(new FileEntry(rel, attrs.size()));
            }
        }
    }

    static String extensionOf(String name) {
        int dot = name.lastIndexOf('.');
        if (dot <= 0) return "";
        return name.substring(dot + 1).toLowerCase(Locale.ROOT);
    }

    static long countLoc(Path p) throws IOException {
        long newlines = 0;
        long total = 0;
        byte last = 0;
        byte[] buf = new byte[64 * 1024];
        try (InputStream in = Files.newInputStream(p)) {
            int n;
            while ((n = in.read(buf)) > 0) {
                for (int i = 0; i < n; i++) {
                    if (buf[i] == '\n') newlines++;
                }
                last = buf[n - 1];
                total += n;
            }
        }
        if (total > 0 && last != '\n') newlines++;
        return newlines;
    }

    /** Compares strings by Unicode code point (equivalent to UTF-8 byte order). */
    static int compareCodePoints(String a, String b) {
        int i = 0;
        int j = 0;
        while (i < a.length() && j < b.length()) {
            int ca = a.codePointAt(i);
            int cb = b.codePointAt(j);
            if (ca != cb) return Integer.compare(ca, cb);
            i += Character.charCount(ca);
            j += Character.charCount(cb);
        }
        return Boolean.compare(i < a.length(), j < b.length());
    }

    String render(int top, int extLimit) {
        StringBuilder sb = new StringBuilder();
        sb.append("files: ").append(fileCount).append('\n');
        sb.append("dirs: ").append(dirCount).append('\n');
        sb.append("by extension:\n");

        List<ExtRow> rows = new ArrayList<>();
        byExt.forEach((k, v) -> rows.add(new ExtRow(k, v.files, v.loc, false)));
        rows.sort(Comparator.comparingLong(ExtRow::files).reversed()
                .thenComparing(ExtRow::key, Treesum::compareCodePoints));
        if (rows.size() > extLimit) {
            long otherFiles = 0;
            long otherLoc = 0;
            for (ExtRow r : rows.subList(extLimit, rows.size())) {
                otherFiles += r.files();
                otherLoc += r.loc();
            }
            rows.subList(extLimit, rows.size()).clear();
            rows.add(new ExtRow("other", otherFiles, otherLoc, true));
        }
        for (ExtRow r : rows) {
            String label = r.other() ? "other" : (r.key().isEmpty() ? "(none)" : r.key());
            sb.append("  ").append(label)
                    .append("  files=").append(r.files())
                    .append(" loc=").append(r.loc()).append('\n');
        }

        sb.append("largest files:\n");
        files.sort(Comparator.comparingLong(FileEntry::size).reversed()
                .thenComparing(FileEntry::path, Treesum::compareCodePoints));
        for (FileEntry f : files.subList(0, Math.min(top, files.size()))) {
            sb.append("  ").append(f.size()).append(' ').append(f.path()).append('\n');
        }
        return sb.toString();
    }
}
