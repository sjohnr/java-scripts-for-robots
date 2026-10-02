import java.io.IOException;
import java.io.InputStream;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.TreeSet;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;

public class Tool {

    record Ga(String group, String artifact) {
        public String toString() { return group + ":" + artifact; }
    }

    record Entry(String fqcn, Ga ga, String version, Path jarPath) {}

    public static void main(String[] args) throws IOException {
        if (args.length == 0) { usageError("missing subcommand"); return; }
        if (args[0].equals("--help")) { printHelp(); return; }
        String cmd = args[0];
        String root = ".";
        List<String> pos = new ArrayList<>();
        for (int i = 1; i < args.length; i++) {
            if (args[i].equals("--root")) { root = args[++i]; }
            else if (args[i].equals("--lines")) { pos.add("--lines"); pos.add(args[++i]); }
            else if (args[i].startsWith("--")) { unknownFlag(args[i]); return; }
            else pos.add(args[i]);
        }
        Path rootPath = Path.of(root);
        List<Entry> entries = scan(rootPath);
        switch (cmd) {
            case "find" -> doFind(entries, pos.get(0));
            case "cat" -> doCat(entries, pos);
            case "ls" -> doLs(entries, rootPath, pos.get(0));
            default -> { usageError("unknown subcommand: " + cmd); }
        }
    }

    static void unknownFlag(String f) {
        System.err.println("unknown flag: " + f);
        System.exit(2);
    }

    static void usageError(String msg) {
        System.err.println("error: " + msg);
        System.exit(2);
    }

    static void printHelp() {
        System.out.println("usage: jarsrc <find|cat|ls> ... [--root DIR] [--lines A:B]");
        System.out.println("  find <fqcn-or-glob> [--root DIR]");
        System.out.println("  cat <fqcn> [--lines A:B] [--root DIR]");
        System.out.println("  ls <group:artifact[:version]> [--root DIR]");
    }

    static List<Entry> scan(Path root) throws IOException {
        List<Entry> entries = new ArrayList<>();
        if (!Files.isDirectory(root)) return entries;
        Files.walk(root).filter(p -> p.getFileName().toString().endsWith("-sources.jar")).forEach(jar -> {
            // path shape: <root>/<group-dirs...>/<artifact>/<version>/<artifact>-<version>-sources.jar
            Path rel = root.relativize(jar);
            int n = rel.getNameCount();
            if (n < 3) return;
            String version = rel.getName(n - 2).toString();
            String artifact = rel.getName(n - 3).toString();
            StringBuilder group = new StringBuilder();
            for (int i = 0; i < n - 3; i++) {
                if (i > 0) group.append('.');
                group.append(rel.getName(i));
            }
            Ga ga = new Ga(group.toString(), artifact);
            try (ZipInputStream zis = new ZipInputStream(Files.newInputStream(jar))) {
                ZipEntry ze;
                while ((ze = zis.getNextEntry()) != null) {
                    if (ze.getName().endsWith(".java")) {
                        String fqcn = ze.getName().substring(0, ze.getName().length() - 5).replace('/', '.');
                        entries.add(new Entry(fqcn, ga, version, jar));
                    }
                }
            } catch (IOException e) {
                throw new RuntimeException(e);
            }
        });
        return entries;
    }

    static int cmpVersion(String a, String b) {
        String[] sa = a.split("[.\\-]", -1);
        String[] sb = b.split("[.\\-]", -1);
        int n = Math.min(sa.length, sb.length);
        for (int i = 0; i < n; i++) {
            int c = cmpSegment(sa[i], sb[i]);
            if (c != 0) return c;
        }
        return Integer.compare(sa.length, sb.length);
    }

    static boolean isNonNegInt(String s) {
        if (s.isEmpty()) return false;
        for (int i = 0; i < s.length(); i++) if (!Character.isDigit(s.charAt(i))) return false;
        return true;
    }

    static int cmpSegment(String a, String b) {
        if (isNonNegInt(a) && isNonNegInt(b)) {
            return new java.math.BigInteger(a).compareTo(new java.math.BigInteger(b));
        }
        return a.compareTo(b);
    }

    static String globToRegex(String glob) {
        StringBuilder sb = new StringBuilder();
        int i = 0;
        while (i < glob.length()) {
            if (glob.startsWith("**", i)) { sb.append(".*"); i += 2; }
            else if (glob.charAt(i) == '*') { sb.append("[^.]*"); i += 1; }
            else { sb.append(java.util.regex.Pattern.quote(String.valueOf(glob.charAt(i)))); i += 1; }
        }
        return sb.toString();
    }

    static List<String> suggest(List<String> known, String query) {
        String simple = query.contains(".") ? query.substring(query.lastIndexOf('.') + 1) : query;
        TreeSet<String> matches = new TreeSet<>();
        for (String k : known) if (k.startsWith(simple)) matches.add(k);
        List<String> out = new ArrayList<>();
        for (String m : matches) { if (out.size() >= 3) break; out.add(m); }
        return out;
    }

    static String errWithSuggestions(String msg, List<String> suggestions) {
        if (suggestions.isEmpty()) return "error: " + msg;
        return "error: " + msg + " (did you mean: " + String.join(", ", suggestions) + "?)";
    }

