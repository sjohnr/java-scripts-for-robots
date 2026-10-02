package jarsrc;

import java.io.BufferedOutputStream;
import java.io.FileDescriptor;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.Enumeration;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.TreeSet;
import java.util.regex.Pattern;
import java.util.stream.Stream;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;

/** jarsrc: query a Gradle-cache-like tree of *-sources.jar files. See SPEC.md. */
public final class Main {

    static final int EXIT_OK = 0;
    static final int EXIT_NOT_FOUND = 1;
    static final int EXIT_USAGE = 2;

    static final String USAGE = String.join("\n",
            "Usage:",
            "  jarsrc find <fqcn-or-glob> [--root DIR]",
            "  jarsrc cat <fqcn> [--lines A:B] [--root DIR]",
            "  jarsrc ls <group:artifact[:version]> [--root DIR]",
            "  jarsrc --help",
            "",
            "Subcommands:",
            "  find   List classes matching a FQCN or glob (* = any chars except '.', ** = any chars)",
            "  cat    Print the source of a class from the highest version containing it",
            "  ls     List versions of group:artifact, or classes in group:artifact:version",
            "",
            "Flags:",
            "  --root DIR    Root of the sources-jar tree (default: .)",
            "  --lines A:B   (cat only) Print only lines A through B, 1-indexed, inclusive",
            "  --help        Show this help and exit",
            "");

    /** Signals termination with a given exit code and stderr message. */
    static final class Exit extends Exception {
        final int code;

        Exit(int code, String message) {
            super(message, null, false, false);
            this.code = code;
        }
    }

    /** One sources jar: group:artifact:version plus its location. */
    record Artifact(String group, String artifact, String version, Path jar) {
        String ga() {
            return group + ":" + artifact;
        }

        String gav() {
            return group + ":" + artifact + ":" + version;
        }
    }

    public static void main(String[] args) {
        PrintStream out = new PrintStream(
                new BufferedOutputStream(new FileOutputStream(FileDescriptor.out)), false, StandardCharsets.UTF_8);
        PrintStream err = new PrintStream(
                new BufferedOutputStream(new FileOutputStream(FileDescriptor.err)), false, StandardCharsets.UTF_8);
        int code;
        try {
            code = run(args, out);
        } catch (Exit e) {
            code = e.code;
            if (e.getMessage() != null) {
                err.print(e.getMessage() + "\n");
            }
        }
        out.flush();
        err.flush();
        System.exit(code);
    }

    static int run(String[] args, PrintStream out) throws Exit {
        String root = ".";
        String lines = null;
        List<String> positional = new ArrayList<>();
        for (int i = 0; i < args.length; i++) {
            String a = args[i];
            if (a.equals("--help")) {
                out.print(USAGE);
                return EXIT_OK;
            } else if (a.equals("--root") || a.equals("--lines")) {
                if (i + 1 >= args.length) {
                    throw usage("flag " + a + " requires a value");
                }
                if (a.equals("--root")) {
                    root = args[++i];
                } else {
                    lines = args[++i];
                }
            } else if (a.startsWith("--root=")) {
                root = a.substring("--root=".length());
            } else if (a.startsWith("--lines=")) {
                lines = a.substring("--lines=".length());
            } else if (a.startsWith("-") && a.length() > 1) {
                throw usage("unknown flag: " + a);
            } else {
                positional.add(a);
            }
        }
        if (positional.isEmpty()) {
            throw usage("missing subcommand (see --help)");
        }
        String cmd = positional.get(0);
        if (!cmd.equals("find") && !cmd.equals("cat") && !cmd.equals("ls")) {
            throw usage("unknown subcommand: " + cmd);
        }
        if (positional.size() < 2) {
            throw usage(cmd + " requires an argument");
        }
        if (positional.size() > 2) {
            throw usage("unexpected argument: " + positional.get(2));
        }
        if (lines != null && !cmd.equals("cat")) {
            throw usage("--lines is only valid with cat");
        }
        String arg = positional.get(1);
        int[] range = lines == null ? null : parseLines(lines);
        Path rootPath = Paths.get(root);
        return switch (cmd) {
            case "find" -> find(rootPath, arg, out);
            case "cat" -> cat(rootPath, arg, range, out);
            default -> ls(rootPath, arg, out);
        };
    }

