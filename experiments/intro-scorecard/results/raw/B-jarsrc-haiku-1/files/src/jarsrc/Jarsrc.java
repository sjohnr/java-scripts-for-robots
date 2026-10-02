package jarsrc;

import java.io.IOException;
import java.io.PrintStream;
import java.io.UncheckedIOException;
import java.math.BigInteger;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.Comparator;
import java.util.Enumeration;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import java.util.function.Predicate;
import java.util.regex.Pattern;
import java.util.stream.Stream;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;

import static java.nio.charset.StandardCharsets.UTF_8;

/** Queries a Gradle-cache-like tree of {@code *-sources.jar} files. Behaviour is defined by SPEC.md. */
public final class Jarsrc {

    private static final String SOURCES_SUFFIX = "-sources.jar";
    private static final String JAVA_SUFFIX = ".java";
    private static final int MAX_SUGGESTIONS = 3;

    private static final String HELP = String.join("\n",
            "usage: jarsrc find <fqcn-or-glob> [--root DIR]",
            "       jarsrc cat <fqcn> [--lines A:B] [--root DIR]",
            "       jarsrc ls <group:artifact[:version]> [--root DIR]",
            "       jarsrc --help",
            "",
            "subcommands:",
            "  find   list classes matching an FQCN or glob, with each version containing them",
            "         (* matches within a dotted segment, ** matches across segments)",
            "  cat    print a class's source from its highest version",
            "  ls     list an artifact's versions, or the classes in one version",
            "",
            "flags:",
            "  --root DIR   directory to search (default: .)",
            "  --lines A:B  cat only: print only lines A through B (1-indexed, inclusive)",
            "  --help       print this message and exit",
            "");

    /** Ends the run with an exit code and an optional single line for stderr. */
    static final class Exit extends RuntimeException {
        private static final long serialVersionUID = 1L;
        final int code;

        Exit(int code, String message) {
            super(message, null, false, false);
            this.code = code;
        }
    }

    /** A sources jar found under the root, identified by its position in the cache layout. */
    record Jar(String group, String artifact, String version, Path path) {
        String ga() {
            return group + ":" + artifact;
        }

        String gav() {
            return ga() + ":" + version;
        }
    }

    /** A class found in a jar, with the entry name that holds its source. */
    record Hit(Jar jar, String entry) {
    }

    /** Inclusive, 1-indexed line range; values already clamped to the int range. */
    record LineRange(int from, int to) {
    }

    public static void main(String[] args) {
        StringBuilder out = new StringBuilder();
        int code = 0;
        try {
            run(args, out);
        } catch (Exit e) {
            code = e.code;
            if (e.getMessage() != null) {
                write(System.err, e.getMessage() + "\n");
            }
        }
        if (code == 0) {
            write(System.out, out.toString());
        }
        System.exit(code);
    }

    static void run(String[] args, StringBuilder out) {
        for (String arg : args) {
            if (arg.equals("--help")) {
                out.append(HELP);
                return;
            }
        }
        if (args.length == 0) {
            throw usage("missing subcommand");
        }
        String cmd = args[0];
        if (cmd.startsWith("-")) {
            throw usage("unknown flag: " + cmd);
        }
        if (!cmd.equals("find") && !cmd.equals("cat") && !cmd.equals("ls")) {
            throw usage("unknown subcommand: " + cmd);
        }

        String rootArg = ".";
        String linesArg = null;
        List<String> positional = new ArrayList<>();
        for (int i = 1; i < args.length; i++) {
            String arg = args[i];
            boolean isRoot = arg.equals("--root");
            boolean isLines = arg.equals("--lines") && cmd.equals("cat");
            if (isRoot || isLines) {
                if (++i >= args.length) {
                    throw usage(arg + " requires a value");
                }
                if (isRoot) {
                    rootArg = args[i];
                } else {
                    linesArg = args[i];
                }
            } else if (arg.startsWith("-")) {
                throw usage("unknown flag: " + arg);
            } else {
                positional.add(arg);
            }
        }
        if (positional.isEmpty()) {
            throw usage("missing argument for " + cmd);
        }
        if (positional.size() > 1) {
            throw usage("unexpected argument: " + positional.get(1));
        }
        String target = positional.get(0);
        Path root = Paths.get(rootArg);
        LineRange range = linesArg == null ? null : parseLines(linesArg);

        switch (cmd) {
            case "find" -> find(target, scan(root), out);
            case "cat" -> cat(target, range, scan(root), out);
            default -> ls(target, scan(root), root, out);
        }
    }

