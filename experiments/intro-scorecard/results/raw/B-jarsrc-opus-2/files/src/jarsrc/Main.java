package jarsrc;

import java.io.IOException;
import java.io.InputStream;
import java.io.PrintStream;
import java.io.UncheckedIOException;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.nio.ByteBuffer;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.Enumeration;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.TreeSet;
import java.util.regex.Pattern;
import java.util.stream.Stream;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;

/** jarsrc: query a Gradle-cache-like tree of *-sources.jar files. See SPEC.md. */
public final class Main {

    static final int OK = 0;
    static final int NOT_FOUND = 1;
    static final int USAGE = 2;

    static final String HELP = String.join("\n",
            "Usage:",
            "  jarsrc find <fqcn-or-glob> [--root DIR]",
            "  jarsrc cat <fqcn> [--lines A:B] [--root DIR]",
            "  jarsrc ls <group:artifact[:version]> [--root DIR]",
            "  jarsrc --help",
            "",
            "Subcommands:",
            "  find   List classes matching an FQCN or glob, with their group:artifact:version.",
            "         Glob: '*' matches any run except '.', '**' matches any run including '.'.",
            "  cat    Print the source of an FQCN from the highest version that contains it.",
            "  ls     List versions of group:artifact, or classes in group:artifact:version.",
            "",
            "Flags:",
            "  --root DIR    Root of the sources-jar tree (default: .)",
            "  --lines A:B   (cat only) Print only lines A..B, 1-indexed, inclusive",
            "  --help        Show this help and exit",
            "");

    /** Thrown to terminate with a given exit code and optional stderr line. */
    static final class Exit extends RuntimeException {
        final int code;
        final String message;

        Exit(int code, String message) {
            super(message, null, false, false);
            this.code = code;
            this.message = message;
        }
    }

    static Exit usage(String msg) {
        return new Exit(USAGE, "error: " + msg);
    }

    private final PrintStream out;

    Main(PrintStream out) {
        this.out = out;
    }

    public static void main(String[] args) {
        PrintStream out = new PrintStream(new java.io.FileOutputStream(java.io.FileDescriptor.out), false,
                StandardCharsets.UTF_8);
        PrintStream err = new PrintStream(new java.io.FileOutputStream(java.io.FileDescriptor.err), false,
                StandardCharsets.UTF_8);
        int code;
        try {
            code = new Main(out).run(args);
        } catch (Exit e) {
            code = e.code;
            if (e.message != null) {
                err.print(e.message + "\n");
            }
        }
        out.flush();
        err.flush();
        System.exit(code);
    }

    // ---------------------------------------------------------------- argument parsing

