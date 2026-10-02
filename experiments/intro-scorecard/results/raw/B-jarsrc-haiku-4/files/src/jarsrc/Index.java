package jarsrc;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.TreeSet;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;

/**
 * In-memory view of every {@code <root>/<group>/<artifact>/<version>/<artifact>-<version>-sources.jar}
 * under a root directory, with the FQCNs each jar contains.
 */
final class Index {

    static final class Artifact {
        final String group;
        final String artifact;
        final String version;
        final Path jar;
        final List<String> classes;

        Artifact(String group, String artifact, String version, Path jar, List<String> classes) {
            this.group = group;
            this.artifact = artifact;
            this.version = version;
            this.jar = jar;
            this.classes = classes;
        }

        String ga() {
            return group + ":" + artifact;
        }

        String gav() {
            return ga() + ":" + version;
        }
    }

    private final List<Artifact> artifacts = new ArrayList<>();
    private final Map<String, List<Artifact>> byClass = new TreeMap<>();
    private final Map<String, List<Artifact>> byGa = new TreeMap<>();

    static Index scan(Path rootArg) throws IOException {
        Index index = new Index();
        Path root = rootArg.toAbsolutePath().normalize();
        if (!Files.isDirectory(root)) {
            return index;
        }
        List<Path> jars = new ArrayList<>();
        try (var walk = Files.walk(root)) {
            walk.filter(Files::isRegularFile).forEach(jars::add);
        }
        for (Path file : jars) {
            Artifact a = describe(root, file);
            if (a != null) {
                index.add(a);
            }
        }
        return index;
    }

    /** Returns the artifact for a jar at the expected Gradle-cache location, or null if it is not one. */
    private static Artifact describe(Path root, Path file) throws IOException {
        List<String> parts = new ArrayList<>();
        for (Path p : root.relativize(file)) {
            parts.add(p.toString());
        }
        int n = parts.size();
        if (n < 4) {
            return null;
        }
        String fileName = parts.get(n - 1);
        String version = parts.get(n - 2);
        String artifact = parts.get(n - 3);
        if (!fileName.equals(artifact + "-" + version + "-sources.jar")) {
            return null;
        }
        String group = String.join(".", parts.subList(0, n - 3));
        return new Artifact(group, artifact, version, file, readClasses(file));
    }

    private static List<String> readClasses(Path jar) throws IOException {
        List<String> classes = new ArrayList<>();
        try (ZipFile zip = new ZipFile(jar.toFile())) {
            var entries = zip.entries();
            while (entries.hasMoreElements()) {
                ZipEntry e = entries.nextElement();
                String name = e.getName();
                if (!e.isDirectory() && name.endsWith(".java")) {
                    classes.add(toFqcn(name));
                }
            }
        }
        return classes;
    }

    static String toFqcn(String entryName) {
        return entryName.substring(0, entryName.length() - ".java".length()).replace('/', '.');
    }

    static String toEntryName(String fqcn) {
        return fqcn.replace('.', '/') + ".java";
    }

    private void add(Artifact a) {
        artifacts.add(a);
        byGa.computeIfAbsent(a.ga(), k -> new ArrayList<>()).add(a);
        for (String c : a.classes) {
            byClass.computeIfAbsent(c, k -> new ArrayList<>()).add(a);
        }
    }

    List<Artifact> artifacts() {
        return artifacts;
    }

    /** All artifacts (any version) that contain the exact FQCN. */
    List<Artifact> containing(String fqcn) {
        return byClass.getOrDefault(fqcn, List.of());
    }

    /** All versions of a group:artifact, unsorted. */
    List<Artifact> versionsOf(String ga) {
        return byGa.getOrDefault(ga, List.of());
    }

    boolean hasGa(String ga) {
        return byGa.containsKey(ga);
    }

    /** Every distinct FQCN in the tree, sorted. */
    TreeSet<String> allClasses() {
        return new TreeSet<>(byClass.keySet());
    }

    /** Every distinct group:artifact string in the tree, sorted. */
    TreeSet<String> allGas() {
        return new TreeSet<>(byGa.keySet());
    }
}
