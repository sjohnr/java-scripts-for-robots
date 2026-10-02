import java.io.*;
import java.math.BigInteger;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.*;
import java.util.regex.Pattern;
import java.util.stream.Stream;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;

public class Jarsrc {
    static final String HELP = String.join("\n",
        "Usage:",
        "  jarsrc find <fqcn-or-glob> [--root DIR]",
        "  jarsrc cat <fqcn> [--lines A:B] [--root DIR]",
        "  jarsrc ls <group:artifact[:version]> [--root DIR]",
        "  jarsrc --help",
        "",
        "Subcommands:",
        "  find   list classes matching an FQCN or glob (* excludes '.', ** includes '.')",
        "  cat    print source of an FQCN from the highest version",
        "  ls     list versions of group:artifact, or classes of group:artifact:version",
        "",
        "Flags:",
        "  --root DIR    root of the Gradle-cache-like tree (default: .)",
        "  --lines A:B   (cat) print only lines A through B, 1-indexed, inclusive",
        "  --help        show this message",
        "");

    static class UsageException extends RuntimeException {
        UsageException(String m) { super(m); }
    }

    record Jar(String group, String artifact, String version, Path path) {
        String ga() { return group + ":" + artifact; }
        String gav() { return ga() + ":" + version; }
    }

    static final StringBuilder out = new StringBuilder();

    public static void main(String[] args) {
        int code;
        try {
            code = run(args);
        } catch (UsageException e) {
            flush();
            code = 2;
            writeErr(e.getMessage());
        }
        flush();
        System.exit(code);
    }

    static void writeErr(String msg) {
        byte[] b = (msg + "\n").getBytes(StandardCharsets.UTF_8);
        System.err.write(b, 0, b.length);
        System.err.flush();
    }

    static void flush() {
        byte[] b = out.toString().getBytes(StandardCharsets.UTF_8);
        System.out.write(b, 0, b.length);
        System.out.flush();
        out.setLength(0);
    }

    static int fail(int code, String msg) {
        flush();
        writeErr(msg);
        return code;
    }

    static int run(String[] args) {
        String root = ".";
        String lines = null;
        List<String> pos = new ArrayList<>();
        for (int i = 0; i < args.length; i++) {
            String a = args[i];
            if (a.equals("--help")) {
                out.append(HELP);
                return 0;
            } else if (a.equals("--root") || a.startsWith("--root=")) {
                if (a.equals("--root")) {
                    if (i + 1 >= args.length) throw new UsageException("missing value for --root");
                    root = args[++i];
                } else root = a.substring(7);
            } else if (a.equals("--lines") || a.startsWith("--lines=")) {
                if (a.equals("--lines")) {
                    if (i + 1 >= args.length) throw new UsageException("missing value for --lines");
                    lines = args[++i];
                } else lines = a.substring(8);
            } else if (a.startsWith("-") && a.length() > 1) {
                throw new UsageException("unknown flag: " + a);
            } else pos.add(a);
        }
        if (pos.isEmpty()) throw new UsageException("missing subcommand (try --help)");
        String cmd = pos.get(0);
        if (!cmd.equals("find") && !cmd.equals("cat") && !cmd.equals("ls"))
            throw new UsageException("unknown subcommand: " + cmd);
        if (pos.size() < 2) throw new UsageException("missing argument for " + cmd);
        if (pos.size() > 2) throw new UsageException("unexpected argument: " + pos.get(2));
        if (lines != null && !cmd.equals("cat")) throw new UsageException("--lines is only valid with cat");
        String arg = pos.get(1);
        long from = 0, to = 0;
        if (lines != null) {
            java.util.regex.Matcher m = Pattern.compile("(-?\\d{1,18}):(-?\\d{1,18})").matcher(lines);
            if (!m.matches()) throw new UsageException("invalid --lines value: " + lines);
            from = Long.parseLong(m.group(1));
            to = Long.parseLong(m.group(2));
        }
        List<Jar> jars = scan(Paths.get(root));
        switch (cmd) {
            case "find": return find(jars, arg);
            case "cat": return cat(jars, arg, lines != null, from, to);
            default: return ls(jars, arg);
        }
    }