    int run(String[] args) {
        List<String> positional = new ArrayList<>();
        String root = ".";
        String lines = null;
        for (int i = 0; i < args.length; i++) {
            String a = args[i];
            if (a.equals("--help")) {
                out.print(HELP);
                return OK;
            } else if (a.equals("--root") || a.equals("--lines")) {
                if (i + 1 >= args.length) {
                    throw usage(a + " requires a value");
                }
                String v = args[++i];
                if (a.equals("--root")) root = v; else lines = v;
            } else if (a.startsWith("--root=")) {
                root = a.substring("--root=".length());
            } else if (a.startsWith("--lines=")) {
                lines = a.substring("--lines=".length());
            } else if (a.startsWith("-") && a.length() > 1) {
                throw new Exit(USAGE, "unknown flag: " + a);
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
            throw usage(cmd + ": missing required argument");
        }
        if (positional.size() > 2) {
            throw usage(cmd + ": unexpected argument: " + positional.get(2));
        }
        if (lines != null && !cmd.equals("cat")) {
            throw usage("--lines is only valid with cat");
        }
        if (root.isEmpty()) {
            throw usage("--root requires a non-empty value");
        }
        String arg = positional.get(1);
        if (arg.isEmpty()) {
            throw usage(cmd + ": argument must not be empty");
        }
        Repo repo = new Repo(Paths.get(root));
        switch (cmd) {
            case "find":
                return find(repo, arg);
            case "cat":
                return cat(repo, arg, lines == null ? null : parseLines(lines));
            default:
                return ls(repo, arg);
        }
    }

    static long[] parseLines(String spec) {
        int colon = spec.indexOf(':');
        if (colon < 0) {
            throw usage("invalid --lines value: " + spec + " (expected A:B)");
        }
        long a = parseLineNumber(spec.substring(0, colon), spec);
        long b = parseLineNumber(spec.substring(colon + 1), spec);
        return new long[] {a, b};
    }

    private static long parseLineNumber(String s, String spec) {
        if (s.isEmpty() || !s.chars().allMatch(c -> c >= '0' && c <= '9')) {
            throw usage("invalid --lines value: " + spec + " (expected A:B)");
        }
        String trimmed = s.replaceFirst("^0+(?=.)", "");
        if (trimmed.length() > 18) {
            return Long.MAX_VALUE;
        }
        return Long.parseLong(trimmed);
    }

    // ---------------------------------------------------------------- subcommands

    int find(Repo repo, String query) {
        Pattern p = globToRegex(query);
        // fqcn -> set of g:a:v strings
        Map<String, Set<String>> hits = new TreeMap<>();
        for (Jar jar : repo.jars()) {
            for (String fqcn : jar.classes()) {
                if (p.matcher(fqcn).matches()) {
                    hits.computeIfAbsent(fqcn, k -> new TreeSet<>()).add(jar.gav());
                }
            }
        }
        if (hits.isEmpty()) {
            String last = lastSegment(query);
            int star = last.indexOf('*');
            String prefix = star >= 0 ? last.substring(0, star) : last;
            throw new Exit(NOT_FOUND, "error: no matches for " + query
                    + suggestions(prefix, repo.simpleNames()));
        }
        StringBuilder sb = new StringBuilder();
        for (Map.Entry<String, Set<String>> e : hits.entrySet()) {
            for (String gav : e.getValue()) {
                sb.append(e.getKey()).append(' ').append(gav).append('\n');
            }
        }
        out.print(sb);
        return OK;
    }

    int cat(Repo repo, String fqcn, long[] range) {
        // g:a -> jars of that g:a containing the fqcn
        Map<String, List<Jar>> byGa = new TreeMap<>();
        for (Jar jar : repo.jars()) {
            if (jar.classes().contains(fqcn)) {
                byGa.computeIfAbsent(jar.ga(), k -> new ArrayList<>()).add(jar);
            }
        }
        if (byGa.isEmpty()) {
            throw new Exit(NOT_FOUND, "error: no source for " + fqcn
                    + suggestions(lastSegment(fqcn), repo.simpleNames()));
        }
        if (byGa.size() > 1) {
            throw new Exit(USAGE, "error: " + fqcn + " is ambiguous across: " + String.join(", ", byGa.keySet()));
        }
        List<Jar> candidates = byGa.values().iterator().next();
        Jar best = candidates.stream().max((x, y) -> Versions.compare(x.version, y.version)).orElseThrow();
        List<String> lines = splitLines(best.read(fqcn));
        long from = 1;
        long to = lines.size();
        if (range != null) {
            from = Math.max(1, range[0]);
            to = Math.min(lines.size(), range[1]);
        }
        StringBuilder sb = new StringBuilder();
        for (long i = from; i <= to; i++) {
            sb.append(lines.get((int) (i - 1))).append('\n');
        }
        out.print(sb);
        return OK;
    }

    int ls(Repo repo, String coord) {
        String[] parts = coord.split(":", -1);
        if (parts.length < 2 || parts.length > 3) {
            throw usage("invalid coordinate: " + coord + " (expected group:artifact[:version])");
        }
        for (String part : parts) {
            if (part.isEmpty()) {
                throw usage("invalid coordinate: " + coord + " (expected group:artifact[:version])");
            }
        }
        String ga = parts[0] + ":" + parts[1];
        List<Jar> versions = new ArrayList<>();
        for (Jar jar : repo.jars()) {
            if (jar.ga().equals(ga)) {
                versions.add(jar);
            }
        }
        if (versions.isEmpty()) {
            throw new Exit(NOT_FOUND, "error: no artifact " + coord + suggestions(ga, repo.gas()));
        }
        StringBuilder sb = new StringBuilder();
        if (parts.length == 2) {
            // Distinct strings that compare equal (e.g. "1.0" vs "1.00") are both kept.
            Set<String> vs = new TreeSet<>(Comparator.comparing((String v) -> v, Versions::compare)
                    .thenComparing(Comparator.naturalOrder()));
            for (Jar j : versions) vs.add(j.version);
            for (String v : vs) sb.append(v).append('\n');
        } else {
            Set<String> classes = new TreeSet<>();
            boolean found = false;
            for (Jar j : versions) {
                if (j.version.equals(parts[2])) {
                    found = true;
                    classes.addAll(j.classes());
                }
            }
            if (!found) {
                throw new Exit(NOT_FOUND, "error: no artifact " + coord + suggestions(ga, repo.gas()));
            }
            for (String c : classes) sb.append(c).append('\n');
        }
        out.print(sb);
        return OK;
    }

    // ---------------------------------------------------------------- helpers

    static String lastSegment(String name) {
        int dot = name.lastIndexOf('.');
        return dot >= 0 ? name.substring(dot + 1) : name;
    }

    /** Returns " (did you mean: a, b, c?)" or "" when there are no prefix matches. */
    static String suggestions(String prefix, Collection<String> known) {
        if (prefix.isEmpty()) {
            return "";
        }
        List<String> matches = new ArrayList<>();
        for (String k : new TreeSet<>(known)) {
            if (k.startsWith(prefix)) {
                matches.add(k);
                if (matches.size() == 3) break;
            }
        }
        if (matches.isEmpty()) {
            return "";
        }
        return " (did you mean: " + String.join(", ", matches) + "?)";
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

    /** Splits source into lines with LF semantics; CRLF/CR normalized, trailing whitespace stripped. */
    static List<String> splitLines(String text) {
        String normalized = text.replace("\r\n", "\n").replace('\r', '\n');
        List<String> lines = new ArrayList<>();
        int start = 0;
        while (start < normalized.length()) {
            int nl = normalized.indexOf('\n', start);
            if (nl < 0) nl = normalized.length();
            lines.add(normalized.substring(start, nl).stripTrailing());
            start = nl + 1;
        }
        return lines;
    }

    // ---------------------------------------------------------------- model

    /** One sources jar at root/group.../artifact/version/artifact-version-sources.jar. */
    static final class Jar {
        final Path path;
        final String group;
        final String artifact;
        final String version;
        private Set<String> classes;

        Jar(Path path, String group, String artifact, String version) {
            this.path = path;
            this.group = group;
            this.artifact = artifact;
            this.version = version;
        }

        String ga() {
            return group + ":" + artifact;
        }

        String gav() {
            return group + ":" + artifact + ":" + version;
        }

        Set<String> classes() {
            if (classes == null) {
                classes = new TreeSet<>();
                try (ZipFile zip = new ZipFile(path.toFile(), StandardCharsets.UTF_8)) {
                    Enumeration<? extends ZipEntry> entries = zip.entries();
                    while (entries.hasMoreElements()) {
                        ZipEntry e = entries.nextElement();
                        String name = e.getName();
                        if (!e.isDirectory() && name.endsWith(".java") && name.length() > ".java".length()) {
                            classes.add(toFqcn(name));
                        }
                    }
                } catch (IOException | RuntimeException e) {
                    // Unreadable jar: treated as containing no classes (no warnings allowed).
                }
            }
            return classes;
        }

        String read(String fqcn) {
            try (ZipFile zip = new ZipFile(path.toFile(), StandardCharsets.UTF_8)) {
                Enumeration<? extends ZipEntry> entries = zip.entries();
                while (entries.hasMoreElements()) {
                    ZipEntry e = entries.nextElement();
                    if (!e.isDirectory() && e.getName().endsWith(".java") && toFqcn(e.getName()).equals(fqcn)) {
                        try (InputStream in = zip.getInputStream(e)) {
                            return decode(in.readAllBytes());
                        }
                    }
                }
            } catch (IOException e) {
                throw new UncheckedIOException(e);
            }
            throw new IllegalStateException("entry vanished: " + fqcn);
        }

        static String toFqcn(String entryName) {
            String n = entryName.startsWith("/") ? entryName.substring(1) : entryName;
            return n.substring(0, n.length() - ".java".length()).replace('/', '.');
        }

        static String decode(byte[] bytes) {
            int off = 0;
            if (bytes.length >= 3 && (bytes[0] & 0xff) == 0xEF && (bytes[1] & 0xff) == 0xBB
                    && (bytes[2] & 0xff) == 0xBF) {
                off = 3; // strip UTF-8 BOM
            }
            try {
                return StandardCharsets.UTF_8.newDecoder()
                        .onMalformedInput(CodingErrorAction.REPLACE)
                        .onUnmappableCharacter(CodingErrorAction.REPLACE)
                        .decode(ByteBuffer.wrap(bytes, off, bytes.length - off))
                        .toString();
            } catch (CharacterCodingException e) {
                throw new IllegalStateException(e);
            }
        }
    }

    /** Lazily scanned tree of sources jars. */
    static final class Repo {
        private final Path root;
        private List<Jar> jars;

        Repo(Path root) {
            this.root = root;
        }

        List<Jar> jars() {
            if (jars == null) {
                jars = scan();
            }
            return jars;
        }

        private List<Jar> scan() {
            List<Jar> found = new ArrayList<>();
            if (!Files.isDirectory(root)) {
                return found;
            }
            try (Stream<Path> walk = Files.walk(root)) {
                walk.filter(p -> p.getFileName() != null && p.getFileName().toString().endsWith("-sources.jar"))
                        .filter(Files::isRegularFile)
                        .sorted()
                        .forEach(p -> {
                            Jar j = toJar(p);
                            if (j != null) found.add(j);
                        });
            } catch (IOException | UncheckedIOException e) {
                // Partially unreadable tree: use whatever was found.
            }
            found.sort(Comparator.comparing(Jar::gav));
            return found;
        }

        private Jar toJar(Path file) {
            Path rel = root.relativize(file);
            int n = rel.getNameCount();
            // group (>=1 segment) / artifact / version / file
            if (n < 4) return null;
            String artifact = rel.getName(n - 3).toString();
            String version = rel.getName(n - 2).toString();
            String expected = artifact + "-" + version + "-sources.jar";
            if (!rel.getName(n - 1).toString().equals(expected)) return null;
            List<String> groupParts = new ArrayList<>();
            for (int i = 0; i < n - 3; i++) {
                groupParts.add(rel.getName(i).toString());
            }
            return new Jar(file, String.join(".", groupParts), artifact, version);
        }

        Set<String> simpleNames() {
            Set<String> names = new TreeSet<>();
            for (Jar j : jars()) {
                for (String c : j.classes()) names.add(lastSegment(c));
            }
            return names;
        }

        Set<String> gas() {
            Set<String> gas = new TreeSet<>();
            for (Jar j : jars()) gas.add(j.ga());
            return gas;
        }
    }

    /** Version ordering per SPEC.md: split on '.' and '-', numeric where both numeric, else byte order. */
    static final class Versions {
        static int compare(String a, String b) {
            String[] x = a.split("[.-]", -1);
            String[] y = b.split("[.-]", -1);
            int n = Math.min(x.length, y.length);
            for (int i = 0; i < n; i++) {
                int c = compareSegment(x[i], y[i]);
                if (c != 0) return c;
            }
            return Integer.compare(x.length, y.length);
        }

        static int compareSegment(String a, String b) {
            if (isNumber(a) && isNumber(b)) {
                String na = a.replaceFirst("^0+", "");
                String nb = b.replaceFirst("^0+", "");
                if (na.length() != nb.length()) return Integer.compare(na.length(), nb.length());
                return na.compareTo(nb);
            }
            return compareBytes(a.getBytes(StandardCharsets.UTF_8), b.getBytes(StandardCharsets.UTF_8));
        }

        static boolean isNumber(String s) {
            if (s.isEmpty()) return false;
            for (int i = 0; i < s.length(); i++) {
                char c = s.charAt(i);
                if (c < '0' || c > '9') return false;
            }
            return true;
        }

        static int compareBytes(byte[] a, byte[] b) {
            int n = Math.min(a.length, b.length);
            for (int i = 0; i < n; i++) {
                int c = Integer.compare(a[i] & 0xff, b[i] & 0xff);
                if (c != 0) return c;
            }
            return Integer.compare(a.length, b.length);
        }
    }
}
