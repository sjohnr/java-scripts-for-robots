import java.io.*;
import java.math.BigInteger;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.*;
import java.util.regex.Pattern;
import java.util.stream.*;
import java.util.zip.*;

public class Jarsrc {
    static final String HELP = String.join("\n",
        "Usage:",
        "  jarsrc find <fqcn-or-glob> [--root DIR]",
        "  jarsrc cat <fqcn> [--lines A:B] [--root DIR]",
        "  jarsrc ls <group:artifact[:version]> [--root DIR]",
        "  jarsrc --help",
        "",
        "Subcommands:",
        "  find   list sources jars containing classes matching an FQCN or glob (* excludes '.', ** includes '.')",
        "  cat    print the source of an FQCN from the highest version",
        "  ls     list versions of group:artifact, or classes in group:artifact:version",
        "",
        "Flags:",
        "  --root DIR    root of the Gradle-cache-like tree (default: .)",
        "  --lines A:B   (cat) print only lines A through B, 1-indexed inclusive",
        "  --help        show this message",
        "");

    static final class Usage extends RuntimeException {
        Usage(String m) { super(m); }
    }

    record Jar(String group, String artifact, String version, Path path) {
        String ga() { return group + ":" + artifact; }
        String gav() { return group + ":" + artifact + ":" + version; }
    }

    static PrintStream out, err;

    public static void main(String[] args) {
        out = new PrintStream(new FileOutputStream(FileDescriptor.out), false, StandardCharsets.UTF_8);
        err = new PrintStream(new FileOutputStream(FileDescriptor.err), false, StandardCharsets.UTF_8);
        int code;
        try {
            code = run(args);
        } catch (Usage u) {
            err.print(u.getMessage() + "\n");
            code = 2;
        }
        out.flush();
        err.flush();
        System.exit(code);
    }

    static int run(String[] args) {
        String root = ".";
        String lines = null;
        List<String> pos = new ArrayList<>();
        boolean help = false;
        for (int i = 0; i < args.length; i++) {
            String a = args[i];
            if (a.equals("--help")) {
                help = true;
            } else if (a.equals("--root") || a.startsWith("--root=")) {
                if (a.equals("--root")) {
                    if (i + 1 >= args.length) throw new Usage("error: --root requires a value");
                    root = args[++i];
                } else root = a.substring(7);
            } else if (a.equals("--lines") || a.startsWith("--lines=")) {
                if (a.equals("--lines")) {
                    if (i + 1 >= args.length) throw new Usage("error: --lines requires a value");
                    lines = args[++i];
                } else lines = a.substring(8);
            } else if (a.startsWith("-") && a.length() > 1) {
                throw new Usage("unknown flag: " + a);
            } else pos.add(a);
        }
        if (help) {
            out.print(HELP);
            return 0;
        }
        if (pos.isEmpty()) throw new Usage("error: missing subcommand (find, cat, ls)");
        String cmd = pos.get(0);
        if (!cmd.equals("find") && !cmd.equals("cat") && !cmd.equals("ls"))
            throw new Usage("error: unknown subcommand: " + cmd);
        if (pos.size() < 2) throw new Usage("error: missing argument for " + cmd);
        if (pos.size() > 2) throw new Usage("error: unexpected argument: " + pos.get(2));
        if (lines != null && !cmd.equals("cat")) throw new Usage("error: --lines is only valid for cat");
        long[] range = null;
        if (lines != null) {
            var m = Pattern.compile("(\\d+):(\\d+)").matcher(lines);
            if (!m.matches()) throw new Usage("error: invalid --lines value: " + lines);
            try {
                range = new long[]{Long.parseLong(m.group(1)), Long.parseLong(m.group(2))};
            } catch (NumberFormatException e) {
                range = new long[]{clampParse(m.group(1)), clampParse(m.group(2))};
            }
        }
        String arg = pos.get(1);
        List<Jar> jars = scan(Paths.get(root));
        return switch (cmd) {
            case "find" -> find(jars, arg);
            case "cat" -> cat(jars, arg, range);
            default -> ls(jars, arg);
        };
    }

    static long clampParse(String s) {
        return new BigInteger(s).min(BigInteger.valueOf(Long.MAX_VALUE)).longValue();
    }

