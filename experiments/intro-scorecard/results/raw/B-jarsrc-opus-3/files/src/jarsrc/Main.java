package jarsrc;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.PrintStream;
import java.math.BigInteger;
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

public final class Main {

    static final String USAGE = String.join("\n",
            "Usage:",
            "  jarsrc find <fqcn-or-glob> [--root DIR]",
            "  jarsrc cat <fqcn> [--lines A:B] [--root DIR]",
            "  jarsrc ls <group:artifact[:version]> [--root DIR]",
            "  jarsrc --help",
            "",
            "Subcommands:",
            "  find   list classes matching an FQCN or glob (* = no dots, ** = any)",
            "  cat    print the source of a class from the highest version",
            "  ls     list versions of an artifact, or classes in one version",
            "",
            "Flags:",
            "  --root DIR     root of the sources-jar tree (default: .)",
            "  --lines A:B    (cat only) print only lines A..B, 1-indexed, inclusive",
            "  --help         show this help",
            "");

    /** Signals termination with a given exit code and stderr message. */
    static final class Exit extends RuntimeException {
        final int code;

        Exit(int code, String message) {
            super(message, null, false, false);
            this.code = code;
        }
    }

    /** One discovered sources jar. */
    record Jar(String group, String artifact, String version, Path path) {
        String ga() {
            return group + ":" + artifact;
        }

        String gav() {
            return ga() + ":" + version;
        }
    }

    static final Comparator<String> BYTES = Main::compareBytes;
    static final Comparator<String> VERSIONS = Main::compareVersions;

    private static final StringBuilder OUT = new StringBuilder();

    public static void main(String[] args) {
        int code;
        try {
            run(args);
            code = 0;
        } catch (Exit e) {
            if (e.getMessage() != null) {
                writeStderr(e.getMessage());
            }
            code = e.code;
        }
        flushStdout();
        System.exit(code);
    }

    static void println(String line) {
        OUT.append(stripTrailing(line)).append('\n');
    }

    private static void flushStdout() {
        if (OUT.isEmpty()) {
            return;
        }
        PrintStream ps = new PrintStream(System.out, false, StandardCharsets.UTF_8);
        ps.print(OUT);
        ps.flush();
    }

    private static void writeStderr(String msg) {
        PrintStream ps = new PrintStream(System.err, false, StandardCharsets.UTF_8);
        ps.print(stripTrailing(msg) + "\n");
        ps.flush();
    }

    static Exit usage(String msg) {
        return new Exit(2, msg);
    }

    // ---------------------------------------------------------------- CLI

    static void run(String[] args) {
        String command = null;
        List<String> positionals = new ArrayList<>();
        String root = ".";
        String lines = null;
        boolean help = false;

        for (int i = 0; i < args.length; i++) {
            String a = args[i];
            if (a.equals("--help")) {
                help = true;
            } else if (a.equals("--root") || a.equals("--lines")) {
                if (i + 1 >= args.length) {
                    throw usage("error: missing value for " + a);
                }
                String v = args[++i];
                if (a.equals("--root")) {
                    root = v;
                } else {
                    lines = v;
                }
            } else if (a.startsWith("--root=")) {
                root = a.substring("--root=".length());
            } else if (a.startsWith("--lines=")) {
                lines = a.substring("--lines=".length());
            } else if (a.startsWith("-") && a.length() > 1) {
                throw usage("unknown flag: " + a);
            } else if (command == null) {
                command = a;
            } else {
                positionals.add(a);
            }
        }

        if (help) {
            OUT.append(USAGE);
            return;
        }
        if (command == null) {
            throw usage("error: missing subcommand (see --help)");
        }
        if (!command.equals("find") && !command.equals("cat") && !command.equals("ls")) {
            throw usage("error: unknown subcommand: " + command);
        }
        if (lines != null && !command.equals("cat")) {
            throw usage("error: --lines is only valid with cat");
        }
        if (positionals.isEmpty()) {
            throw usage("error: " + command + " requires an argument");
        }
        if (positionals.size() > 1) {
            throw usage("error: unexpected argument: " + positionals.get(1));
        }
        String arg = positionals.get(0);
        if (arg.isEmpty()) {
            throw usage("error: " + command + " requires a non-empty argument");
        }

        int[] range = lines == null ? null : parseLines(lines);
        Path rootPath = Paths.get(root);
        if (!Files.isDirectory(rootPath)) {
            throw usage("error: root is not a directory: " + root);
        }
        List<Jar> jars = discover(rootPath);

        switch (command) {
            case "find" -> find(jars, arg);
            case "cat" -> cat(jars, arg, range);
            default -> ls(jars, arg);
        }
    }

