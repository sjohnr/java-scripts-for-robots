import java.io.IOException;
import java.io.InputStream;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.TreeSet;
import java.util.regex.Pattern;
import java.util.stream.Stream;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;

public final class Jarsrc {

    static final String HELP = String.join("\n",
            "Usage:",
            "  jarsrc find <fqcn-or-glob> [--root DIR]",
            "  jarsrc cat <fqcn> [--lines A:B] [--root DIR]",
            "  jarsrc ls <group:artifact[:version]> [--root DIR]",
            "  jarsrc --help",
            "",
            "Subcommands:",
            "  find   list classes matching an FQCN or glob (* = within segment, ** = across segments)",
            "  cat    print the source of a class from the highest version containing it",
            "  ls     list versions of group:artifact, or classes in group:artifact:version",
            "",
            "Flags:",
            "  --root DIR    root of the sources-jar tree (default: .)",
            "  --lines A:B   (cat only) print only lines A through B, 1-indexed, inclusive",
            "  --help        show this help and exit",
            "");

    static final PrintStream OUT = new PrintStream(System.out, false, StandardCharsets.UTF_8);
    static final PrintStream ERR = new PrintStream(System.err, false, StandardCharsets.UTF_8);

    static final class UsageException extends Exception {
        UsageException(String msg) { super(msg); }
    }

    /** One sources jar: group:artifact:version plus its path. */
    record Artifact(String group, String artifact, String version, Path jar) {
        String ga() { return group + ":" + artifact; }
        String gav() { return group + ":" + artifact + ":" + version; }
    }

    public static void main(String[] args) {
        int code;
        try {
            code = run(args);
        } catch (UsageException e) {
            ERR.print(e.getMessage() + "\n");
            code = 2;
        } catch (IOException e) {
            ERR.print("error: " + e.getMessage() + "\n");
            code = 2;
        }
        OUT.flush();
        ERR.flush();
        System.exit(code);
    }

    static int run(String[] args) throws UsageException, IOException {
        String root = ".";
        String lines = null;
        List<String> positional = new ArrayList<>();
        for (String a : args) {
            if (a.equals("--help")) {
                OUT.print(HELP);
                return 0;
            }
        }
        for (int i = 0; i < args.length; i++) {
            String a = args[i];
            if (a.equals("--root") || a.equals("--lines")) {
                if (i + 1 >= args.length) throw new UsageException("error: missing value for " + a);
                String v = args[++i];
                if (a.equals("--root")) root = v; else lines = v;
            } else if (a.startsWith("--root=")) {
                root = a.substring("--root=".length());
            } else if (a.startsWith("--lines=")) {
                lines = a.substring("--lines=".length());
            } else if (a.startsWith("-") && a.length() > 1) {
                throw new UsageException("unknown flag: " + a);
            } else {
                positional.add(a);
            }
        }
        if (positional.isEmpty()) throw new UsageException("error: missing subcommand");
        String cmd = positional.get(0);
        switch (cmd) {
            case "find", "cat", "ls" -> { }
            default -> throw new UsageException("error: unknown subcommand: " + cmd);
        }
        if (positional.size() < 2) throw new UsageException("error: missing argument for " + cmd);
        if (positional.size() > 2) throw new UsageException("error: unexpected argument: " + positional.get(2));
        if (lines != null && !cmd.equals("cat")) throw new UsageException("unknown flag: --lines");
        String arg = positional.get(1);
        int[] range = lines == null ? null : parseLines(lines);

        List<Artifact> artifacts = scan(Paths.get(root));
        return switch (cmd) {
            case "find" -> find(artifacts, arg);
            case "cat" -> cat(artifacts, arg, range);
            default -> ls(artifacts, arg);
        };
    }

    static int[] parseLines(String s) throws UsageException {
        int colon = s.indexOf(':');
        if (colon < 0) throw new UsageException("error: invalid --lines value: " + s);
        try {
            String a = s.substring(0, colon), b = s.substring(colon + 1);
            if (!a.matches("[0-9]+") || !b.matches("[0-9]+")) throw new NumberFormatException();
            return new int[] {Integer.parseInt(a), Integer.parseInt(b)};
        } catch (NumberFormatException e) {
            throw new UsageException("error: invalid --lines value: " + s);
        }
    }

    // ---- tree scanning ----

