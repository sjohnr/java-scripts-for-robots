import java.io.IOException;
import java.nio.file.FileVisitResult;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.SimpleFileVisitor;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;

public class Tool {
    static final Set<String> IGNORE = Set.of(".git", "node_modules", "build", ".gradle", "target", "dist", ".venv", "__pycache__", ".idea", ".vscode");

    record FileInfo(String relPath, long size, long loc) {}

    public static void main(String[] args) throws IOException {
        String dir = ".";
        int top = 10, extLimit = 20;
        for (int i = 0; i < args.length; i++) {
            String a = args[i];
            if (a.equals("--help")) { printHelp(); return; }
            else if (a.equals("--top")) top = Integer.parseInt(args[++i]);
            else if (a.equals("--ext-limit")) extLimit = Integer.parseInt(args[++i]);
            else if (a.startsWith("--")) { System.err.println("unknown flag: " + a); System.exit(2); return; }
            else dir = a;
        }
        Path root = Path.of(dir);
        if (!Files.isDirectory(root)) {
            System.err.println("error: cannot read " + dir);
            System.exit(2);
            return;
        }

        List<FileInfo> files = new ArrayList<>();
        long[] dirCount = {0};
        Files.walkFileTree(root, Set.of(), Integer.MAX_VALUE, new SimpleFileVisitor<>() {
            @Override
            public FileVisitResult preVisitDirectory(Path d, BasicFileAttributes attrs) {
                if (!d.equals(root) && IGNORE.contains(d.getFileName().toString())) return FileVisitResult.SKIP_SUBTREE;
                if (!d.equals(root)) dirCount[0]++;
                return FileVisitResult.CONTINUE;
            }
            @Override
            public FileVisitResult visitFile(Path f, BasicFileAttributes attrs) throws IOException {
                if (attrs.isSymbolicLink() || Files.isSymbolicLink(f)) return FileVisitResult.CONTINUE;
                if (!attrs.isRegularFile()) return FileVisitResult.CONTINUE;
                long size = attrs.size();
                long loc = countLoc(f);
                String rel = root.relativize(f).toString().replace('\\', '/');
                files.add(new FileInfo(rel, size, loc));
                return FileVisitResult.CONTINUE;
            }
            @Override
            public FileVisitResult visitFileFailed(Path f, IOException exc) { return FileVisitResult.CONTINUE; }
        });

        Map<String, long[]> extStats = new TreeMap<>();
        for (FileInfo fi : files) {
            String name = Path.of(fi.relPath()).getFileName().toString();
            int dot = name.lastIndexOf('.');
            String ext = (dot <= 0) ? "" : name.substring(dot + 1).toLowerCase();
            extStats.computeIfAbsent(ext, k -> new long[2]);
            extStats.get(ext)[0]++;
            extStats.get(ext)[1] += fi.loc();
        }

        List<String> extKeys = new ArrayList<>(extStats.keySet());
        extKeys.sort((a, b) -> {
            long ca = extStats.get(a)[0], cb = extStats.get(b)[0];
            if (ca != cb) return Long.compare(cb, ca);
            return a.compareTo(b);
        });

        StringBuilder out = new StringBuilder();
        out.append("files: ").append(files.size()).append('\n');
        out.append("dirs: ").append(dirCount[0]).append('\n');
        out.append("by extension:\n");

        List<String> shown = extKeys.size() > extLimit ? extKeys.subList(0, extLimit) : extKeys;
        long otherFiles = 0, otherLoc = 0;
        for (int i = extLimit; i < extKeys.size(); i++) {
            otherFiles += extStats.get(extKeys.get(i))[0];
            otherLoc += extStats.get(extKeys.get(i))[1];
        }
        for (String ext : shown) {
            String label = ext.isEmpty() ? "(none)" : ext;
            out.append("  ").append(label).append("  files=").append(extStats.get(ext)[0]).append(" loc=").append(extStats.get(ext)[1]).append('\n');
        }
        if (extKeys.size() > extLimit) {
            out.append("  other  files=").append(otherFiles).append(" loc=").append(otherLoc).append('\n');
        }

        out.append("largest files:\n");
        List<FileInfo> byLargest = new ArrayList<>(files);
        byLargest.sort((a, b) -> {
            if (a.size() != b.size()) return Long.compare(b.size(), a.size());
            return a.relPath().compareTo(b.relPath());
        });
        for (int i = 0; i < Math.min(top, byLargest.size()); i++) {
            FileInfo fi = byLargest.get(i);
            out.append("  ").append(fi.size()).append(' ').append(fi.relPath()).append('\n');
        }
        System.out.print(out);
    }

    static long countLoc(Path f) throws IOException {
        byte[] bytes = Files.readAllBytes(f);
        long nl = 0;
        for (byte b : bytes) if (b == '\n') nl++;
        if (bytes.length > 0 && bytes[bytes.length - 1] != '\n') nl++;
        return nl;
    }

    static void printHelp() {
        System.out.println("usage: treesum [DIR] [--top N] [--ext-limit M]");
        System.out.println("ignores: .git node_modules build .gradle target dist .venv __pycache__ .idea .vscode");
    }
}