    static Exit usage(String message) {
        return new Exit(EXIT_USAGE, "error: " + message);
    }

    static int[] parseLines(String s) throws Exit {
        int colon = s.indexOf(':');
        if (colon < 0) {
            throw usage("invalid --lines value: " + s + " (expected A:B)");
        }
        try {
            String a = s.substring(0, colon);
            String b = s.substring(colon + 1);
            if (!a.matches("[0-9]+") || !b.matches("[0-9]+")) {
                throw new NumberFormatException();
            }
            return new int[] {clampInt(a), clampInt(b)};
        } catch (NumberFormatException e) {
            throw usage("invalid --lines value: " + s + " (expected A:B)");
        }
    }

    private static int clampInt(String digits) {
        String stripped = digits.replaceFirst("^0+(?=.)", "");
        return stripped.length() > 9 ? Integer.MAX_VALUE : Integer.parseInt(stripped);
    }

    // ---------------------------------------------------------------- find

    static int find(Path root, String query, PrintStream out) throws Exit {
        Pattern pattern = globToRegex(query);
        List<Artifact> artifacts = scan(root);
        List<String[]> matches = new ArrayList<>();
        TreeSet<String> simpleNames = new TreeSet<>(Main::compareBytes);
        for (Artifact art : artifacts) {
            for (String fqcn : classesIn(art)) {
                simpleNames.add(simpleName(fqcn));
                if (pattern.matcher(fqcn).matches()) {
                    matches.add(new String[] {fqcn, art.gav()});
                }
            }
        }
        if (matches.isEmpty()) {
            String prefix = simpleName(query);
            int star = prefix.indexOf('*');
            if (star >= 0) {
                prefix = prefix.substring(0, star);
            }
            throw notFound("no matches for " + query, prefix, simpleNames);
        }
        matches.sort((x, y) -> {
            int c = compareBytes(x[0], y[0]);
            return c != 0 ? c : compareBytes(x[1], y[1]);
        });
        String prev = null;
        for (String[] m : matches) {
            String line = m[0] + " " + m[1];
            if (!line.equals(prev)) {
                out.print(line + "\n");
            }
            prev = line;
        }
        return EXIT_OK;
    }

    static Pattern globToRegex(String glob) {
        StringBuilder re = new StringBuilder();
        StringBuilder literal = new StringBuilder();
        for (int i = 0; i < glob.length(); i++) {
            char c = glob.charAt(i);
            if (c == '*') {
                if (literal.length() > 0) {
                    re.append(Pattern.quote(literal.toString()));
                    literal.setLength(0);
                }
                if (i + 1 < glob.length() && glob.charAt(i + 1) == '*') {
                    re.append(".*");
                    i++;
                } else {
                    re.append("[^.]*");
                }
            } else {
                literal.append(c);
            }
        }
        if (literal.length() > 0) {
            re.append(Pattern.quote(literal.toString()));
        }
        return Pattern.compile(re.toString(), Pattern.DOTALL);
    }

    // ---------------------------------------------------------------- cat

    static int cat(Path root, String fqcn, int[] range, PrintStream out) throws Exit {
        List<Artifact> artifacts = scan(root);
        String entryName = fqcn.replace('.', '/') + ".java";
        Map<String, List<Artifact>> byGa = new TreeMap<>(Main::compareBytes);
        TreeSet<String> simpleNames = new TreeSet<>(Main::compareBytes);
        for (Artifact art : artifacts) {
            List<String> classes = classesIn(art);
            for (String c : classes) {
                simpleNames.add(simpleName(c));
            }
            if (classes.contains(fqcn)) {
                byGa.computeIfAbsent(art.ga(), k -> new ArrayList<>()).add(art);
            }
        }
        if (byGa.isEmpty()) {
            throw notFound("no source for " + fqcn, simpleName(fqcn), simpleNames);
        }
        if (byGa.size() > 1) {
            throw new Exit(EXIT_USAGE,
                    "error: " + fqcn + " is ambiguous across: " + String.join(", ", byGa.keySet()));
        }
        List<Artifact> candidates = byGa.values().iterator().next();
        Artifact best = candidates.stream()
                .max(Comparator.comparing(Artifact::version, Main::compareVersions))
                .orElseThrow();
        String source = readEntry(best, entryName);
        List<String> lines = splitLines(source);
        int from = 1;
        int to = lines.size();
        if (range != null) {
            from = Math.max(1, range[0]);
            to = Math.min(lines.size(), range[1]);
        }
        StringBuilder sb = new StringBuilder();
        for (int i = from; i <= to; i++) {
            sb.append(lines.get(i - 1).stripTrailing()).append('\n');
        }
        out.print(sb);
        return EXIT_OK;
    }

