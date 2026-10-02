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
            "  DIR            directory to summarize (default: .)",
            "  --top N        number of largest files to list (default: 10)",
            "  --ext-limit M  max distinct extensions in the by-extension table (default: 20);",
            "                 the rest are folded into an 'other' row",
            "  --help         print this message and exit",
            "",
            "Ignored directory names (fixed): .git, node_modules, build, .gradle, target, dist,",
            ".venv, __pycache__, .idea, .vscode",
            "") + "";

    record FileInfo(String path, long size) {}

    static long files = 0, dirs = 0;
    static final Map<String, long[]> byExt = new HashMap<>();
    static final List<FileInfo> all = new ArrayList<>();

    static int usage(String msg) {
        System.err.print(msg + "\n");
        System.err.flush();
        return 2;
    }

    public static void main(String[] args) {
        System.exit(run(args));
    }

    static int run(String[] args) {
        PrintStream out = new PrintStream(new FileOutputStream(FileDescriptor.out), false, StandardCharsets.UTF_8);
        System.setErr(new PrintStream(new FileOutputStream(FileDescriptor.err), false, StandardCharsets.UTF_8));
        String dir = null;
        long top = 10, extLimit = 20;
        for (String a : args) {
            if (a.equals("--help")) {
                out.print(HELP);
                out.flush();
                return 0;
            }
        }
        for (int i = 0; i < args.length; i++) {
            String a = args[i];
            if (a.equals("--top") || a.equals("--ext-limit") || a.startsWith("--top=") || a.startsWith("--ext-limit=")) {
                String name = a.startsWith("--top") ? "--top" : "--ext-limit";
                String val;
                int eq = a.indexOf('=');
                if (eq >= 0) val = a.substring(eq + 1);
                else if (i + 1 < args.length) val = args[++i];
                else return usage("error: missing value for " + name);
                long n;
                try {
                    n = Long.parseLong(val);
                } catch (NumberFormatException e) {
                    return usage("error: invalid value for " + name + ": " + val);
                }
                if (n < 0) return usage("error: invalid value for " + name + ": " + val);
                if (name.equals("--top")) top = n; else extLimit = n;
            } else if (a.startsWith("-") && a.length() > 1) {
                return usage("unknown flag: " + a);
            } else {
                if (dir != null) return usage("error: unexpected argument: " + a);
                dir = a;
            }
        }
        if (dir == null) dir = ".";
        Path root = Paths.get(dir);
        if (!Files.isDirectory(root)) {
            return usage("error: cannot read " + dir);
        }
        walk(root, "");

        List<Map.Entry<String, long[]>> exts = new ArrayList<>(byExt.entrySet());
        exts.sort((x, y) -> {
            int c = Long.compare(y.getValue()[0], x.getValue()[0]);
            return c != 0 ? c : x.getKey().compareTo(y.getKey());
        });
        StringBuilder sb = new StringBuilder();
        sb.append("files: ").append(files).append('\n');
        sb.append("dirs: ").append(dirs).append('\n');
        sb.append("by extension:\n");
        long oF = 0, oL = 0;
        boolean hasOther = false;
        for (int i = 0; i < exts.size(); i++) {
            var e = exts.get(i);
            if (i < extLimit) {
                sb.append("  ").append(e.getKey().isEmpty() ? "(none)" : e.getKey())
                  .append("  files=").append(e.getValue()[0]).append(" loc=").append(e.getValue()[1]).append('\n');
            } else {
                hasOther = true;
                oF += e.getValue()[0];
                oL += e.getValue()[1];
            }
        }
        if (hasOther) sb.append("  other  files=").append(oF).append(" loc=").append(oL).append('\n');
        sb.append("largest files:\n");
        all.sort((x, y) -> {
            int c = Long.compare(y.size, x.size);
            return c != 0 ? c : x.path.compareTo(y.path);
        });
        for (int i = 0; i < all.size() && i < top; i++) {
            sb.append("  ").append(all.get(i).size).append(' ').append(all.get(i).path).append('\n');
        }
        out.print(sb);
        out.flush();
        return 0;
    }

    static void walk(Path dir, String rel) {
        List<Path> children = new ArrayList<>();
        try (DirectoryStream<Path> ds = Files.newDirectoryStream(dir)) {
            for (Path p : ds) children.add(p);
        } catch (IOException | SecurityException e) {
            return;
        }
        for (Path p : children) {
            String name = p.getFileName().toString();
            BasicFileAttributes at;
            try {
                at = Files.readAttributes(p, BasicFileAttributes.class, LinkOption.NOFOLLOW_LINKS);
            } catch (IOException e) {
                continue;
            }
            if (at.isSymbolicLink()) continue;
            String r = rel.isEmpty() ? name : rel + "/" + name;
            if (at.isDirectory()) {
                if (IGNORED.contains(name)) continue;
                dirs++;
                walk(p, r);
            } else if (at.isRegularFile()) {
                long loc;
                try {
                    loc = countLoc(p);
                } catch (IOException e) {
                    continue;
                }
                files++;
                int dot = name.lastIndexOf('.');
                String ext = dot <= 0 ? "" : name.substring(dot + 1).toLowerCase(Locale.ROOT);
                long[] s = byExt.computeIfAbsent(ext, k -> new long[2]);
                s[0]++;
                s[1] += loc;
                all.add(new FileInfo(r, at.size()));
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
