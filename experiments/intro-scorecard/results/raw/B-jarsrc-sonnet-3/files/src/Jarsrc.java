import java.io.*;
import java.math.BigInteger;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.*;
import java.util.regex.Pattern;
import java.util.stream.*;
import java.util.zip.*;

public class Jarsrc {
    static final PrintStream OUT = new PrintStream(new FileOutputStream(FileDescriptor.out), false, StandardCharsets.UTF_8);
    static final PrintStream ERR = new PrintStream(new FileOutputStream(FileDescriptor.err), false, StandardCharsets.UTF_8);

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
        "  --lines A:B   (cat) print only lines A through B, 1-indexed inclusive",
        "  --help        show this message",
        "");

    record Jar(String group, String artifact, String version, Path path) {
        String ga() { return group + ":" + artifact; }
        String gav() { return ga() + ":" + version; }
    }

    static class Usage extends RuntimeException {
        Usage(String m) { super(m); }
    }

    public static void main(String[] args) {
        int code;
        try {
            code = run(args);
        } catch (Usage u) {
            ERR.print(u.getMessage() + "\n");
            code = 2;
        }
        OUT.flush();
        ERR.flush();
        System.exit(code);
    }

    static int run(String[] args) {
        for (String a : args) if (a.equals("--help")) { OUT.print(HELP); return 0; }
        String root = ".";
        String lines = null;
        List<String> pos = new ArrayList<>();
        for (int i = 0; i < args.length; i++) {
            String a = args[i];
            if (a.equals("--root") || a.equals("--lines")) {
                if (i + 1 >= args.length) throw new Usage("missing value for " + a);
                if (a.equals("--root")) root = args[++i]; else lines = args[++i];
            } else if (a.startsWith("--root=")) {
                root = a.substring(7);
            } else if (a.startsWith("--lines=")) {
                lines = a.substring(8);
            } else if (a.startsWith("-") && a.length() > 1) {
                throw new Usage("unknown flag: " + a);
            } else pos.add(a);
        }
        if (pos.isEmpty()) throw new Usage("usage: jarsrc <find|cat|ls> ... (see --help)");
        String cmd = pos.get(0);
        if (!cmd.equals("find") && !cmd.equals("cat") && !cmd.equals("ls"))
            throw new Usage("unknown command: " + cmd);
        if (pos.size() < 2) throw new Usage("missing argument for " + cmd);
        if (pos.size() > 2) throw new Usage("unexpected argument: " + pos.get(2));
        String arg = pos.get(1);
        long[] range = null;
        if (lines != null && cmd.equals("cat")) range = parseLines(lines);
        List<Jar> jars = discover(Paths.get(root));
        switch (cmd) {
            case "find": return find(jars, arg);
            case "cat": return cat(jars, arg, range);
            default: return ls(jars, arg);
        }
    }

    static long[] parseLines(String s) {
        int c = s.indexOf(':');
        if (c < 0) throw new Usage("invalid --lines: " + s);
        String a = s.substring(0, c), b = s.substring(c + 1);
        if (!a.matches("[0-9]{1,18}") || !b.matches("[0-9]{1,18}")) throw new Usage("invalid --lines: " + s);
        return new long[] {Long.parseLong(a), Long.parseLong(b)};
    }

    static List<Jar> discover(Path root) {
        List<Jar> res = new ArrayList<>();
        if (!Files.isDirectory(root)) return res;
        try (Stream<Path> st = Files.walk(root)) {
            for (Path p : (Iterable<Path>) st::iterator) {
                String name = p.getFileName() == null ? "" : p.getFileName().toString();
                if (!name.endsWith("-sources.jar") || !Files.isRegularFile(p)) continue;
                Path rel = root.relativize(p);
                int n = rel.getNameCount();
                if (n < 4) continue;
                String version = rel.getName(n - 2).toString();
                String artifact = rel.getName(n - 3).toString();
                if (!name.equals(artifact + "-" + version + "-sources.jar")) continue;
                StringBuilder g = new StringBuilder();
                for (int i = 0; i < n - 3; i++) {
                    if (i > 0) g.append('.');
                    g.append(rel.getName(i));
                }
                res.add(new Jar(g.toString(), artifact, version, p));
            }
        } catch (IOException | UncheckedIOException e) {
            // ignore unreadable parts of the tree
        }
        return res;
    }

    static List<String> classes(Jar j) {
        List<String> r = new ArrayList<>();
        try (ZipFile z = new ZipFile(j.path().toFile())) {
            for (var en = z.entries(); en.hasMoreElements();) {
                ZipEntry e = en.nextElement();
                String n = e.getName();
                if (e.isDirectory() || !n.endsWith(".java")) continue;
                r.add(n.substring(0, n.length() - 5).replace('/', '.'));
            }
        } catch (IOException e) {
            // skip corrupt jar
        }
        return r;
    }

    static Pattern glob(String g) {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < g.length();) {
            if (g.startsWith("**", i)) { sb.append(".*"); i += 2; }
            else if (g.charAt(i) == '*') { sb.append("[^.]*"); i++; }
            else {
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

    static String suggest(Collection<String> known, String prefix) {
        List<String> m = known.stream().distinct().filter(k -> k.startsWith(prefix)).sorted().limit(3).collect(Collectors.toList());
        return m.isEmpty() ? "" : " (did you mean: " + String.join(", ", m) + "?)";
    }

    static int find(List<Jar> jars, String q) {
        boolean wild = q.contains("*");
        Pattern p = wild ? glob(q) : null;
        TreeSet<String> out = new TreeSet<>(Comparator.comparing((String s) -> s.substring(0, s.indexOf(' '))).thenComparing(s -> s.substring(s.indexOf(' ') + 1)));
        Set<String> simples = new HashSet<>();
        for (Jar j : jars) {
            for (String c : classes(j)) {
                simples.add(simple(c));
                if (wild ? p.matcher(c).matches() : c.equals(q)) out.add(c + " " + j.gav());
            }
        }
        if (out.isEmpty()) {
            ERR.print("error: no matches for " + q + suggest(simples, simple(q)) + "\n");
            return 1;
        }
        for (String s : out) OUT.print(s + "\n");
        return 0;
    }

    static int cmpSeg(String a, String b) {
        if (isNum(a) && isNum(b)) return new BigInteger(a).compareTo(new BigInteger(b));
        return Arrays.compareUnsigned(a.getBytes(StandardCharsets.UTF_8), b.getBytes(StandardCharsets.UTF_8));
    }

    static boolean isNum(String s) {
        if (s.isEmpty()) return false;
        for (int i = 0; i < s.length(); i++) if (s.charAt(i) < '0' || s.charAt(i) > '9') return false;
        return true;
    }

    static int cmpVer(String a, String b) {
        String[] x = a.split("[.-]", -1), y = b.split("[.-]", -1);
        for (int i = 0; i < Math.min(x.length, y.length); i++) {
            int c = cmpSeg(x[i], y[i]);
            if (c != 0) return c;
        }
        return Integer.compare(x.length, y.length);
    }

    static int cat(List<Jar> jars, String fqcn, long[] range) {
        Map<String, Jar> best = new TreeMap<>();
        Set<String> simples = new HashSet<>();
        String entry = fqcn.replace('.', '/') + ".java";
        for (Jar j : jars) {
            boolean has = false;
            for (String c : classes(j)) {
                simples.add(simple(c));
                if (c.equals(fqcn)) has = true;
            }
            if (has) best.merge(j.ga(), j, (o, n) -> cmpVer(n.version(), o.version()) > 0 ? n : o);
        }
        if (best.isEmpty()) {
            ERR.print("error: no source for " + fqcn + suggest(simples, simple(fqcn)) + "\n");
            return 1;
        }
        if (best.size() > 1) {
            ERR.print("error: " + fqcn + " is ambiguous across: " + String.join(", ", best.keySet()) + "\n");
            return 2;
        }
        Jar j = best.values().iterator().next();
        String text;
        try (ZipFile z = new ZipFile(j.path().toFile())) {
            ZipEntry e = z.getEntry(entry);
            try (InputStream in = z.getInputStream(e)) {
                text = new String(in.readAllBytes(), StandardCharsets.UTF_8);
            }
        } catch (IOException e) {
            ERR.print("error: no source for " + fqcn + "\n");
            return 1;
        }
        if (text.startsWith("﻿")) text = text.substring(1);
        text = text.replace("\r\n", "\n").replace('\r', '\n');
        if (text.isEmpty()) return 0;
        if (text.endsWith("\n")) text = text.substring(0, text.length() - 1);
        String[] ls = text.split("\n", -1);
        long a = 1, b = ls.length;
        if (range != null) { a = Math.max(1, range[0]); b = Math.min(ls.length, range[1]); }
        StringBuilder sb = new StringBuilder();
        for (long i = a; i <= b; i++) sb.append(ls[(int) i - 1]).append('\n');
        OUT.print(sb);
        return 0;
    }

    static int ls(List<Jar> jars, String arg) {
        String[] parts = arg.split(":", -1);
        if (parts.length < 2 || parts.length > 3) throw new Usage("invalid argument: " + arg);
        for (String s : parts) if (s.isEmpty()) throw new Usage("invalid argument: " + arg);
        String ga = parts[0] + ":" + parts[1];
        List<Jar> mine = jars.stream().filter(j -> j.ga().equals(ga)).collect(Collectors.toList());
        if (mine.isEmpty()) {
            Set<String> known = jars.stream().map(Jar::ga).collect(Collectors.toSet());
            ERR.print("error: no artifact " + ga + suggest(known, arg) + "\n");
            return 1;
        }
        if (parts.length == 2) {
            mine.stream().map(Jar::version).distinct().sorted(Jarsrc::cmpVer).forEach(v -> OUT.print(v + "\n"));
            return 0;
        }
        Jar hit = mine.stream().filter(j -> j.version().equals(parts[2])).findFirst().orElse(null);
        if (hit == null) {
            Set<String> known = jars.stream().map(Jar::ga).collect(Collectors.toSet());
            ERR.print("error: no artifact " + arg + suggest(known, arg) + "\n");
            return 1;
        }
        classes(hit).stream().distinct().sorted().forEach(c -> OUT.print(c + "\n"));
        return 0;
    }
}