    static List<String> splitLines(String s) {
        if (!s.isEmpty() && s.charAt(0) == '﻿') {
            s = s.substring(1);
        }
        List<String> lines = new ArrayList<>();
        if (s.isEmpty()) {
            return lines;
        }
        String[] parts = s.split("\r\n|\r|\n", -1);
        int n = parts.length;
        if (parts[n - 1].isEmpty()) {
            n--; // trailing newline terminates the last line rather than starting a new one
        }
        for (int i = 0; i < n; i++) {
            lines.add(parts[i]);
        }
        return lines;
    }

    // ---------------------------------------------------------------- ls

    static int ls(Path root, String coord, PrintStream out) throws Exit {
        String[] parts = coord.split(":", -1);
        if (parts.length < 2 || parts.length > 3) {
            throw usage("invalid coordinate: " + coord + " (expected group:artifact[:version])");
        }
        for (String p : parts) {
            if (p.isEmpty()) {
                throw usage("invalid coordinate: " + coord + " (expected group:artifact[:version])");
            }
        }
        String ga = parts[0] + ":" + parts[1];
        List<Artifact> artifacts = scan(root);
        TreeSet<String> knownGa = new TreeSet<>(Main::compareBytes);
        List<Artifact> matching = new ArrayList<>();
        for (Artifact art : artifacts) {
            knownGa.add(art.ga());
            if (art.ga().equals(ga)) {
                matching.add(art);
            }
        }
        if (matching.isEmpty()) {
            throw notFound("no artifact " + ga, ga, knownGa);
        }
        if (parts.length == 2) {
            TreeSet<String> versions = new TreeSet<>(Main::compareVersionsTotal);
            for (Artifact art : matching) {
                versions.add(art.version());
            }
            StringBuilder sb = new StringBuilder();
            for (String v : versions) {
                sb.append(v).append('\n');
            }
            out.print(sb);
            return EXIT_OK;
        }
        String version = parts[2];
        Artifact found = matching.stream().filter(a -> a.version().equals(version)).findFirst().orElse(null);
        if (found == null) {
            throw notFound("no artifact " + coord, coord, knownGa);
        }
        TreeSet<String> classes = new TreeSet<>(Main::compareBytes);
        classes.addAll(classesIn(found));
        StringBuilder sb = new StringBuilder();
        for (String c : classes) {
            sb.append(c).append('\n');
        }
        out.print(sb);
        return EXIT_OK;
    }

    // ---------------------------------------------------------------- errors

    static Exit notFound(String what, String prefix, TreeSet<String> known) {
        List<String> suggestions = new ArrayList<>();
        for (String k : known) {
            if (k.startsWith(prefix)) {
                suggestions.add(k);
                if (suggestions.size() == 3) {
                    break;
                }
            }
        }
        String msg = "error: " + what;
        if (!suggestions.isEmpty()) {
            msg += " (did you mean: " + String.join(", ", suggestions) + "?)";
        }
        return new Exit(EXIT_NOT_FOUND, msg);
    }

    // ---------------------------------------------------------------- tree scanning

    /** Finds every {@code <group...>/<artifact>/<version>/<artifact>-<version>-sources.jar} under root. */
    static List<Artifact> scan(Path root) {
        List<Artifact> result = new ArrayList<>();
        if (!Files.isDirectory(root)) {
            return result;
        }
        TreeSet<String> seen = new TreeSet<>();
        try (Stream<Path> walk = Files.walk(root)) {
            walk.filter(p -> p.getFileName() != null && p.getFileName().toString().endsWith("-sources.jar"))
                    .filter(Files::isRegularFile)
                    .sorted()
                    .forEach(p -> {
                        Path rel = root.relativize(p);
                        int n = rel.getNameCount();
                        if (n < 4) {
                            return;
                        }
                        String version = rel.getName(n - 2).toString();
                        String artifact = rel.getName(n - 3).toString();
                        if (!rel.getName(n - 1).toString().equals(artifact + "-" + version + "-sources.jar")) {
                            return;
                        }
                        List<String> groupParts = new ArrayList<>();
                        for (int i = 0; i < n - 3; i++) {
                            groupParts.add(rel.getName(i).toString());
                        }
                        String group = String.join(".", groupParts);
                        Artifact art = new Artifact(group, artifact, version, p);
                        if (seen.add(art.gav())) {
                            result.add(art);
                        }
                    });
        } catch (IOException | RuntimeException e) {
            // Unreadable parts of the tree are skipped; whatever was found is used.
        }
        return result;
    }