    // ---- subcommands -------------------------------------------------------

    private static void find(String pattern, List<Jar> jars, StringBuilder out) {
        Predicate<String> matches = glob(pattern);
        Map<String, List<Hit>> byFqcn = classIndex(jars);
        List<String[]> rows = new ArrayList<>();
        for (Map.Entry<String, List<Hit>> e : byFqcn.entrySet()) {
            if (!matches.test(e.getKey())) {
                continue;
            }
            for (Hit hit : e.getValue()) {
                rows.add(new String[] {e.getKey(), hit.jar().gav()});
            }
        }
        if (rows.isEmpty()) {
            throw new Exit(1, notFound("no matches for " + pattern, lastSegment(pattern), simpleNames(byFqcn)));
        }
        rows.sort(Comparator.<String[], String>comparing(r -> r[0], Jarsrc::compareBytes)
                .thenComparing(r -> r[1], Jarsrc::compareBytes));
        for (String[] row : rows) {
            out.append(row[0]).append(' ').append(row[1]).append('\n');
        }
    }

    private static void cat(String fqcn, LineRange range, List<Jar> jars, StringBuilder out) {
        Map<String, List<Hit>> byFqcn = classIndex(jars);
        List<Hit> hits = byFqcn.getOrDefault(fqcn, List.of());
        if (hits.isEmpty()) {
            throw new Exit(1, notFound("no source for " + fqcn, lastSegment(fqcn), simpleNames(byFqcn)));
        }

        TreeSet<String> gas = new TreeSet<>(Jarsrc::compareBytes);
        for (Hit hit : hits) {
            gas.add(hit.jar().ga());
        }
        if (gas.size() > 1) {
            throw new Exit(2, "error: " + fqcn + " is ambiguous across: " + String.join(", ", gas));
        }

        Hit best = hits.get(0);
        for (Hit hit : hits) {
            if (VERSION_ORDER.compare(hit.jar().version(), best.jar().version()) > 0) {
                best = hit;
            }
        }
        List<String> lines = textLines(readEntry(best));
        int from = 1;
        int to = lines.size();
        if (range != null) {
            from = Math.max(from, range.from());
            to = Math.min(to, range.to());
        }
        for (int n = from; n <= to; n++) {
            out.append(lines.get(n - 1)).append('\n');
        }
    }

    private static void ls(String target, List<Jar> jars, Path root, StringBuilder out) {
        String[] parts = target.split(":", -1);
        boolean wellFormed = (parts.length == 2 || parts.length == 3)
                && Arrays.stream(parts).noneMatch(String::isEmpty);
        if (!wellFormed) {
            throw usage("expected group:artifact[:version], got: " + target);
        }
        String ga = parts[0] + ":" + parts[1];
        List<Jar> inGa = jars.stream().filter(j -> j.ga().equals(ga)).toList();

        if (parts.length == 2) {
            if (inGa.isEmpty()) {
                throw new Exit(1, notFound("no artifact " + ga, ga, gaNames(jars)));
            }
            List<String> versions = inGa.stream().map(Jar::version).sorted(VERSION_ORDER).toList();
            for (String version : versions) {
                out.append(version).append('\n');
            }
            return;
        }

        String version = parts[2];
        Jar jar = inGa.stream().filter(j -> j.version().equals(version)).findFirst().orElse(null);
        if (jar == null) {
            String what = inGa.isEmpty() ? ga : target;
            throw new Exit(1, notFound("no artifact " + what, ga, gaNames(jars)));
        }
        for (String fqcn : new TreeSet<>(classes(jar).keySet())) {
            out.append(fqcn).append('\n');
        }
    }

    // ---- cache layout ------------------------------------------------------

