import java.io.IOException;
import java.io.InputStream;
import java.io.PrintStream;
import java.math.BigInteger;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.List;
import java.util.Set;
import java.util.TreeSet;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;
import java.util.stream.Stream;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;

/**
 * jarsrc: query a Gradle-cache-like tree of *-sources.jar files.
 * See SPEC.md for the full behavioural contract.
 */
public final class Jarsrc {

    private static final int EXIT_OK = 0;
    private static final int EXIT_NOT_FOUND = 1;
    private static final int EXIT_USAGE = 2;

    private static final String JAR_SUFFIX = "-sources.jar";
    private static final String JAVA_SUFFIX = ".java";

    private static final String HELP = String.join("\n",
            "Usage: jarsrc <command> [options]",
            "",
            "Commands:",
            "  find <fqcn-or-glob> [--root DIR]",
            "      List classes matching an FQCN or glob ('*' within a segment, '**' across dots).",
            "  cat <fqcn> [--lines A:B] [--root DIR]",
            "      Print the source of a class from its highest available version.",
            "  ls <group:artifact[:version]> [--root DIR]",
            "      List versions of an artifact, or the classes in one exact version.",
            "  --help",
            "      Show this help and exit.",
            "",
            "Options:",
            "  --root DIR     Root of the Gradle-style cache tree (default: .)",
            "  --lines A:B    cat only: print lines A through B (1-indexed, inclusive)",
            "  --help         Show this help and exit.");

    /** Byte-wise (UTF-8) ordering, used for all lexicographic sorts. */
    private static final Comparator<String> BYTES =
            (a, b) -> Arrays.compareUnsigned(utf8(a), utf8(b));

    /** A failure that terminates the program with the given exit code and one stderr line. */
    private static final class Failure extends RuntimeException {
        private static final long serialVersionUID = 1L;
        final int code;

        Failure(int code, String message) {
            super(message);
            this.code = code;
        }
    }

    /** One sources jar found under the root. */
    private record Artifact(String group, String artifact, String version, Path jar) {
        String ga() {
            return group + ":" + artifact;
        }

        String gav() {
            return ga() + ":" + version;
        }
    }

    /** A class found by find: its FQCN and the artifact that contains it. */
    private record Hit(String fqcn, Artifact artifact) {
    }

    public static void main(String[] argv) {
        int code;
        try {
            String out = run(argv);
            writeBytes(System.out, out);
            code = EXIT_OK;
        } catch (Failure f) {
            writeBytes(System.err, f.getMessage() + "\n");
            code = f.code;
        } catch (IOException e) {
            writeBytes(System.err, "error: " + e.getMessage() + "\n");
            code = EXIT_USAGE;
        }
        System.exit(code);
    }

    /** Parses the arguments, runs the command, and returns the text for stdout. */
    static String run(String[] argv) throws IOException {
        String root = ".";
        String lines = null;
        boolean help = false;
        List<String> positional = new ArrayList<>();

        for (int i = 0; i < argv.length; i++) {
            String arg = argv[i];
            if (arg.equals("--help")) {
                help = true;
            } else if (arg.equals("--root")) {
                root = requireValue(argv, ++i, "--root");
            } else if (arg.startsWith("--root=")) {
                root = arg.substring("--root=".length());
            } else if (arg.equals("--lines")) {
                lines = requireValue(argv, ++i, "--lines");
            } else if (arg.startsWith("--lines=")) {
                lines = arg.substring("--lines=".length());
            } else if (arg.startsWith("-") && arg.length() > 1) {
                throw new Failure(EXIT_USAGE, "unknown flag: " + arg);
            } else {
                positional.add(arg);
            }
        }

        if (help) {
            return HELP + "\n";
        }
        if (positional.isEmpty()) {
            throw new Failure(EXIT_USAGE, "error: missing command");
        }

        String command = positional.get(0);
        String argName;
        switch (command) {
            case "find" -> argName = "<fqcn-or-glob>";
            case "cat" -> argName = "<fqcn>";
            case "ls" -> argName = "<group:artifact[:version]>";
            default -> throw new Failure(EXIT_USAGE, "error: unknown command: " + command);
        }
        if (lines != null && !command.equals("cat")) {
            throw new Failure(EXIT_USAGE, "unknown flag: --lines");
        }
        if (positional.size() < 2) {
            throw new Failure(EXIT_USAGE, "error: missing argument: " + argName);
        }
        if (positional.size() > 2) {
            throw new Failure(EXIT_USAGE, "error: unexpected argument: " + positional.get(2));
        }

        String target = positional.get(1);
        Path rootDir = Path.of(root).toAbsolutePath().normalize();
        return switch (command) {
            case "find" -> find(target, rootDir);
            case "cat" -> cat(target, lines, rootDir);
            default -> ls(target, rootDir);
        };
    }