    static List<Jar> scan(Path root) {
        List<Jar> res = new ArrayList<>();
        if (!Files.isDirectory(root)) return res;
        try (Stream<Path> s = Files.walk(root)) {
            s.filter(p -> p.getFileName().toString().endsWith("-sources.jar") && Files.isRegularFile(p))
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
                 res.add(new Jar(g.toString(), artifact, version, p));
             });
        } catch (IOException | UncheckedIOException e) {
            // unreadable parts of the tree are skipped
        }
        return res;
    }

    static List<String> classes(Jar j) {
        List<String> r = new ArrayList<>();
        try (ZipFile z = new ZipFile(j.path().toFile())) {
            for (Enumeration<? extends ZipEntry> e = z.entries(); e.hasMoreElements();) {
                ZipEntry en = e.nextElement();
                String n = en.getName();
                if (en.isDirectory() || !n.endsWith(".java")) continue;
                r.add(n.substring(0, n.length() - 5).replace('/', '.'));
            }
        } catch (IOException e) {
            // corrupt jar: ignored
        }
        return r;
    }

    static Pattern glob(String g) {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < g.length();) {
            if (g.startsWith("**", i)) {
                sb.append(".*");
                i += 2;
                while (i < g.length() && g.charAt(i) == '*') i++;
            } else if (g.charAt(i) == '*') {
                sb.append("[^.]*");
                i++;
            } else {
                int j = i;
                while (j < g.length() && g.charAt(j) != '*') j++;
                sb.append(Pattern.quote(g.substring(i, j)));
                i = j;
            }
        }
        return Pattern.compile(sb.toString(), Pattern.DOTALL);
    }

    static String simple(String fqcn) {
        return fqcn.substring(fqcn.lastIndexOf('.') + 1);
    }

    static String suggest(String what, String query, Collection<String> known) {
        TreeSet<String> m = new TreeSet<>();
        for (String k : known) if (k.startsWith(query)) m.add(k);
        StringBuilder sb = new StringBuilder("error: ").append(what);
        if (!m.isEmpty()) {
            List<String> l = new ArrayList<>(m);
            sb.append(" (did you mean: ").append(String.join(", ", l.subList(0, Math.min(3, l.size())))).append("?)");
        }
        return sb.toString();
    }

    static Set<String> allSimpleNames(List<Jar> jars) {
        Set<String> s = new HashSet<>();
        for (Jar j : jars) for (String c : classes(j)) s.add(simple(c));
        return s;
    }

    static int find(List<Jar> jars, String arg) {
        boolean isGlob = arg.indexOf('*') >= 0;
        Pattern p = isGlob ? glob(arg) : null;
        List<String[]> res = new ArrayList<>();
        for (Jar j : jars) {
            for (String c : classes(j)) {
                if (isGlob ? p.matcher(c).matches() : c.equals(arg))
                    res.add(new String[]{c, j.gav()});
            }
        }
        if (res.isEmpty()) {
            String q = simple(arg);
            int star = q.indexOf('*');
            if (star >= 0) q = q.substring(0, star);
            return fail(1, suggest("no matches for " + arg, q, allSimpleNames(jars)));
        }
        res.sort((a, b) -> {
            int c = a[0].compareTo(b[0]);
            return c != 0 ? c : a[1].compareTo(b[1]);
        });
        for (String[] r : res) out.append(r[0]).append(' ').append(r[1]).append('\n');
        return 0;
    }

    static int cat(List<Jar> jars, String fqcn, boolean ranged, long from, long to) {
        List<Jar> have = new ArrayList<>();
        for (Jar j : jars) if (classes(j).contains(fqcn)) have.add(j);
        if (have.isEmpty())
            return fail(1, suggest("no source for " + fqcn, simple(fqcn), allSimpleNames(jars)));
        TreeSet<String> gas = new TreeSet<>();
        for (Jar j : have) gas.add(j.ga());
        if (gas.size() > 1)
            return fail(2, "error: " + fqcn + " is ambiguous across: " + String.join(", ", gas));
        Jar best = have.get(0);
        for (Jar j : have) if (compareVersions(j.version(), best.version()) > 0) best = j;
        String text;
        try (ZipFile z = new ZipFile(best.path().toFile())) {
            ZipEntry e = z.getEntry(fqcn.replace('.', '/') + ".java");
            try (InputStream in = z.getInputStream(e)) {
                text = new String(in.readAllBytes(), StandardCharsets.UTF_8);
            }
        } catch (IOException e) {
            return fail(1, "error: no source for " + fqcn);
        }
        List<String> ls = new ArrayList<>();
        int start = 0;
        while (start < text.length()) {
            int nl = text.indexOf('\n', start);
            String line = nl < 0 ? text.substring(start) : text.substring(start, nl);
            ls.add(stripEnd(line));
            if (nl < 0) break;
            start = nl + 1;
        }
        long a = 1, b = ls.size();
        if (ranged) {
            a = Math.max(from, 1);
            b = Math.min(to, ls.size());
        }
        for (long i = a; i <= b; i++) out.append(ls.get((int) (i - 1))).append('\n');
        return 0;
    }

    static String stripEnd(String s) {
        int e = s.length();
        while (e > 0 && Character.isWhitespace(s.charAt(e - 1))) e--;
        return s.substring(0, e);
    }

    static int ls(List<Jar> jars, String arg) {
        String[] parts = arg.split(":", -1);
        if (parts.length < 2 || parts.length > 3 || parts[0].isEmpty() || parts[1].isEmpty()
                || (parts.length == 3 && parts[2].isEmpty()))
            throw new UsageException("invalid artifact spec: " + arg);
        String ga = parts[0] + ":" + parts[1];
        List<Jar> forGa = new ArrayList<>();
        for (Jar j : jars) if (j.ga().equals(ga)) forGa.add(j);
        Set<String> known = new HashSet<>();
        for (Jar j : jars) known.add(j.ga());
        if (forGa.isEmpty()) return fail(1, suggest("no artifact " + arg, arg, known));
        if (parts.length == 2) {
            Set<String> seen = new HashSet<>();
            for (Jar j : forGa) seen.add(j.version());
            List<String> sorted = new ArrayList<>(seen);
            sorted.sort(Jarsrc::compareVersions);
            for (String v : sorted) out.append(v).append('\n');
            return 0;
        }
        TreeSet<String> cs = new TreeSet<>();
        boolean found = false;
        for (Jar j : forGa) {
            if (j.version().equals(parts[2])) {
                found = true;
                cs.addAll(classes(j));
            }
        }
        if (!found) return fail(1, suggest("no artifact " + arg, arg, known));
        for (String c : cs) out.append(c).append('\n');
        return 0;
    }

    static boolean isNum(String s) {
        if (s.isEmpty()) return false;
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            if (c < '0' || c > '9') return false;
        }
        return true;
    }

    static int compareVersions(String a, String b) {
        String[] x = a.split("[.-]", -1), y = b.split("[.-]", -1);
        int n = Math.min(x.length, y.length);
        for (int i = 0; i < n; i++) {
            int c;
            if (isNum(x[i]) && isNum(y[i])) c = new BigInteger(x[i]).compareTo(new BigInteger(y[i]));
            else c = Arrays.compareUnsigned(x[i].getBytes(StandardCharsets.UTF_8), y[i].getBytes(StandardCharsets.UTF_8));
            if (c != 0) return c < 0 ? -1 : 1;
        }
        return Integer.compare(x.length, y.length);
    }
}