    /** Finds every {@code <root>/<group...>/<artifact>/<version>/<artifact>-<version>-sources.jar}. */
    static List<Jar> scan(Path root) {
        List<Jar> jars = new ArrayList<>();
        if (!Files.isDirectory(root)) {
            return jars;
        }
        try (Stream<Path> walk = Files.walk(root)) {
            walk.filter(Files::isRegularFile).forEach(file -> {
                Jar jar = toJar(root, file);
                if (jar != null) {
                    jars.add(jar);
                }
            });
        } catch (IOException | UncheckedIOException e) {
            throw new Exit(2, "error: cannot read " + root);
        }
        return jars;
    }

    private static Jar toJar(Path root, Path file) {
        String name = file.getFileName().toString();
        if (!name.endsWith(SOURCES_SUFFIX)) {
            return null;
        }
        List<String> segments = new ArrayList<>();
        for (Path part : root.relativize(file)) {
            segments.add(part.toString());
        }
        int n = segments.size();
        if (n < 4) {
            return null;
        }
        String artifact = segments.get(n - 3);
        String version = segments.get(n - 2);
        if (!name.equals(artifact + "-" + version + SOURCES_SUFFIX)) {
            return null;
        }
        String group = String.join(".", segments.subList(0, n - 3));
        return new Jar(group, artifact, version, file);
    }

    /** Maps each FQCN to the jars that contain it, in the order the jars were given. */
    private static Map<String, List<Hit>> classIndex(List<Jar> jars) {
        Map<String, List<Hit>> byFqcn = new HashMap<>();
        for (Jar jar : jars) {
            classes(jar).forEach((fqcn, entry) ->
                    byFqcn.computeIfAbsent(fqcn, k -> new ArrayList<>()).add(new Hit(jar, entry)));
        }
        return byFqcn;
    }

    /** FQCN to entry name for every {@code .java} entry in the jar. */
    private static Map<String, String> classes(Jar jar) {
        Map<String, String> classes = new LinkedHashMap<>();
        try (ZipFile zip = new ZipFile(jar.path().toFile())) {
            Enumeration<? extends ZipEntry> entries = zip.entries();
            while (entries.hasMoreElements()) {
                ZipEntry entry = entries.nextElement();
                String name = entry.getName();
                if (entry.isDirectory() || !name.endsWith(JAVA_SUFFIX) || name.length() == JAVA_SUFFIX.length()) {
                    continue;
                }
                String fqcn = name.substring(0, name.length() - JAVA_SUFFIX.length()).replace('/', '.');
                classes.putIfAbsent(fqcn, name);
            }
        } catch (IOException e) {
            throw new Exit(2, "error: cannot read " + jar.path());
        }
        return classes;
    }

    private static byte[] readEntry(Hit hit) {
        try (ZipFile zip = new ZipFile(hit.jar().path().toFile())) {
            ZipEntry entry = zip.getEntry(hit.entry());
            return zip.getInputStream(entry).readAllBytes();
        } catch (IOException e) {
            throw new Exit(2, "error: cannot read " + hit.jar().path());
        }
    }

    // ---- matching, ordering and text ---------------------------------------

    /** {@code *} matches within a dotted segment, {@code **} matches anything; everything else is literal. */
    static Predicate<String> glob(String pattern) {
        if (!pattern.contains("*")) {
            return pattern::equals;
        }
        StringBuilder regex = new StringBuilder();
        int i = 0;
        while (i < pattern.length()) {
            if (pattern.startsWith("**", i)) {
                regex.append(".*");
                i += 2;
            } else if (pattern.charAt(i) == '*') {
                regex.append("[^.]*");
                i++;
            } else {
                regex.append(Pattern.quote(String.valueOf(pattern.charAt(i))));
                i++;
            }
        }
        Pattern compiled = Pattern.compile(regex.toString());
        return s -> compiled.matcher(s).matches();
    }

    /** Unsigned byte-wise comparison of the UTF-8 encodings. */
    static int compareBytes(String a, String b) {
        return Arrays.compareUnsigned(a.getBytes(UTF_8), b.getBytes(UTF_8));
    }