    private static String requireValue(String[] argv, int index, String flag) {
        if (index >= argv.length) {
            throw new Failure(EXIT_USAGE, "error: " + flag + " requires a value");
        }
        return argv[index];
    }

    // ---------------------------------------------------------------- find

    private static String find(String pattern, Path root) throws IOException {
        Pattern re = globToRegex(pattern);
        List<Artifact> artifacts = scan(root);
        List<Hit> hits = new ArrayList<>();
        for (Artifact a : artifacts) {
            for (String fqcn : fqcnsIn(a.jar())) {
                if (re.matcher(fqcn).matches()) {
                    hits.add(new Hit(fqcn, a));
                }
            }
        }
        if (hits.isEmpty()) {
            throw notFound("no matches for " + pattern, simpleName(pattern), simpleNames(artifacts));
        }
        hits.sort(Comparator.comparing(Hit::fqcn, BYTES)
                .thenComparing(h -> h.artifact().gav(), BYTES));

        StringBuilder out = new StringBuilder();
        for (Hit h : hits) {
            out.append(h.fqcn()).append(' ').append(h.artifact().gav()).append('\n');
        }
        return out.toString();
    }

    /** Translates a glob where '**' spans dots, '*' does not, and everything else is literal. */
    static Pattern globToRegex(String glob) {
        StringBuilder re = new StringBuilder();
        StringBuilder literal = new StringBuilder();
        int i = 0;
        while (i < glob.length()) {
            if (glob.startsWith("**", i)) {
                flushLiteral(re, literal);
                re.append(".*");
                i += 2;
            } else if (glob.charAt(i) == '*') {
                flushLiteral(re, literal);
                re.append("[^.]*");
                i++;
            } else {
                literal.append(glob.charAt(i));
                i++;
            }
        }
        flushLiteral(re, literal);
        return Pattern.compile(re.toString());
    }

    private static void flushLiteral(StringBuilder re, StringBuilder literal) {
        if (literal.length() > 0) {
            re.append(Pattern.quote(literal.toString()));
            literal.setLength(0);
        }
    }

    // ----------------------------------------------------------------- cat

    private static String cat(String fqcn, String linesSpec, Path root) throws IOException {
        String entry = entryName(fqcn);
        List<Artifact> artifacts = scan(root);

        List<Artifact> matches = new ArrayList<>();
        for (Artifact a : artifacts) {
            if (hasEntry(a.jar(), entry)) {
                matches.add(a);
            }
        }
        if (matches.isEmpty()) {
            throw notFound("no source for " + fqcn, simpleName(fqcn), simpleNames(artifacts));
        }

        Set<String> gas = new TreeSet<>(BYTES);
        for (Artifact a : matches) {
            gas.add(a.ga());
        }
        if (gas.size() > 1) {
            throw new Failure(EXIT_USAGE,
                    "error: " + fqcn + " is ambiguous across: " + String.join(", ", gas));
        }

        Artifact best = matches.get(0);
        for (Artifact a : matches) {
            if (compareVersions(a.version(), best.version()) > 0) {
                best = a;
            }
        }

        List<String> lines = lines(readEntry(best.jar(), entry));
        int from = 1;
        int to = lines.size();
        if (linesSpec != null) {
            int[] range = parseRange(linesSpec);
            from = Math.max(range[0], 1);
            to = Math.min(range[1], lines.size());
        }

        StringBuilder out = new StringBuilder();
        for (int n = from; n <= to; n++) {
            out.append(lines.get(n - 1)).append('\n');
        }
        return out.toString();
    }