    static List<Artifact> scan(Path root) throws IOException {
        List<Artifact> result = new ArrayList<>();
        if (!Files.isDirectory(root)) return result;
        try (Stream<Path> s = Files.walk(root)) {
            for (Path p : (Iterable<Path>) s::iterator) {
                if (!Files.isRegularFile(p)) continue;
                Path rel = root.relativize(p);
                int n = rel.getNameCount();
                if (n < 4) continue;
                String file = rel.getName(n - 1).toString();
                String version = rel.getName(n - 2).toString();
                String artifact = rel.getName(n - 3).toString();
                if (!file.equals(artifact + "-" + version + "-sources.jar")) continue;
                StringBuilder group = new StringBuilder();
                for (int i = 0; i < n - 3; i++) {
                    if (i > 0) group.append('.');
                    group.append(rel.getName(i).toString());
                }
                result.add(new Artifact(group.toString(), artifact, version, p));
            }
        }
        return result;
    }

    static List<String> classesIn(Artifact a) throws IOException {
        List<String> out = new ArrayList<>();
        try (ZipFile z = new ZipFile(a.jar().toFile(), StandardCharsets.UTF_8)) {
            var en = z.entries();
            while (en.hasMoreElements()) {
                ZipEntry e = en.nextElement();
                String name = e.getName();
                if (e.isDirectory() || !name.endsWith(".java")) continue;
                if (name.startsWith("/")) name = name.substring(1);
                out.add(name.substring(0, name.length() - ".java".length()).replace('/', '.'));
            }
        }
        return out;
    }

    static String readSource(Artifact a, String fqcn) throws IOException {
        String entry = fqcn.replace('.', '/') + ".java";
        try (ZipFile z = new ZipFile(a.jar().toFile(), StandardCharsets.UTF_8)) {
            ZipEntry e = z.getEntry(entry);
            if (e == null) e = z.getEntry("/" + entry);
            if (e == null) throw new IOException("missing entry " + entry + " in " + a.jar());
            try (InputStream in = z.getInputStream(e)) {
                return new String(in.readAllBytes(), StandardCharsets.UTF_8);
            }
        }
    }

    static String simpleName(String fqcn) {
        int dot = fqcn.lastIndexOf('.');
        return dot < 0 ? fqcn : fqcn.substring(dot + 1);
    }

    // ---- suggestions ----

    static String withSuggestions(String msg, String prefix, Iterable<String> known) {
        TreeSet<String> matches = new TreeSet<>();
        if (!prefix.isEmpty()) {
            for (String k : known) if (k.startsWith(prefix)) matches.add(k);
        }
        if (matches.isEmpty()) return msg;
        List<String> top = new ArrayList<>(matches).subList(0, Math.min(3, matches.size()));
        return msg + " (did you mean: " + String.join(", ", top) + "?)";
    }

    static int notFound(String msg) {
        ERR.print(msg + "\n");
        return 1;
    }

    // ---- find ----

    static Pattern globToRegex(String glob) {
        StringBuilder re = new StringBuilder();
        StringBuilder lit = new StringBuilder();
        for (int i = 0; i < glob.length(); i++) {
            char c = glob.charAt(i);
            if (c == '*') {
                if (lit.length() > 0) { re.append(Pattern.quote(lit.toString())); lit.setLength(0); }
                if (i + 1 < glob.length() && glob.charAt(i + 1) == '*') {
                    re.append(".*");
                    while (i + 1 < glob.length() && glob.charAt(i + 1) == '*') i++;
                } else {
                    re.append("[^.]*");
                }
            } else {
                lit.append(c);
            }
        }
        if (lit.length() > 0) re.append(Pattern.quote(lit.toString()));
        return Pattern.compile(re.toString(), Pattern.DOTALL);
    }

    static int find(List<Artifact> artifacts, String query) throws IOException {
        Pattern pat = query.contains("*") ? globToRegex(query) : null;
        TreeSet<String> simpleNames = new TreeSet<>();
        List<String[]> matches = new ArrayList<>();
        for (Artifact a : artifacts) {
            for (String fqcn : classesIn(a)) {
                simpleNames.add(simpleName(fqcn));
                boolean ok = pat == null ? fqcn.equals(query) : pat.matcher(fqcn).matches();
                if (ok) matches.add(new String[] {fqcn, a.gav()});
            }
        }
        if (matches.isEmpty()) {
            String last = simpleName(query);
            int star = last.indexOf('*');
            String prefix = star < 0 ? last : last.substring(0, star);
            return notFound(withSuggestions("error: no matches for " + query, prefix, simpleNames));
        }
        matches.sort(Comparator.<String[], String>comparing(m -> m[0]).thenComparing(m -> m[1]));
        StringBuilder sb = new StringBuilder();
        String prev = null;
        for (String[] m : matches) {
            String line = m[0] + " " + m[1];
            if (line.equals(prev)) continue;
            sb.append(line).append('\n');
            prev = line;
        }
        OUT.print(sb);
        return 0;
    }