    static int[] parseLines(String s) {
        int colon = s.indexOf(':');
        if (colon < 0 || s.indexOf(':', colon + 1) >= 0) {
            throw usage("error: invalid --lines value: " + s + " (expected A:B)");
        }
        try {
            long a = Long.parseLong(s.substring(0, colon));
            long b = Long.parseLong(s.substring(colon + 1));
            return new int[] {clampInt(a), clampInt(b)};
        } catch (NumberFormatException e) {
            throw usage("error: invalid --lines value: " + s + " (expected A:B)");
        }
    }

    private static int clampInt(long v) {
        return (int) Math.max(Integer.MIN_VALUE, Math.min(Integer.MAX_VALUE, v));
    }

    // ---------------------------------------------------------- discovery

    static List<Jar> discover(Path root) {
        List<Jar> jars = new ArrayList<>();
        try (Stream<Path> walk = Files.walk(root)) {
            walk.filter(p -> p.getFileName() != null && p.getFileName().toString().endsWith("-sources.jar"))
                    .filter(Files::isRegularFile)
                    .forEach(p -> {
                        Path rel = root.relativize(p);
                        int n = rel.getNameCount();
                        if (n < 4) {
                            return;
                        }
                        String version = rel.getName(n - 2).toString();
                        String artifact = rel.getName(n - 3).toString();
                        String expected = artifact + "-" + version + "-sources.jar";
                        if (!rel.getName(n - 1).toString().equals(expected)) {
                            return;
                        }
                        StringBuilder group = new StringBuilder();
                        for (int i = 0; i < n - 3; i++) {
                            if (i > 0) {
                                group.append('.');
                            }
                            group.append(rel.getName(i).toString());
                        }
                        jars.add(new Jar(group.toString(), artifact, version, p));
                    });
        } catch (IOException | java.io.UncheckedIOException e) {
            throw usage("error: cannot read root: " + e.getMessage());
        }
        return jars;
    }

    /** FQCNs of all .java entries in the jar. */
    static List<String> classesIn(Jar jar) {
        List<String> out = new ArrayList<>();
        try (ZipFile zf = new ZipFile(jar.path().toFile())) {
            Enumeration<? extends ZipEntry> en = zf.entries();
            while (en.hasMoreElements()) {
                ZipEntry e = en.nextElement();
                String fqcn = fqcnOf(e);
                if (fqcn != null) {
                    out.add(fqcn);
                }
            }
        } catch (IOException e) {
            // Unreadable jar: treat as containing no classes.
        }
        return out;
    }

    static String fqcnOf(ZipEntry e) {
        String name = e.getName();
        if (e.isDirectory() || !name.endsWith(".java")) {
            return null;
        }
        while (name.startsWith("/")) {
            name = name.substring(1);
        }
        name = name.substring(0, name.length() - ".java".length());
        if (name.isEmpty()) {
            return null;
        }
        return name.replace('/', '.');
    }