    /** Parses "A:B" into a pair of non-negative integers, clamped to the int range. */
    private static int[] parseRange(String spec) {
        Matcher m = Pattern.compile("(\\d+):(\\d+)").matcher(spec);
        if (!m.matches()) {
            throw new Failure(EXIT_USAGE, "error: invalid --lines value: " + spec + " (expected A:B)");
        }
        return new int[] {clampInt(m.group(1)), clampInt(m.group(2))};
    }

    private static int clampInt(String digits) {
        return new BigInteger(digits).min(BigInteger.valueOf(Integer.MAX_VALUE)).intValue();
    }

    /**
     * Splits source text into lines: LF/CRLF/CR all end a line, trailing whitespace is removed,
     * and a final newline does not start an extra empty line.
     */
    static List<String> lines(String text) {
        List<String> result = new ArrayList<>();
        String normalized = text.replace("\r\n", "\n").replace('\r', '\n');
        if (normalized.isEmpty()) {
            return result;
        }
        result.addAll(Arrays.asList(normalized.split("\n", -1)));
        if (normalized.endsWith("\n")) {
            result.remove(result.size() - 1);
        }
        result.replaceAll(Jarsrc::rstrip);
        return result;
    }

    private static String rstrip(String line) {
        int end = line.length();
        while (end > 0 && Character.isWhitespace(line.charAt(end - 1))) {
            end--;
        }
        return line.substring(0, end);
    }

    // ------------------------------------------------------------------ ls

    private static String ls(String target, Path root) throws IOException {
        String[] parts = target.split(":", -1);
        if (parts.length < 2 || parts.length > 3 || Arrays.stream(parts).anyMatch(String::isEmpty)) {
            throw new Failure(EXIT_USAGE,
                    "error: invalid coordinate: " + target + " (expected group:artifact[:version])");
        }
        String ga = parts[0] + ":" + parts[1];
        List<Artifact> artifacts = scan(root);
        List<Artifact> sameGa = artifacts.stream().filter(a -> a.ga().equals(ga)).toList();

        StringBuilder out = new StringBuilder();
        if (parts.length == 2) {
            if (sameGa.isEmpty()) {
                throw notFound("no artifact " + ga, ga, gaNames(artifacts));
            }
            List<String> versions = sameGa.stream().map(Artifact::version).distinct()
                    .sorted(Jarsrc::compareVersions).toList();
            for (String v : versions) {
                out.append(v).append('\n');
            }
            return out.toString();
        }

        String version = parts[2];
        Artifact match = sameGa.stream().filter(a -> a.version().equals(version)).findFirst()
                .orElse(null);
        if (match == null) {
            // Suggestions are computed against the query as typed, so for a missing
            // version they are normally empty; for a missing artifact they use the GA.
            String subject = sameGa.isEmpty() ? ga : target;
            throw notFound("no artifact " + subject, subject, gaNames(artifacts));
        }
        for (String fqcn : fqcnsIn(match.jar()).stream().sorted(BYTES).toList()) {
            out.append(fqcn).append('\n');
        }
        return out.toString();
    }

    // ------------------------------------------------------------- helpers

    /**
     * Finds every sources jar at &lt;root&gt;/&lt;group segments&gt;/&lt;artifact&gt;/&lt;version&gt;/&lt;artifact&gt;-&lt;version&gt;-sources.jar.
     * The group is the remaining leading path segments joined with dots.
     */
    static List<Artifact> scan(Path root) throws IOException {
        if (!Files.isDirectory(root)) {
            return List.of();
        }
        List<Path> jars;
        try (Stream<Path> walk = Files.walk(root)) {
            jars = walk.filter(Files::isRegularFile)
                    .filter(p -> p.getFileName().toString().endsWith(JAR_SUFFIX))
                    .toList();
        }

        List<Artifact> result = new ArrayList<>();
        for (Path jar : jars) {
            Path rel = root.relativize(jar);
            int n = rel.getNameCount();
            if (n < 4) {
                continue;
            }
            String version = rel.getName(n - 2).toString();
            String artifact = rel.getName(n - 3).toString();
            if (!rel.getFileName().toString().equals(artifact + "-" + version + JAR_SUFFIX)) {
                continue;
            }
            String group = Stream.iterate(0, i -> i < n - 3, i -> i + 1)
                    .map(i -> rel.getName(i).toString())
                    .collect(Collectors.joining("."));
            result.add(new Artifact(group, artifact, version, jar));
        }
        return result;
    }

