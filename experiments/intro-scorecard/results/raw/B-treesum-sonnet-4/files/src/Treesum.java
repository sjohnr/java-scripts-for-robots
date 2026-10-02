import java.io.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.*;

public class Treesum {
    static final Set<String> IGNORED = Set.of(".git", "node_modules", "build", ".gradle", "target",
            "dist", ".venv", "__pycache__", ".idea", ".vscode");

    static final String HELP = String.join("\n",
            "Usage: treesum [DIR] [--top N] [--ext-limit M]",
            "",
            "Compact repo overview: file/dir counts, files and LOC by extension, largest files.",
            "",
            "  DIR            directory to summarize (default: .)",
            "  --top N        number of largest files to list (default: 10)",
            "  --ext-limit M  max distinct extensions shown; the rest fold into 'other' (default: 20)",
            "  --help         show this message",
            "",
            "Ignored directory names (fixed): " + String.join(", ", new TreeSet<>(IGNORED)),
            "Symlinks are not followed or counted.",
            "");

    static long files = 0, dirs = 0;
    static final Map<String, long[]> byExt = new HashMap<>();
    static final List<Object[]> all = new ArrayList<>();

    static PrintStream out;

    static int usage(String msg) {
        System.err.print(msg + "\n");
        System.err.flush();
        return 2;
    }

    public static void main(String[] args) throws Exception {
        System.exit(run(args));
    }

    static int run(String[] args) throws Exception {
        out = new PrintStream(new FileOutputStream(FileDescriptor.out), false, StandardCharsets.UTF_8);
        String dir = null;
        long top = 10, extLimit = 20;
        for (int i = 0; i < args.length; i++) {
            String a = args[i];
            if (a.equals("--help")) {
                out.print(HELP);
                out.flush();
                return 0;
            }
        }
        for (int i = 0; i < args.length; i++) {
            String a = args[i];
            if (a.startsWith("--")) {
                String name = a, val = null;
                int eq = a.indexOf('=');
                if (eq > 0) { name = a.substring(0, eq); val = a.substring(eq + 1); }
                if (!name.equals("--top") && !name.equals("--ext-limit")) return usage("unknown flag: " + a);
                if (val == null) {
                    if (i + 1 >= args.length) return usage("missing value for " + name);
                    val = args[++i];
                }
                long n;
                try { n = Long.parseLong(val); } catch (NumberFormatException e) {
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
        Path root = Paths.get(dir);
        if (!Files.isDirectory(root)) {
            System.err.print("error: cannot read " + dir + "\n");
            return 2;
        }
        try {
            walk(root, "");
        } catch (IOException e) {
            System.err.print("error: cannot read " + dir + "\n");
            return 2;
        }

        StringBuilder sb = new StringBuilder();
        sb.append("files: ").append(files).append('\n');
        sb.append("dirs: ").append(dirs).append('\n');
        sb.append("by extension:\n");
        List<Map.Entry<String, long[]>> exts = new ArrayList<>(byExt.entrySet());
        exts.sort((x, y) -> {
            int c = Long.compare(y.getValue()[0], x.getValue()[0]);
            return c != 0 ? c : x.getKey().compareTo(y.getKey());
        });
        long oFiles = 0, oLoc = 0;
        boolean hasOther = false;
        for (int i = 0; i < exts.size(); i++) {
            Map.Entry<String, long[]> e = exts.get(i);
            if (i < extLimit) {
                String label = e.getKey().isEmpty() ? "(none)" : e.getKey();
                sb.append("  ").append(label).append("  files=").append(e.getValue()[0])
                        .append(" loc=").append(e.getValue()[1]).append('\n');
            } else {
                hasOther = true;
                oFiles += e.getValue()[0];
                oLoc += e.getValue()[1];
            }
        }
        if (hasOther) sb.append("  other  files=").append(oFiles).append(" loc=").append(oLoc).append('\n');
        sb.append("largest files:\n");
        all.sort((x, y) -> {
            int c = Long.compare((Long) y[0], (Long) x[0]);
            return c != 0 ? c : ((String) x[1]).compareTo((String) y[1]);
        });
        for (int i = 0; i < all.size() && i < top; i++) {
            sb.append("  ").append(all.get(i)[0]).append(' ').append(all.get(i)[1]).append('\n');
        }
        out.print(sb);
        out.flush();
        return 0;
    }

    static void walk(Path dir, String rel) throws IOException {
        List<Path> children = new ArrayList<>();
        try (DirectoryStream<Path> ds = Files.newDirectoryStream(dir)) {
            for (Path p : ds) children.add(p);
        }
        for (Path p : children) {
            String name = p.getFileName().toString();
            BasicFileAttributes at;
            try {
                at = Files.readAttributes(p, BasicFileAttributes.class, LinkOption.NOFOLLOW_LINKS);
            } catch (IOException e) {
                continue;
            }
            String r = rel.isEmpty() ? name : rel + "/" + name;
            if (at.isSymbolicLink()) continue;
            if (at.isDirectory()) {
                if (IGNORED.contains(name)) continue;
                dirs++;
                try { walk(p, r); } catch (IOException e) { /* unreadable subdir: skip */ }
            } else if (at.isRegularFile()) {
                long loc;
                try { loc = countLoc(p); } catch (IOException e) { continue; }
                files++;
                int idx = name.lastIndexOf('.');
                String ext = idx <= 0 ? "" : name.substring(idx + 1).toLowerCase(Locale.ROOT);
                long[] s = byExt.computeIfAbsent(ext, k -> new long[2]);
                s[0]++;
                s[1] += loc;
                all.add(new Object[]{at.size(), r});
            }
        }
    }

    static long countLoc(Path p) throws IOException {
        long n = 0;
        int last = -1;
        byte[] buf = new byte[65536];
        try (InputStream in = Files.newInputStream(p)) {
            int k;
            while ((k = in.read(buf)) > 0) {
                for (int i = 0; i < k; i++) if (buf[i] == '\n') n++;
                last = buf[k - 1];
            }
        }
        if (last != -1 && last != '\n') n++;
        return n;
    }
}