    static List<Jar> scan(Path root) {
        List<Jar> res = new ArrayList<>();
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
                 if (!rel.getFileName().toString().equals(artifact + "-" + version + "-sources.jar")) return;
                 String group = IntStream.range(0, n - 3).mapToObj(i -> rel.getName(i).toString())
                     .collect(Collectors.joining("."));
                 res.add(new Jar(group, artifact, version, p));
             });
        } catch (IOException | UncheckedIOException e) {
            // unreadable subtree: use what we have
        }
        return res;
    }

    static List<String> classes(Jar j) {
        List<String> r = new ArrayList<>();
        try (ZipFile z = new ZipFile(j.path().toFile())) {
            for (var en = z.entries(); en.hasMoreElements(); ) {
                ZipEntry e = en.nextElement();
                String n = e.getName();
                if (e.isDirectory() || !n.endsWith(".java")) continue;
                r.add(n.substring(0, n.length() - 5).replace('/', '.'));
            }
        } catch (IOException e) {
            // corrupt jar: ignore
        }
        return r;
    }

    static int notFound(String what, Collection<String> candidates, String prefix) {
        List<String> sug = candidates.stream().filter(c -> c.startsWith(prefix)).distinct().sorted()
            .limit(3).collect(Collectors.toList());
        String msg = "error: " + what;
        if (!sug.isEmpty()) msg += " (did you mean: " + String.join(", ", sug) + "?)";
        err.print(msg + "\n");
        return 1;
    }

    static String simple(String fqcn) {
        return fqcn.substring(fqcn.lastIndexOf('.') + 1);
    }

    static int find(List<Jar> jars, String pat) {
        Pattern re = null;
        if (pat.contains("*")) {
            StringBuilder sb = new StringBuilder();
            for (int i = 0; i < pat.length(); ) {
                if (pat.startsWith("**", i)) { sb.append(".*"); i += 2; }
                else if (pat.charAt(i) == '*') { sb.append("[^.]*"); i++; }
                else {
                    int j = i;
                    while (j < pat.length() && pat.charAt(j) != '*') j++;
                    sb.append(Pattern.quote(pat.substring(i, j)));
                    i = j;
                }
            }
            re = Pattern.compile(sb.toString(), Pattern.DOTALL);
        }
        List<String[]> hits = new ArrayList<>();
        Set<String> simples = new HashSet<>();
        for (Jar j : jars) {
            for (String c : classes(j)) {
                simples.add(simple(c));
                if (re == null ? c.equals(pat) : re.matcher(c).matches())
                    hits.add(new String[]{c, j.gav()});
            }
        }
        if (hits.isEmpty()) return notFound("no matches for " + pat, simples, simple(pat));
        hits.sort((a, b) -> a[0].equals(b[0]) ? a[1].compareTo(b[1]) : a[0].compareTo(b[0]));
        StringBuilder sb = new StringBuilder();
        for (String[] h : hits) sb.append(h[0]).append(' ').append(h[1]).append('\n');
        out.print(sb);
        return 0;
    }

    static int cat(List<Jar> jars, String fqcn, long[] range) {
        String entry = fqcn.replace('.', '/') + ".java";
        Map<String, Jar> best = new TreeMap<>();
        Set<String> simples = new HashSet<>();
        for (Jar j : jars) {
            boolean has = false;
            for (String c : classes(j)) {
                simples.add(simple(c));
                if (c.equals(fqcn)) has = true;
            }
            if (has) best.merge(j.ga(), j, (a, b) -> compareVersions(a.version(), b.version()) >= 0 ? a : b);
        }
        if (best.isEmpty()) return notFound("no source for " + fqcn, simples, simple(fqcn));
        if (best.size() > 1)
            throw new Usage("error: " + fqcn + " is ambiguous across: " + String.join(", ", best.keySet()));
        Jar j = best.values().iterator().next();
        String text;
        try (ZipFile z = new ZipFile(j.path().toFile())) {
            ZipEntry e = z.getEntry(entry);
            try (InputStream in = z.getInputStream(e)) {
                text = new String(in.readAllBytes(), StandardCharsets.UTF_8);
            }
        } catch (IOException e) {
            err.print("error: no source for " + fqcn + "\n");
            return 1;
        }
        text = text.replace("\r\n", "\n").replace('\r', '\n');
        if (!text.isEmpty() && text.charAt(0) == '﻿') text = text.substring(1);
        List<String> ls = new ArrayList<>(Arrays.asList(text.split("\n", -1)));
        if (!ls.isEmpty() && ls.get(ls.size() - 1).isEmpty()) ls.remove(ls.size() - 1);
        long a = 1, b = ls.size();
        if (range != null) {
            a = Math.max(1, range[0]);
            b = Math.min(ls.size(), range[1]);
        }
        StringBuilder sb = new StringBuilder();
        for (long i = a; i <= b; i++) sb.append(ls.get((int) (i - 1))).append('\n');
        out.print(sb);
        return 0;
    }

    static int ls(List<Jar> jars, String arg) {
        String[] parts = arg.split(":", -1);
        if (parts.length < 2 || parts.length > 3 || parts[0].isEmpty() || parts[1].isEmpty()
                || (parts.length == 3 && parts[2].isEmpty()))
            throw new Usage("error: expected group:artifact[:version], got: " + arg);
        String ga = parts[0] + ":" + parts[1];
        List<Jar> matching = jars.stream().filter(j -> j.ga().equals(ga)).collect(Collectors.toList());
        Set<String> gas = jars.stream().map(Jar::ga).collect(Collectors.toSet());
        if (matching.isEmpty()) return notFound("no artifact " + arg, gas, arg);
        StringBuilder sb = new StringBuilder();
        if (parts.length == 2) {
            matching.sort((x, y) -> compareVersions(x.version(), y.version()));
            for (Jar j : matching) sb.append(j.version()).append('\n');
        } else {
            Jar hit = matching.stream().filter(j -> j.version().equals(parts[2])).findFirst().orElse(null);
            if (hit == null) return notFound("no artifact " + arg, gas, arg);
            List<String> cs = classes(hit);
            Collections.sort(cs);
            for (String c : cs) sb.append(c).append('\n');
        }
        out.print(sb);
        return 0;
    }

    static boolean isInt(String s) {
        if (s.isEmpty()) return false;
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            if (c < '0' || c > '9') return false;
        }
        return true;
    }

    static int compareVersions(String a, String b) {
        String[] x = a.split("[.-]", -1), y = b.split("[.-]", -1);
        for (int i = 0; i < Math.min(x.length, y.length); i++) {
            int c;
            if (isInt(x[i]) && isInt(y[i])) c = new BigInteger(x[i]).compareTo(new BigInteger(y[i]));
            else c = Arrays.compareUnsigned(x[i].getBytes(StandardCharsets.UTF_8), y[i].getBytes(StandardCharsets.UTF_8));
            if (c != 0) return c < 0 ? -1 : 1;
        }
        return Integer.compare(x.length, y.length);
    }
}