    // ---- cat ----

    static int cat(List<Artifact> artifacts, String fqcn, int[] range) throws IOException {
        TreeSet<String> simpleNames = new TreeSet<>();
        Map<String, Artifact> bestByGa = new TreeMap<>();
        for (Artifact a : artifacts) {
            for (String c : classesIn(a)) {
                simpleNames.add(simpleName(c));
                if (c.equals(fqcn)) {
                    Artifact cur = bestByGa.get(a.ga());
                    if (cur == null || compareVersions(a.version(), cur.version()) > 0) bestByGa.put(a.ga(), a);
                }
            }
        }
        if (bestByGa.isEmpty()) {
            return notFound(withSuggestions("error: no source for " + fqcn, simpleName(fqcn), simpleNames));
        }
        if (bestByGa.size() > 1) {
            ERR.print("error: " + fqcn + " is ambiguous across: " + String.join(", ", bestByGa.keySet()) + "\n");
            return 2;
        }
        String src = readSource(bestByGa.values().iterator().next(), fqcn);
        src = src.replace("\r\n", "\n").replace('\r', '\n');
        if (src.endsWith("\n")) src = src.substring(0, src.length() - 1);
        String[] lines = src.isEmpty() ? new String[0] : src.split("\n", -1);
        int from = 1, to = lines.length;
        if (range != null) {
            from = Math.max(1, range[0]);
            to = Math.min(lines.length, range[1]);
        }
        StringBuilder sb = new StringBuilder();
        for (int i = from; i <= to; i++) sb.append(stripTrailing(lines[i - 1])).append('\n');
        OUT.print(sb);
        return 0;
    }

    static String stripTrailing(String s) {
        int end = s.length();
        while (end > 0 && Character.isWhitespace(s.charAt(end - 1))) end--;
        return s.substring(0, end);
    }

    // ---- ls ----

    static int ls(List<Artifact> artifacts, String coord) throws IOException, UsageException {
        String[] parts = coord.split(":", -1);
        if (parts.length < 2 || parts.length > 3) {
            throw new UsageException("error: invalid coordinate: " + coord + " (expected group:artifact[:version])");
        }
        for (String p : parts) {
            if (p.isEmpty()) throw new UsageException("error: invalid coordinate: " + coord + " (expected group:artifact[:version])");
        }
        String ga = parts[0] + ":" + parts[1];
        TreeSet<String> knownGas = new TreeSet<>();
        List<Artifact> matching = new ArrayList<>();
        for (Artifact a : artifacts) {
            knownGas.add(a.ga());
            if (a.ga().equals(ga)) matching.add(a);
        }
        if (matching.isEmpty()) {
            return notFound(withSuggestions("error: no artifact " + coord, coord, knownGas));
        }
        StringBuilder sb = new StringBuilder();
        if (parts.length == 2) {
            List<String> versions = new ArrayList<>(new TreeSet<>(matching.stream().map(Artifact::version).toList()));
            versions.sort(Jarsrc::compareVersions);
            for (String v : versions) sb.append(v).append('\n');
        } else {
            Artifact hit = null;
            for (Artifact a : matching) if (a.version().equals(parts[2])) hit = a;
            if (hit == null) {
                return notFound(withSuggestions("error: no artifact " + coord, coord, knownGas));
            }
            for (String c : new TreeSet<>(classesIn(hit))) sb.append(c).append('\n');
        }
        OUT.print(sb);
        return 0;
    }

    // ---- version comparison ----

    static final Pattern DIGITS = Pattern.compile("[0-9]+");

    static int compareVersions(String a, String b) {
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
        if (DIGITS.matcher(a).matches() && DIGITS.matcher(b).matches()) {
            String sa = a.replaceFirst("^0+(?=.)", ""), sb = b.replaceFirst("^0+(?=.)", "");
            if (sa.length() != sb.length()) return Integer.compare(sa.length(), sb.length());
            return sa.compareTo(sb);
        }
        byte[] ba = a.getBytes(StandardCharsets.UTF_8), bb = b.getBytes(StandardCharsets.UTF_8);
        return java.util.Arrays.compareUnsigned(ba, bb);
    }
}
