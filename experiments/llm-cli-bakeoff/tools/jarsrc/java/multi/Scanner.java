import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;

public class Scanner {
    public record Ga(String group, String artifact) {
        public String toString() { return group + ":" + artifact; }
    }

    public record Entry(String fqcn, Ga ga, String version, Path jarPath) {}

    public static List<Entry> scan(Path root) throws IOException {
        List<Entry> entries = new ArrayList<>();
        if (!Files.isDirectory(root)) return entries;
        Files.walk(root).filter(p -> p.getFileName().toString().endsWith("-sources.jar")).forEach(jar -> {
            Path rel = root.relativize(jar);
            int n = rel.getNameCount();
            if (n < 3) return;
            String version = rel.getName(n - 2).toString();
            String artifact = rel.getName(n - 3).toString();
            StringBuilder group = new StringBuilder();
            for (int i = 0; i < n - 3; i++) {
                if (i > 0) group.append('.');
                group.append(rel.getName(i));
            }
            Ga ga = new Ga(group.toString(), artifact);
            try (ZipInputStream zis = new ZipInputStream(Files.newInputStream(jar))) {
                ZipEntry ze;
                while ((ze = zis.getNextEntry()) != null) {
                    if (ze.getName().endsWith(".java")) {
                        String fqcn = ze.getName().substring(0, ze.getName().length() - 5).replace('/', '.');
                        entries.add(new Entry(fqcn, ga, version, jar));
                    }
                }
            } catch (IOException e) {
                throw new RuntimeException(e);
            }
        });
        return entries;
    }

    public static String readEntrySource(Path jar, String entryName) throws IOException {
        try (ZipInputStream zis = new ZipInputStream(Files.newInputStream(jar))) {
            ZipEntry ze;
            while ((ze = zis.getNextEntry()) != null) {
                if (ze.getName().equals(entryName)) {
                    return new String(zis.readAllBytes(), java.nio.charset.StandardCharsets.UTF_8);
                }
            }
        }
        throw new IOException("entry not found: " + entryName);
    }
}