    static void doFind(List<Entry> entries, String pattern) {
        boolean isGlob = pattern.contains("*");
        java.util.regex.Pattern re = isGlob ? java.util.regex.Pattern.compile("^" + globToRegex(pattern) + "$") : null;
        TreeMap<String, TreeSet<String>> results = new TreeMap<>();
        for (Entry e : entries) {
            boolean match = isGlob ? re.matcher(e.fqcn()).matches() : e.fqcn().equals(pattern);
            if (match) {
                results.computeIfAbsent(e.fqcn(), k -> new TreeSet<>()).add(e.ga() + ":" + e.version());
            }
        }
        if (results.isEmpty()) {
            List<String> simpleNames = new ArrayList<>();
            for (Entry e : entries) simpleNames.add(e.fqcn().substring(e.fqcn().lastIndexOf('.') + 1));
            System.err.println(errWithSuggestions("no matches for " + pattern, suggest(simpleNames, pattern)));
            System.exit(1);
            return;
        }
        for (var me : results.entrySet()) {
            for (String gav : me.getValue()) {
                System.out.println(me.getKey() + " " + gav);
            }
        }
    }

    static void doCat(List<Entry> entries, List<String> pos) throws IOException {
        String fqcn = null;
        Integer lo = null, hi = null;
        for (int i = 0; i < pos.size(); i++) {
            if (pos.get(i).equals("--lines")) {
                String[] ab = pos.get(++i).split(":", 2);
                lo = Integer.parseInt(ab[0]);
                hi = Integer.parseInt(ab[1]);
            } else if (fqcn == null) fqcn = pos.get(i);
        }
        Map<Ga, TreeSet<String>> gaVersions = new LinkedHashMap<>();
        for (Entry e : entries) if (e.fqcn().equals(fqcn)) {
            gaVersions.computeIfAbsent(e.ga(), k -> new TreeSet<>(Tool::cmpVersion)).add(e.version());
        }
        if (gaVersions.isEmpty()) {
            List<String> simpleNames = new ArrayList<>();
            for (Entry e : entries) simpleNames.add(e.fqcn().substring(e.fqcn().lastIndexOf('.') + 1));
            System.err.println(errWithSuggestions("no source for " + fqcn, suggest(simpleNames, fqcn)));
            System.exit(1);
            return;
        }
        if (gaVersions.size() > 1) {
            List<String> gas = new ArrayList<>();
            for (Ga ga : gaVersions.keySet()) gas.add(ga.toString());
            gas.sort(Comparator.naturalOrder());
            System.err.println("error: " + fqcn + " is ambiguous across: " + String.join(", ", gas));
            System.exit(2);
            return;
        }
        Ga ga = gaVersions.keySet().iterator().next();
        String bestVersion = gaVersions.get(ga).last();
        Entry chosen = null;
        for (Entry e : entries) if (e.fqcn().equals(fqcn) && e.ga().equals(ga) && e.version().equals(bestVersion)) { chosen = e; break; }
        String src = readEntrySource(chosen.jarPath(), fqcn.replace('.', '/') + ".java");
        String[] lines = src.split("\n", -1);
        int n = lines.length;
        if (lines.length > 0 && lines[lines.length - 1].isEmpty()) n = lines.length - 1;
        int start = (lo == null) ? 1 : Math.max(1, lo);
        int end = (hi == null) ? n : Math.min(n, hi);
        if (start > n) return;
        StringBuilder out = new StringBuilder();
        for (int i = start; i <= end; i++) { out.append(lines[i - 1]).append('\n'); }
        System.out.print(out);
    }

    static String readEntrySource(Path jar, String entryName) throws IOException {
        try (ZipInputStream zis = new ZipInputStream(Files.newInputStream(jar))) {
            ZipEntry ze;
            while ((ze = zis.getNextEntry()) != null) {
                if (ze.getName().equals(entryName)) {
                    return new String(zis.readAllBytes(), java.nio.charset.StandardCharsets.UTF_8);
                }
            }
        }
        throw new IOException("entry not found: " + entryName);
    }

    static void doLs(List<Entry> entries, Path root, String spec) {
        String[] parts = spec.split(":");
        String group = parts[0], artifact = parts.length > 1 ? parts[1] : null;
        String version = parts.length > 2 ? parts[2] : null;
        if (artifact == null) { usageError("ls requires group:artifact"); return; }
        Ga ga = new Ga(group, artifact);
        if (version == null) {
            TreeSet<String> versions = new TreeSet<>(Tool::cmpVersion);
            for (Entry e : entries) if (e.ga().equals(ga)) versions.add(e.version());
            if (versions.isEmpty()) {
                notFoundGa(entries, ga.toString());
                return;
            }
            for (String v : versions) System.out.println(v);
        } else {
            TreeSet<String> fqcns = new TreeSet<>();
            for (Entry e : entries) if (e.ga().equals(ga) && e.version().equals(version)) fqcns.add(e.fqcn());
            if (fqcns.isEmpty()) {
                boolean gaExists = entries.stream().anyMatch(e -> e.ga().equals(ga));
                if (gaExists) {
                    System.err.println("error: no artifact " + ga + ":" + version);
                } else {
                    notFoundGa(entries, ga.toString());
                    return;
                }
                System.exit(1);
                return;
            }
            for (String f : fqcns) System.out.println(f);
        }
    }

    static void notFoundGa(List<Entry> entries, String gaStr) {
        TreeSet<String> knownGas = new TreeSet<>();
        for (Entry e : entries) knownGas.add(e.ga().toString());
        List<String> suggestions = new ArrayList<>();
        for (String k : knownGas) { if (k.startsWith(gaStr)) suggestions.add(k); if (suggestions.size() >= 3) break; }
        System.err.println(errWithSuggestions("no artifact " + gaStr, suggestions));
        System.exit(1);
    }
}