    static String readSource(Jar jar, String fqcn) {
        try (ZipFile zf = new ZipFile(jar.path().toFile())) {
            Enumeration<? extends ZipEntry> en = zf.entries();
            while (en.hasMoreElements()) {
                ZipEntry e = en.nextElement();
                if (fqcn.equals(fqcnOf(e))) {
                    try (InputStream in = zf.getInputStream(e)) {
                        ByteArrayOutputStream bos = new ByteArrayOutputStream();
                        in.transferTo(bos);
                        return bos.toString(StandardCharsets.UTF_8);
                    }
                }
            }
        } catch (IOException e) {
            // fall through
        }
        return null;
    }

    // ------------------------------------------------------------ commands

    static void find(List<Jar> jars, String pattern) {
        Pattern re = globToRegex(pattern);
        TreeSet<String> simpleNames = new TreeSet<>(BYTES);
        // fqcn -> set of gav
        TreeMap<String, TreeSet<String>> matches = new TreeMap<>(BYTES);
        for (Jar jar : jars) {
            for (String fqcn : classesIn(jar)) {
                simpleNames.add(simpleName(fqcn));
                if (re.matcher(fqcn).matches()) {
                    matches.computeIfAbsent(fqcn, k -> new TreeSet<>(BYTES)).add(jar.gav());
                }
            }
        }
        if (matches.isEmpty()) {
            throw notFound("no matches for " + pattern, simpleNames, lastSegment(pattern));
        }
        for (Map.Entry<String, TreeSet<String>> m : matches.entrySet()) {
            for (String gav : m.getValue()) {
                println(m.getKey() + " " + gav);
            }
        }
    }

    static void cat(List<Jar> jars, String fqcn, int[] range) {
        TreeSet<String> simpleNames = new TreeSet<>(BYTES);
        // ga -> jars containing fqcn
        TreeMap<String, List<Jar>> byGa = new TreeMap<>(BYTES);
        for (Jar jar : jars) {
            for (String c : classesIn(jar)) {
                simpleNames.add(simpleName(c));
                if (c.equals(fqcn)) {
                    byGa.computeIfAbsent(jar.ga(), k -> new ArrayList<>()).add(jar);
                }
            }
        }
        if (byGa.isEmpty()) {
            throw notFound("no source for " + fqcn, simpleNames, simpleName(fqcn));
        }
        if (byGa.size() > 1) {
            throw new Exit(2, "error: " + fqcn + " is ambiguous across: " + String.join(", ", byGa.keySet()));
        }
        List<Jar> candidates = byGa.firstEntry().getValue();
        Jar best = candidates.get(0);
        for (Jar j : candidates) {
            if (compareVersions(j.version(), best.version()) > 0) {
                best = j;
            }
        }
        String src = readSource(best, fqcn);
        if (src == null) {
            throw notFound("no source for " + fqcn, simpleNames, simpleName(fqcn));
        }
        List<String> lines = splitLines(src);
        int from = 1;
        int to = lines.size();
        if (range != null) {
            from = Math.max(1, range[0]);
            to = Math.min(lines.size(), range[1]);
        }
        for (int i = from; i <= to; i++) {
            println(lines.get(i - 1));
        }
    }

    static void ls(List<Jar> jars, String coord) {
        String[] parts = coord.split(":", -1);
        if (parts.length < 2 || parts.length > 3) {
            throw usage("error: invalid coordinate: " + coord + " (expected group:artifact[:version])");
        }
        for (String p : parts) {
            if (p.isEmpty()) {
                throw usage("error: invalid coordinate: " + coord + " (expected group:artifact[:version])");
            }
        }
        String ga = parts[0] + ":" + parts[1];
        TreeSet<String> knownGas = new TreeSet<>(BYTES);
        List<Jar> gaJars = new ArrayList<>();
        for (Jar jar : jars) {
            knownGas.add(jar.ga());
            if (jar.ga().equals(ga)) {
                gaJars.add(jar);
            }
        }
        if (gaJars.isEmpty()) {
            throw notFound("no artifact " + coord, knownGas, coord);
        }
        if (parts.length == 2) {
            // Not a TreeSet: distinct strings like "1.0" and "1.00" compare equal.
            List<String> versions = new ArrayList<>();
            for (Jar j : gaJars) {
                versions.add(j.version());
            }
            versions.sort(VERSIONS.thenComparing(BYTES));
            versions.forEach(Main::println);
            return;
        }
        Jar target = null;
        for (Jar j : gaJars) {
            if (j.version().equals(parts[2])) {
                target = j;
            }
        }
        if (target == null) {
            throw notFound("no artifact " + coord, knownGas, coord);
        }
        TreeSet<String> classes = new TreeSet<>(BYTES);
        classes.addAll(classesIn(target));
        classes.forEach(Main::println);
    }

