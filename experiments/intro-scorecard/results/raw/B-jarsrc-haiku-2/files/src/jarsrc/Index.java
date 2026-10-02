package jarsrc;

import java.io.IOException;
import java.nio.file.FileVisitResult;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.SimpleFileVisitor;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.StringJoiner;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;

import static java.util.stream.Collectors.toSet;

/** Every sources jar under a root, with the .java classes each one contains. */
final class Index {

    record Artifact(String group, String artifact, String version, Path jar) {
        String ga() {
            return group + ":" + artifact;
        }

        String gav() {
            return ga() + ":" + version;
        }
    }

    private final List<Artifact> artifacts = new ArrayList<>();
    private final Map<Artifact, List<String>> classes = new HashMap<>();

    /** A missing or unreadable root is an empty index. */
    Index(Path root) {
        Path base = root.toAbsolutePath().normalize();
        try {
            Files.walkFileTree(base, new SimpleFileVisitor<Path>() {
                @Override
                public FileVisitResult visitFile(Path file, BasicFileAttributes attrs) {
                    Artifact a = toArtifact(base, file);
                    if (a != null && attrs.isRegularFile()) {
                        artifacts.add(a);
                    }
                    return FileVisitResult.CONTINUE;
                }

                @Override
                public FileVisitResult visitFileFailed(Path file, IOException e) {
                    return FileVisitResult.CONTINUE;
                }
            });
        } catch (IOException e) {
            // walkFileTree only throws if the visitor does; ours never does.
        }
        for (Artifact a : artifacts) {
            classes.put(a, readClasses(a.jar()));
        }
    }

    List<Artifact> artifacts() {
        return artifacts;
    }

    List<String> classesIn(Artifact a) {
        return classes.get(a);
    }

    List<Artifact> holdersOf(String fqcn) {
        return artifacts.stream().filter(a -> classes.get(a).contains(fqcn)).toList();
    }

    Set<String> allClasses() {
        return classes.values().stream().flatMap(List::stream).collect(toSet());
    }

    /** Matches {@code <root>/<group dirs>/<artifact>/<version>/<artifact>-<version>-sources.jar}. */
    private static Artifact toArtifact(Path base, Path jar) {
        Path rel = base.relativize(jar);
        int n = rel.getNameCount();
        if (n < 4) {
            return null;
        }
        String file = rel.getFileName().toString();
        String version = rel.getName(n - 2).toString();
        String artifact = rel.getName(n - 3).toString();
        if (!file.equals(artifact + "-" + version + "-sources.jar")) {
            return null;
        }
        StringJoiner group = new StringJoiner(".");
        for (int i = 0; i < n - 3; i++) {
            group.add(rel.getName(i).toString());
        }
        return new Artifact(group.toString(), artifact, version, jar);
    }

    private static List<String> readClasses(Path jar) {
        List<String> out = new ArrayList<>();
        try (ZipFile zip = new ZipFile(jar.toFile())) {
            zip.stream()
                    .map(ZipEntry::getName)
                    .filter(name -> name.endsWith(".java"))
                    .forEach(name -> out.add(fqcn(name)));
        } catch (IOException e) {
            // An unreadable jar contributes no classes.
        }
        return out;
    }

    /** {@code com/example/Widget.java} -> {@code com.example.Widget}. */
    static String fqcn(String entry) {
        return entry.substring(0, entry.length() - ".java".length()).replace('/', '.');
    }
}