    /** FQCNs of every .java entry in the jar, in jar order. */
    private static List<String> fqcnsIn(Path jar) {
        try (ZipFile zip = new ZipFile(jar.toFile())) {
            return zip.stream()
                    .map(ZipEntry::getName)
                    .filter(name -> name.endsWith(JAVA_SUFFIX))
                    .map(name -> name.substring(0, name.length() - JAVA_SUFFIX.length())
                            .replace('/', '.'))
                    .toList();
        } catch (IOException e) {
            throw new Failure(EXIT_USAGE, "error: cannot read " + jar);
        }
    }

    private static boolean hasEntry(Path jar, String entry) {
        try (ZipFile zip = new ZipFile(jar.toFile())) {
            return zip.getEntry(entry) != null;
        } catch (IOException e) {
            throw new Failure(EXIT_USAGE, "error: cannot read " + jar);
        }
    }

    private static String readEntry(Path jar, String entry) {
        try (ZipFile zip = new ZipFile(jar.toFile())) {
            ZipEntry e = zip.getEntry(entry);
            try (InputStream in = zip.getInputStream(e)) {
                return new String(in.readAllBytes(), StandardCharsets.UTF_8);
            }
        } catch (IOException ex) {
            throw new Failure(EXIT_USAGE, "error: cannot read " + jar);
        }
    }

    private static String entryName(String fqcn) {
        return fqcn.replace('.', '/') + JAVA_SUFFIX;
    }

    /** Simple name of every class in every jar (the segment after the last dot). */
    private static Set<String> simpleNames(List<Artifact> artifacts) {
        Set<String> names = new TreeSet<>(BYTES);
        for (Artifact a : artifacts) {
            for (String fqcn : fqcnsIn(a.jar())) {
                names.add(simpleName(fqcn));
            }
        }
        return names;
    }

    private static Set<String> gaNames(List<Artifact> artifacts) {
        Set<String> names = new TreeSet<>(BYTES);
        for (Artifact a : artifacts) {
            names.add(a.ga());
        }
        return names;
    }

    private static String simpleName(String fqcnOrQuery) {
        return fqcnOrQuery.substring(fqcnOrQuery.lastIndexOf('.') + 1);
    }

    /** Builds the one-line "not found" failure, with up to three prefix suggestions. */
    private static Failure notFound(String what, String prefix, Set<String> known) {
        List<String> suggestions = known.stream()
                .filter(k -> k.startsWith(prefix))
                .sorted(BYTES)
                .limit(3)
                .toList();
        String message = "error: " + what;
        if (!suggestions.isEmpty()) {
            message += " (did you mean: " + String.join(", ", suggestions) + "?)";
        }
        return new Failure(EXIT_NOT_FOUND, message);
    }

    // ------------------------------------------------------------ versions

    /** Orders versions per SPEC.md: split on '.' and '-', compare segments, longer wins on tie. */
    static int compareVersions(String a, String b) {
        String[] x = a.split("[.-]", -1);
        String[] y = b.split("[.-]", -1);
        int common = Math.min(x.length, y.length);
        for (int i = 0; i < common; i++) {
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
            return na.compareTo(nb);
        }
        return Arrays.compareUnsigned(utf8(a), utf8(b));
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

    private static String stripLeadingZeros(String digits) {
        int i = 0;
        while (i < digits.length() - 1 && digits.charAt(i) == '0') {
            i++;
        }
        return digits.substring(i);
    }

    private static byte[] utf8(String s) {
        return s.getBytes(StandardCharsets.UTF_8);
    }

    private static void writeBytes(PrintStream stream, String text) {
        byte[] bytes = utf8(text);
        stream.write(bytes, 0, bytes.length);
        stream.flush();
    }
}
