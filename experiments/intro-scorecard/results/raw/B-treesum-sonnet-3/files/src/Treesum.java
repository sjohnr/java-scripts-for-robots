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
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

public class Treesum {
    private static final Set<String> IGNORED = Set.of(
        ".git", "node_modules", "build", ".gradle", "target", "dist", ".venv",
        "__pycache__", ".idea", ".vscode");

    private static final String HELP = String.join("\n",
        "Usage: treesum [DIR] [--top N] [--ext-limit M]",
        "",
        "Compact repo overview: file/dir counts, per-extension stats, largest files.",
        "",
        "Arguments and flags:",
        "  DIR            directory to scan (default: .)",
        "  --top N        number of largest files to list (default: 10)",
        "  --ext-limit M  max distinct extensions in the by-extension table (default: 20);",
        "                 the rest are folded into an 'other' row",
        "  --help         show this message and exit",
        "",
        "Ignored directory names (fixed, anywhere in the tree):",
        "  .git, node_modules, build, .gradle, target, dist, .venv, __pycache__, .idea, .vscode",
        "",
        "Symlinks are not followed or counted.",
        "");

    private static final class Stat {
        long files;
        long loc;
    }

    private record FileInfo(long size, String path) {}

    private static long fileCount;
    private static long dirCount;
    private static final Map<String, Stat> byExt = new HashMap<>();
    private static final List<FileInfo> all = new ArrayList<>();

    private static int usage(String msg) {
        System.err.print(msg + "\n");
        return 2;
    }

    public static void main(String[] args) {
        System.exit(run(args));
    }

    private static int run(String[] args) {
        String dir = null;
        long top = 10, extLimit = 20;
        for (int i = 0; i < args.length; i++) {
            String a = args[i];
            if (a.equals("--help")) {
                out().print(HELP);
                out().flush();
                return 0;
            }
        }
        for (int i = 0; i < args.length; i++) {
            String a = args[i];
            if (a.equals("--top") || a.equals("--ext-limit") || a.startsWith("--top=") || a.startsWith("--ext-limit=")) {
                String name = a.startsWith("--top") ? "--top" : "--ext-limit";
                String val;
                int eq = a.indexOf('=');
                if (eq >= 0) {
                    val = a.substring(eq + 1);
                } else {
                    if (i + 1 >= args.length) return usage("missing value for " + name);
                    val = args[++i];
                }
                long n;
                try {
                    n = Long.parseLong(val);
                } catch (NumberFormatException e) {
                    return usage("invalid value for " + name + ": " + val);
                }
                if (n < 0) return usage("invalid value for " + name + ": " + val);
                if (name.equals("--top")) top = n; else extLimit = n;
            } else if (a.startsWith("-") && a.length() > 1) {
                return usage("unknown flag: " + a);
            } else {
                if (dir != null) return usage("unexpected argument: " + a);
                dir = a;
            }
        }
        if (dir == null) dir = ".";

        Path root;
        try {
            root = Paths.get(dir);
        } catch (RuntimeException e) {
            return usage("error: cannot read " + dir);
        }
        if (!Files.isDirectory(root)) return usage("error: cannot read " + dir);

        walk(root, "");

        StringBuilder sb = new StringBuilder();
        sb.append("files: ").append(fileCount).append('\n');
        sb.append("dirs: ").append(dirCount).append('\n');
        sb.append("by extension:\n");

        List<Map.Entry<String, Stat>> exts = new ArrayList<>(byExt.entrySet());
        exts.sort((x, y) -> {
            int c = Long.compare(y.getValue().files, x.getValue().files);
            return c != 0 ? c : x.getKey().compareTo(y.getKey());
        });
        long otherFiles = 0, otherLoc = 0;
        for (int i = 0; i < exts.size(); i++) {
            Stat s = exts.get(i).getValue();
            if (i < extLimit) {
                String label = exts.get(i).getKey();
                if (label.isEmpty()) label = "(none)";
                sb.append("  ").append(label).append("  files=").append(s.files)
                  .append(" loc=").append(s.loc).append('\n');
            } else {
                otherFiles += s.files;
                otherLoc += s.loc;
            }
        }
        if (exts.size() > extLimit) {
            sb.append("  other  files=").append(otherFiles).append(" loc=").append(otherLoc).append('\n');
        }

        sb.append("largest files:\n");
        all.sort((x, y) -> {
            int c = Long.compare(y.size, x.size);
            return c != 0 ? c : x.path.compareTo(y.path);
        });
        for (int i = 0; i < all.size() && i < top; i++) {
            sb.append("  ").append(all.get(i).size).append(' ').append(all.get(i).path).append('\n');
        }
        out().print(sb);
        out().flush();
        return 0;
    }

    private static PrintStream out_;

    private static PrintStream out() {
        if (out_ == null) out_ = new PrintStream(new java.io.FileOutputStream(java.io.FileDescriptor.out), false, StandardCharsets.UTF_8);
        return out_;
    }

    private static void walk(Path dir, String rel) {
        List<Path> children = new ArrayList<>();
        try (DirectoryStream<Path> ds = Files.newDirectoryStream(dir)) {
            for (Path p : ds) children.add(p);
        } catch (IOException | RuntimeException e) {
            return;
        }
        for (Path p : children) {
            String name = p.getFileName().toString();
            BasicFileAttributes attrs;
            try {
                attrs = Files.readAttributes(p, BasicFileAttributes.class, LinkOption.NOFOLLOW_LINKS);
            } catch (IOException | RuntimeException e) {
                continue;
            }
            if (attrs.isSymbolicLink()) continue;
            String childRel = rel.isEmpty() ? name : rel + "/" + name;
            if (attrs.isDirectory()) {
                if (IGNORED.contains(name)) continue;
                dirCount++;
                walk(p, childRel);
            } else if (attrs.isRegularFile()) {
                long loc = countLoc(p);
                if (loc < 0) continue;
                fileCount++;
                Stat s = byExt.computeIfAbsent(extOf(name), k -> new Stat());
                s.files++;
                s.loc += loc;
                all.add(new FileInfo(attrs.size(), childRel));
            }
        }
    }

    private static String extOf(String name) {
        int i = name.lastIndexOf('.');
        if (i <= 0) return "";
        return name.substring(i + 1).toLowerCase(java.util.Locale.ROOT);
    }

    private static long countLoc(Path p) {
        long n = 0;
        int last = -1;
        byte[] buf = new byte[65536];
        try (InputStream in = Files.newInputStream(p)) {
            int r;
            while ((r = in.read(buf)) > 0) {
                for (int i = 0; i < r; i++) if (buf[i] == '\n') n++;
                last = buf[r - 1];
            }
        } catch (IOException | RuntimeException e) {
            return -1;
        }
        if (last != -1 && last != '\n') n++;
        return n;
    }
}
