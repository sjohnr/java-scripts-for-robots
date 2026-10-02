import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;
import java.util.TreeSet;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;

public final class Jarsrc {
    private static final String HELP = String.join("\n",
        "Usage:",
        "  jarsrc find <fqcn-or-glob> [--root DIR]",
        "  jarsrc cat <fqcn> [--lines A:B] [--root DIR]",
        "  jarsrc ls <group:artifact[:version]> [--root DIR]",
        "  jarsrc --help",
        "",
        "Subcommands:",
        "  find   list sources jars containing classes matching an FQCN or glob",
        "         (* matches within a segment, ** matches across dots)",
        "  cat    print the source of an FQCN from the highest version",
        "  ls     list versions of group:artifact, or classes of group:artifact:version",
        "",
        "Flags:",
        "  --root DIR   root of the Gradle-cache-like tree (default: .)",
        "  --lines A:B  with cat, print only lines A through B (1-indexed, inclusive)",
        "  --help       show this message",
        "");

    /** Thrown to terminate with an exit code and optional stderr message. */
    private static final class Exit extends RuntimeException {
        final int code;
        Exit(int code, String message) {
            super(message, null, false, false);
            this.code = code;
        }
    }

    private record Artifact(String group, String artifact, String version, Path jar) {
        String ga() { return group + ":" + artifact; }
        String gav() { return group + ":" + artifact + ":" + version; }
    }

    public static void main(String[] args) {
        PrintStream out = new PrintStream(new java.io.FileOutputStream(java.io.FileDescriptor.out), false, StandardCharsets.UTF_8);
        int code;
        try {
            code = run(args, out);
        } catch (Exit e) {
            if (e.getMessage() != null) {
                System.err.write((e.getMessage() + "\n").getBytes(StandardCharsets.UTF_8), 0,
                    (e.getMessage() + "\n").getBytes(StandardCharsets.UTF_8).length);
                System.err.flush();
            }
            code = e.code;
        }
        out.flush();
        System.exit(code);
    }

    private static int run(String[] args, PrintStream out) {
        for (String a : args) {
            if (a.equals("--help")) {
                out.write(HELP.getBytes(StandardCharsets.UTF_8), 0, HELP.getBytes(StandardCharsets.UTF_8).length);
                return 0;
            }
        }
        String root = ".";
        String lines = null;
        List<String> pos = new ArrayList<>();
        for (int i = 0; i < args.length; i++) {
            String a = args[i];
            if (a.startsWith("--") && a.length() > 2) {
                String name = a, value = null;
                int eq = a.indexOf('=');
                if (eq > 0) { name = a.substring(0, eq); value = a.substring(eq + 1); }
                if (!name.equals("--root") && !name.equals("--lines")) {
                    throw new Exit(2, "unknown flag: " + a);
                }
                if (value == null) {
                    if (i + 1 >= args.length) throw new Exit(2, "missing value for " + name);
                    value = args[++i];
                }
                if (name.equals("--root")) root = value; else lines = value;
            } else if (a.startsWith("-") && a.length() > 1) {
                throw new Exit(2, "unknown flag: " + a);
            } else {
                pos.add(a);
            }
        }
        if (pos.isEmpty()) throw new Exit(2, "usage: jarsrc <find|cat|ls> ... (see --help)");
        String cmd = pos.get(0);
        if (!cmd.equals("find") && !cmd.equals("cat") && !cmd.equals("ls")) {
            throw new Exit(2, "unknown command: " + cmd);
        }
        if (pos.size() < 2) throw new Exit(2, "missing argument for " + cmd);
        if (pos.size() > 2) throw new Exit(2, "unexpected argument: " + pos.get(2));
        if (lines != null && !cmd.equals("cat")) throw new Exit(2, "--lines is only valid for cat");
        String arg = pos.get(1);
        if (arg.isEmpty()) throw new Exit(2, "empty argument for " + cmd);

        long from = 0, to = 0;
        if (lines != null) {
            Matcher m = Pattern.compile("(\\d+):(\\d+)").matcher(lines);
            if (!m.matches()) throw new Exit(2, "invalid --lines value: " + lines);
            from = parseClamped(m.group(1));
            to = parseClamped(m.group(2));
        }

        List<Artifact> all = scan(Paths.get(root));
        switch (cmd) {
            case "find": return find(all, arg, out);
            case "cat": return cat(all, arg, lines != null, from, to, out);
            default: return ls(all, arg, out);
        }
    }