    /** Version ordering from SPEC.md; ties in that ordering are broken bytewise so output is stable. */
    static final Comparator<String> VERSION_ORDER = (a, b) -> {
        int c = compareVersions(a, b);
        return c != 0 ? c : compareBytes(a, b);
    };

    static int compareVersions(String a, String b) {
        String[] x = a.split("[.-]", -1);
        String[] y = b.split("[.-]", -1);
        int n = Math.min(x.length, y.length);
        for (int i = 0; i < n; i++) {
            int c = compareSegments(x[i], y[i]);
            if (c != 0) {
                return c;
            }
        }
        return Integer.compare(x.length, y.length);
    }

    private static int compareSegments(String a, String b) {
        if (isDigits(a) && isDigits(b)) {
            String na = stripLeadingZeros(a);
            String nb = stripLeadingZeros(b);
            if (na.length() != nb.length()) {
                return Integer.compare(na.length(), nb.length());
            }
            return Integer.signum(na.compareTo(nb));
        }
        return compareBytes(a, b);
    }

    private static boolean isDigits(String s) {
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

    private static String stripLeadingZeros(String s) {
        int i = 0;
        while (i < s.length() - 1 && s.charAt(i) == '0') {
            i++;
        }
        return s.substring(i);
    }

    /** Splits source into lines on LF, CRLF or CR, drops trailing whitespace, and drops the final terminator. */
    static List<String> textLines(byte[] data) {
        String[] parts = new String(data, UTF_8).split("\r\n|\r|\n", -1);
        int n = parts.length;
        if (parts[n - 1].isEmpty()) {
            n--;
        }
        List<String> lines = new ArrayList<>(n);
        for (int i = 0; i < n; i++) {
            lines.add(stripTrailingWhitespace(parts[i]));
        }
        return lines;
    }

    private static String stripTrailingWhitespace(String s) {
        int end = s.length();
        while (end > 0 && " \t\f\u000B".indexOf(s.charAt(end - 1)) >= 0) {
            end--;
        }
        return s.substring(0, end);
    }

    private static LineRange parseLines(String spec) {
        int colon = spec.indexOf(':');
        if (colon < 0) {
            throw usage("expected --lines A:B, got: " + spec);
        }
        return new LineRange(parseLineNumber(spec.substring(0, colon), spec),
                parseLineNumber(spec.substring(colon + 1), spec));
    }

    private static int parseLineNumber(String digits, String spec) {
        if (!digits.matches("-?[0-9]+")) {
            throw usage("expected --lines A:B, got: " + spec);
        }
        BigInteger value = new BigInteger(digits);
        if (value.signum() < 0) {
            return 0;
        }
        return value.compareTo(BigInteger.valueOf(Integer.MAX_VALUE)) > 0 ? Integer.MAX_VALUE : value.intValue();
    }

    // ---- suggestions -------------------------------------------------------

    private static String notFound(String what, String prefix, Collection<String> candidates) {
        List<String> suggestions = candidates.stream()
                .filter(c -> c.startsWith(prefix))
                .sorted(Jarsrc::compareBytes)
                .limit(MAX_SUGGESTIONS)
                .toList();
        String message = "error: " + what;
        if (!suggestions.isEmpty()) {
            message += " (did you mean: " + String.join(", ", suggestions) + "?)";
        }
        return message;
    }

    private static Set<String> simpleNames(Map<String, List<Hit>> byFqcn) {
        Set<String> names = new TreeSet<>(Jarsrc::compareBytes);
        for (String fqcn : byFqcn.keySet()) {
            names.add(lastSegment(fqcn));
        }
        return names;
    }

    private static Set<String> gaNames(List<Jar> jars) {
        Set<String> names = new TreeSet<>(Jarsrc::compareBytes);
        for (Jar jar : jars) {
            names.add(jar.ga());
        }
        return names;
    }

    private static String lastSegment(String name) {
        return name.substring(name.lastIndexOf('.') + 1);
    }

    // ---- plumbing ----------------------------------------------------------

    private static Exit usage(String message) {
        return new Exit(2, "error: " + message);
    }

    private static void write(PrintStream stream, String text) {
        byte[] bytes = text.getBytes(UTF_8);
        stream.write(bytes, 0, bytes.length);
        stream.flush();
    }
}