    // ------------------------------------------------------------- helpers

    static Exit notFound(String what, TreeSet<String> known, String prefix) {
        List<String> suggestions = new ArrayList<>();
        if (prefix != null && !prefix.isEmpty()) {
            for (String k : known) {
                if (k.startsWith(prefix)) {
                    suggestions.add(k);
                    if (suggestions.size() == 3) {
                        break;
                    }
                }
            }
        }
        String msg = "error: " + what;
        if (!suggestions.isEmpty()) {
            msg += " (did you mean: " + String.join(", ", suggestions) + "?)";
        }
        return new Exit(1, msg);
    }

    static String simpleName(String fqcn) {
        int dot = fqcn.lastIndexOf('.');
        return dot < 0 ? fqcn : fqcn.substring(dot + 1);
    }

    /** Last dot-separated segment of a glob, truncated at its first wildcard. */
    static String lastSegment(String glob) {
        String seg = simpleName(glob);
        int star = seg.indexOf('*');
        return star < 0 ? seg : seg.substring(0, star);
    }

    static Pattern globToRegex(String glob) {
        StringBuilder sb = new StringBuilder();
        int i = 0;
        while (i < glob.length()) {
            char c = glob.charAt(i);
            if (c == '*') {
                if (i + 1 < glob.length() && glob.charAt(i + 1) == '*') {
                    sb.append(".*");
                    i += 2;
                    while (i < glob.length() && glob.charAt(i) == '*') {
                        i++;
                    }
                } else {
                    sb.append("[^.]*");
                    i++;
                }
            } else {
                int j = i;
                while (j < glob.length() && glob.charAt(j) != '*') {
                    j++;
                }
                sb.append(Pattern.quote(glob.substring(i, j)));
                i = j;
            }
        }
        return Pattern.compile(sb.toString(), Pattern.DOTALL);
    }

    static List<String> splitLines(String src) {
        List<String> lines = new ArrayList<>();
        if (src.isEmpty()) {
            return lines;
        }
        String norm = src.replace("\r\n", "\n").replace('\r', '\n');
        if (norm.endsWith("\n")) {
            norm = norm.substring(0, norm.length() - 1);
        }
        for (String l : norm.split("\n", -1)) {
            lines.add(l);
        }
        return lines;
    }

    static String stripTrailing(String s) {
        StringBuilder sb = new StringBuilder(s.length());
        for (String line : s.split("\n", -1)) {
            if (!sb.isEmpty()) {
                sb.append('\n');
            }
            sb.append(line.stripTrailing());
        }
        return sb.toString();
    }

    static int compareBytes(String a, String b) {
        return java.util.Arrays.compareUnsigned(
                a.getBytes(StandardCharsets.UTF_8), b.getBytes(StandardCharsets.UTF_8));
    }

    static int compareVersions(String a, String b) {
        String[] sa = a.split("[.\\-]", -1);
        String[] sb = b.split("[.\\-]", -1);
        int n = Math.min(sa.length, sb.length);
        for (int i = 0; i < n; i++) {
            int c;
            if (isNumber(sa[i]) && isNumber(sb[i])) {
                c = new BigInteger(sa[i]).compareTo(new BigInteger(sb[i]));
            } else {
                c = compareBytes(sa[i], sb[i]);
            }
            if (c != 0) {
                return c;
            }
        }
        return Integer.compare(sa.length, sb.length);
    }

    static boolean isNumber(String s) {
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
}