    private static long parseClamped(String s) {
        String t = s.replaceFirst("^0+(?=.)", "");
        return t.length() > 15 ? Long.MAX_VALUE / 2 : Long.parseLong(t);
    }

    // ---------- scanning ----------

    private static List<Artifact> scan(Path root) {
        List<Artifact> res = new ArrayList<>();
        if (!Files.isDirectory(root)) return res;
        try (Stream<Path> s = Files.walk(root)) {
            s.filter(p -> p.getFileName() != null && p.getFileName().toString().endsWith("-sources.jar"))
             .filter(Files::isRegularFile)
             .forEach(p -> {
                Path rel = root.relativize(p);
                int n = rel.getNameCount();
                if (n < 4) return;
                String version = rel.getName(n - 2).toString();
                String artifact = rel.getName(n - 3).toString();
                if (!rel.getName(n - 1).toString().equals(artifact + "-" + version + "-sources.jar")) return;
                StringBuilder g = new StringBuilder();
                for (int i = 0; i < n - 3; i++) {
                    if (i > 0) g.append('.');
                    g.append(rel.getName(i).toString());
                }
                res.add(new Artifact(g.toString(), artifact, version, p));
             });
        } catch (IOException | RuntimeException e) {
            // unreadable tree: use whatever was found
        }
        return res;
    }

    /** FQCNs of all .java entries in a jar; empty if the jar is unreadable. */
    private static List<String> classes(Artifact a) {
        List<String> r = new ArrayList<>();
        try (ZipFile z = new ZipFile(a.jar().toFile())) {
            var en = z.entries();
            while (en.hasMoreElements()) {
                ZipEntry e = en.nextElement();
                String n = e.getName();
                if (e.isDirectory() || !n.endsWith(".java")) continue;
                n = n.substring(0, n.length() - 5);
                while (n.startsWith("/")) n = n.substring(1);
                r.add(n.replace('/', '.'));
            }
        } catch (IOException | RuntimeException e) {
            // skip bad jars silently
        }
        return r;
    }

    // ---------- commands ----------

    private static int find(List<Artifact> all, String pattern, PrintStream out) {
        Pattern re = globToRegex(pattern);
        List<String[]> hits = new ArrayList<>();
        TreeSet<String> simple = new TreeSet<>();
        for (Artifact a : all) {
            for (String c : classes(a)) {
                simple.add(c.substring(c.lastIndexOf('.') + 1));
                if (re.matcher(c).matches()) hits.add(new String[] {c, a.gav()});
            }
        }
        if (hits.isEmpty()) {
            throw new Exit(1, "error: no matches for " + pattern
                + suggest(simple, pattern.substring(pattern.lastIndexOf('.') + 1)));
        }
        hits.sort(Comparator.<String[], String>comparing(h -> h[0]).thenComparing(h -> h[1]));
        StringBuilder sb = new StringBuilder();
        for (String[] h : hits) sb.append(h[0]).append(' ').append(h[1]).append('\n');
        print(out, sb.toString());
        return 0;
    }

    private static Pattern globToRegex(String g) {
        StringBuilder sb = new StringBuilder();
        int i = 0;
        while (i < g.length()) {
            char c = g.charAt(i);
            if (c == '*') {
                if (i + 1 < g.length() && g.charAt(i + 1) == '*') { sb.append(".*"); i += 2; }
                else { sb.append("[^.]*"); i++; }
            } else {
                int j = i;
                while (j < g.length() && g.charAt(j) != '*') j++;
                sb.append(Pattern.quote(g.substring(i, j)));
                i = j;
            }
        }
        return Pattern.compile(sb.toString(), Pattern.DOTALL);
    }