    /** FQCNs of every .java entry in the artifact's jar. */
    static List<String> classesIn(Artifact art) {
        List<String> classes = new ArrayList<>();
        try (ZipFile zip = new ZipFile(art.jar().toFile(), StandardCharsets.UTF_8)) {
            Enumeration<? extends ZipEntry> entries = zip.entries();
            while (entries.hasMoreElements()) {
                ZipEntry e = entries.nextElement();
                String name = e.getName();
                if (e.isDirectory() || !name.endsWith(".java")) {
                    continue;
                }
                while (name.startsWith("/")) {
                    name = name.substring(1);
                }
                String fqcn = name.substring(0, name.length() - ".java".length()).replace('/', '.');
                if (!fqcn.isEmpty()) {
                    classes.add(fqcn);
                }
            }
        } catch (IOException | RuntimeException e) {
            // Corrupt or unreadable jar: treat as containing nothing.
        }
        return classes;
    }

    static String readEntry(Artifact art, String entryName) throws Exit {
        try (ZipFile zip = new ZipFile(art.jar().toFile(), StandardCharsets.UTF_8)) {
            ZipEntry e = zip.getEntry(entryName);
            if (e == null) {
                e = zip.getEntry("/" + entryName);
            }
            if (e == null) {
                throw new Exit(EXIT_NOT_FOUND, "error: no source for " + entryName);
            }
            try (InputStream in = zip.getInputStream(e)) {
                return new String(in.readAllBytes(), StandardCharsets.UTF_8);
            }
        } catch (IOException e) {
            throw new Exit(EXIT_NOT_FOUND, "error: no source for "
                    + entryName.substring(0, entryName.length() - 5).replace('/', '.'));
        }
    }

    // ---------------------------------------------------------------- helpers

    static String simpleName(String fqcn) {
        int dot = fqcn.lastIndexOf('.');
        return dot < 0 ? fqcn : fqcn.substring(dot + 1);
    }

    /** Ordinal comparison of the UTF-8 byte encodings (unsigned). */
    static int compareBytes(String a, String b) {
        byte[] x = a.getBytes(StandardCharsets.UTF_8);
        byte[] y = b.getBytes(StandardCharsets.UTF_8);
        return java.util.Arrays.compareUnsigned(x, y);
    }

    /** Version ordering from SPEC.md: segments split on '.' and '-', numeric where both are digits. */
    static int compareVersions(String a, String b) {
        String[] x = a.split("[.-]", -1);
        String[] y = b.split("[.-]", -1);
        int n = Math.min(x.length, y.length);
        for (int i = 0; i < n; i++) {
            int c;
            if (isNumber(x[i]) && isNumber(y[i])) {
                c = compareNumeric(x[i], y[i]);
            } else {
                c = compareBytes(x[i], y[i]);
            }
            if (c != 0) {
                return c;
            }
        }
        return Integer.compare(x.length, y.length);
    }

    /** {@link #compareVersions} with a byte-order tie-break so distinct strings never collapse. */
    static int compareVersionsTotal(String a, String b) {
        int c = compareVersions(a, b);
        return c != 0 ? c : compareBytes(a, b);
    }

    private static boolean isNumber(String s) {
        if (s.isEmpty()) {
            return false;
        }
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            if (c < '0' || c > '9') {
                return false;
            }
        }
        return true;
    }

    private static int compareNumeric(String a, String b) {
        String x = a.replaceFirst("^0+", "");
        String y = b.replaceFirst("^0+", "");
        if (x.length() != y.length()) {
            return Integer.compare(x.length(), y.length());
        }
        return x.compareTo(y);
    }
}
