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
            "Arguments:",
            "  DIR            directory to scan (default: .)",
            "  --top N        number of largest files to list (default: 10)",
            "  --ext-limit M  max distinct extensions in the by-extension table (default: 20);",
            "                 the rest are folded into an 'other' row",
            "  --help         show this message and exit",
            "",
            "Ignored directory names (fixed): " + String.join(", ", new TreeSet<>(IGNORED).stream().toList()),
            "");

    record Entry(long size, String path) {}

    static class Stat { long files, loc; }

    static int files = 0, dirs = 0;
    static final Map<String, Stat> byExt = new HashMap<>();
    static final List<Entry> all = new ArrayList<>();

    static void usage(String msg) {
        System.err.print(msg + "\n");
        System.err.flush();
        System.exit(2);
    }

    static int parseNum(String flag, String v) {
        try {
            int n = Integer.parseInt(v);
            if (n < 0) throw new NumberFormatException();
            return n;
        } catch (NumberFormatException e) {
            usage("invalid value for " + flag + ": " + v);
            return 0;
        }
    }

    public static void main(String[] args) throws IOException {
        String dir = null;
        int top = 10, extLimit = 20;
        boolean noMoreFlags = false;
        for (int i = 0; i < args.length; i++) {
            String a = args[i];
            if (!noMoreFlags && a.equals("--")) { noMoreFlags = true; continue; }
            if (!noMoreFlags && a.startsWith("--")) {
                String name = a, val = null;
                int eq = a.indexOf('=');
                if (eq > 0) { name = a.substring(0, eq); val = a.substring(eq + 1); }
                switch (name) {
                    case "--help" -> {
                        PrintStream o = new PrintStream(new FileOutputStream(FileDescriptor.out), false, StandardCharsets.UTF_8);
                        o.print(HELP);
                        o.flush();
                        System.exit(0);
                    }
                    case "--top", "--ext-limit" -> {
                        if (val == null) {
                            if (i + 1 >= args.length) usage("missing value for " + name);
                            val = args[++i];
                        }
                        int n = parseNum(name, val);
                        if (name.equals("--top")) top = n; else extLimit = n;
                    }
                    default -> usage("unknown flag: " + a);
                }
            } else if (!noMoreFlags && a.startsWith("-") && a.length() > 1) {
                usage("unknown flag: " + a);
            } else {
                if (dir != null) usage("unexpected argument: " + a);
                dir = a;
            }
        }
        if (dir == null) dir = ".";
        Path root = Paths.get(dir);
        if (!Files.isDirectory(root)) {
            System.err.print("error: cannot read " + dir + "\n");
            System.exit(2);
        }
        walk(root, "");

        StringBuilder sb = new StringBuilder();
        sb.append("files: ").append(files).append('\n');
        sb.append("dirs: ").append(dirs).append('\n');
        sb.append("by extension:\n");

        List<Map.Entry<String, Stat>> exts = new ArrayList<>(byExt.entrySet());
        exts.sort((x, y) -> {
            int c = Long.compare(y.getValue().files, x.getValue().files);
            return c != 0 ? c : x.getKey().compareTo(y.getKey());
        });
        long oFiles = 0, oLoc = 0;
        for (int i = 0; i < exts.size(); i++) {
            Map.Entry<String, Stat> e = exts.get(i);
            if (i < extLimit) {
                String label = e.getKey().isEmpty() ? "(none)" : e.getKey();
                sb.append("  ").append(label).append("  files=").append(e.getValue().files)
                  .append(" loc=").append(e.getValue().loc).append('\n');
            } else {
                oFiles += e.getValue().files;
                oLoc += e.getValue().loc;
            }
        }
        if (exts.size() > extLimit)
            sb.append("  other  files=").append(oFiles).append(" loc=").append(oLoc).append('\n');

        sb.append("largest files:\n");
        all.sort((x, y) -> {
            int c = Long.compare(y.size, x.size);
            return c != 0 ? c : x.path.compareTo(y.path);
        });
        for (int i = 0; i < Math.min(top, all.size()); i++)
            sb.append("  ").append(all.get(i).size).append(' ').append(all.get(i).path).append('\n');

        PrintStream o = new PrintStream(new FileOutputStream(FileDescriptor.out), false, StandardCharsets.UTF_8);
        o.print(sb);
        o.flush();
    }

    static void walk(Path dir, String rel) throws IOException {
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
                Stat s = byExt.computeIfAbsent(ext, k -> new Stat());
                s.files++;
                s.loc += loc;
                all.add(new Entry(at.size(), r));
            }
        }
    }

    static long countLoc(Path p) throws IOException {
        long n = 0;
        int last = -1;
        try (InputStream in = new FileInputStream(p.toFile())) {
            byte[] buf = new byte[65536];
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