    private static int cat(List<Artifact> all, String fqcn, boolean ranged, long from, long to, PrintStream out) {
        String entry = fqcn.replace('.', '/') + ".java";
        List<Artifact> have = new ArrayList<>();
        TreeSet<String> simple = new TreeSet<>();
        for (Artifact a : all) {
            boolean found = false;
            for (String c : classes(a)) {
                simple.add(c.substring(c.lastIndexOf('.') + 1));
                if (c.equals(fqcn)) found = true;
            }
            if (found) have.add(a);
        }
        if (have.isEmpty()) {
            throw new Exit(1, "error: no source for " + fqcn
                + suggest(simple, fqcn.substring(fqcn.lastIndexOf('.') + 1)));
        }
        TreeSet<String> gas = new TreeSet<>();
        for (Artifact a : have) gas.add(a.ga());
        if (gas.size() > 1) {
            throw new Exit(2, "error: " + fqcn + " is ambiguous across: " + String.join(", ", gas));
        }
        Artifact best = have.get(0);
        for (Artifact a : have) if (compareVersions(a.version(), best.version()) > 0) best = a;

        byte[] data;
        try (ZipFile z = new ZipFile(best.jar().toFile())) {
            ZipEntry e = z.getEntry(entry);
            if (e == null) e = z.getEntry("/" + entry);
            try (InputStream in = z.getInputStream(e)) {
                ByteArrayOutputStream bo = new ByteArrayOutputStream();
                in.transferTo(bo);
                data = bo.toByteArray();
            }
        } catch (IOException | RuntimeException e) {
            throw new Exit(1, "error: no source for " + fqcn);
        }
        if (!ranged) {
            out.write(data, 0, data.length);
            return 0;
        }
        String text = new String(data, StandardCharsets.UTF_8);
        List<String> ls = new ArrayList<>(List.of(text.split("\n", -1)));
        if (!ls.isEmpty() && ls.get(ls.size() - 1).isEmpty()) ls.remove(ls.size() - 1);
        long a = Math.max(from, 1), b = Math.min(to, ls.size());
        StringBuilder sb = new StringBuilder();
        for (long i = a; i <= b; i++) sb.append(ls.get((int) (i - 1))).append('\n');
        print(out, sb.toString());
        return 0;
    }

    private static int ls(List<Artifact> all, String arg, PrintStream out) {
        String[] parts = arg.split(":", -1);
        if (parts.length < 2 || parts.length > 3) throw new Exit(2, "invalid coordinate: " + arg);
        for (String p : parts) if (p.isEmpty()) throw new Exit(2, "invalid coordinate: " + arg);
        String ga = parts[0] + ":" + parts[1];
        List<Artifact> matches = new ArrayList<>();
        TreeSet<String> known = new TreeSet<>();
        for (Artifact a : all) {
            known.add(a.ga());
            if (a.ga().equals(ga)) matches.add(a);
        }
        if (matches.isEmpty()) {
            throw new Exit(1, "error: no artifact " + ga + suggest(known, ga));
        }
        StringBuilder sb = new StringBuilder();
        if (parts.length == 2) {
            matches.sort((x, y) -> compareVersions(x.version(), y.version()));
            for (Artifact a : matches) sb.append(a.version()).append('\n');
        } else {
            Artifact hit = null;
            for (Artifact a : matches) if (a.version().equals(parts[2])) { hit = a; break; }
            if (hit == null) {
                throw new Exit(1, "error: no artifact " + arg + suggest(known, arg));
            }
            for (String c : new TreeSet<>(classes(hit))) sb.append(c).append('\n');
        }
        print(out, sb.toString());
        return 0;
    }

    // ---------- helpers ----------

    private static void print(PrintStream out, String s) {
        byte[] b = s.getBytes(StandardCharsets.UTF_8);
        out.write(b, 0, b.length);
    }

    private static String suggest(Iterable<String> known, String prefix) {
        List<String> m = new ArrayList<>();
        for (String k : known) if (k.startsWith(prefix)) m.add(k);
        if (m.isEmpty()) return "";
        Collections.sort(m);
        if (m.size() > 3) m = m.subList(0, 3);
        return " (did you mean: " + String.join(", ", m) + "?)";
    }

    static int compareVersions(String a, String b) {
        String[] x = a.split("[.\\-]", -1), y = b.split("[.\\-]", -1);
        int n = Math.min(x.length, y.length);
        for (int i = 0; i < n; i++) {
            int c = compareSegment(x[i], y[i]);
            if (c != 0) return c;
        }
        return Integer.compare(x.length, y.length);
    }

    private static boolean isNum(String s) {
        if (s.isEmpty()) return false;
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            if (c < '0' || c > '9') return false;
        }
        return true;
    }

    private static int compareSegment(String a, String b) {
        if (isNum(a) && isNum(b)) {
            String p = a.replaceFirst("^0+(?=.)", ""), q = b.replaceFirst("^0+(?=.)", "");
            if (p.length() != q.length()) return Integer.compare(p.length(), q.length());
            return Integer.signum(p.compareTo(q));
        }
        byte[] p = a.getBytes(StandardCharsets.UTF_8), q = b.getBytes(StandardCharsets.UTF_8);
        int n = Math.min(p.length, q.length);
        for (int i = 0; i < n; i++) {
            int c = Integer.compare(p[i] & 0xff, q[i] & 0xff);
            if (c != 0) return c;
        }
        return Integer.compare(p.length, q.length);
    }
}
