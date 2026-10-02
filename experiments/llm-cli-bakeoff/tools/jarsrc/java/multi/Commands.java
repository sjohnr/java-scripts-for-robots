import java.io.IOException;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.TreeSet;

public class Commands {
    static void doFind(List<Scanner.Entry> entries, String pattern) {
        boolean isGlob = pattern.contains("*");
        java.util.regex.Pattern re = isGlob ? java.util.regex.Pattern.compile("^" + Util.globToRegex(pattern) + "$") : null;
        TreeMap<String, TreeSet<String>> results = new TreeMap<>();
        for (Scanner.Entry e : entries) {
            boolean match = isGlob ? re.matcher(e.fqcn()).matches() : e.fqcn().equals(pattern);
            if (match) {
                results.computeIfAbsent(e.fqcn(), k -> new TreeSet<>()).add(e.ga() + ":" + e.version());
            }
        }
        if (results.isEmpty()) {
            List<String> simpleNames = new ArrayList<>();
            for (Scanner.Entry e : entries) simpleNames.add(e.fqcn().substring(e.fqcn().lastIndexOf('.') + 1));
            System.err.println(Util.errWithSuggestions("no matches for " + pattern, Util.suggest(simpleNames, pattern)));
            System.exit(1);
            return;
        }
        for (var me : results.entrySet()) {
            for (String gav : me.getValue()) {
                System.out.println(me.getKey() + " " + gav);
            }
        }
    }

    static void doCat(List<Scanner.Entry> entries, List<String> pos) throws IOException {
        String fqcn = null;
        Integer lo = null, hi = null;
        for (int i = 0; i < pos.size(); i++) {
            if (pos.get(i).equals("--lines")) {
                String[] ab = pos.get(++i).split(":", 2);
                lo = Integer.parseInt(ab[0]);
                hi = Integer.parseInt(ab[1]);
            } else if (fqcn == null) fqcn = pos.get(i);
        }
        Map<Scanner.Ga, TreeSet<String>> gaVersions = new LinkedHashMap<>();
        for (Scanner.Entry e : entries) if (e.fqcn().equals(fqcn)) {
            gaVersions.computeIfAbsent(e.ga(), k -> new TreeSet<>((a, b) -> VersionCompare.cmpVersion(a, b))).add(e.version());
        }
        if (gaVersions.isEmpty()) {
            List<String> simpleNames = new ArrayList<>();
            for (Scanner.Entry e : entries) simpleNames.add(e.fqcn().substring(e.fqcn().lastIndexOf('.') + 1));
            System.err.println(Util.errWithSuggestions("no source for " + fqcn, Util.suggest(simpleNames, fqcn)));
            System.exit(1);
            return;
        }
        if (gaVersions.size() > 1) {
            List<String> gas = new ArrayList<>();
            for (Scanner.Ga ga : gaVersions.keySet()) gas.add(ga.toString());
            gas.sort(Comparator.naturalOrder());
            System.err.println("error: " + fqcn + " is ambiguous across: " + String.join(", ", gas));
            System.exit(2);
            return;
        }
        Scanner.Ga ga = gaVersions.keySet().iterator().next();
        String bestVersion = gaVersions.get(ga).last();
        Scanner.Entry chosen = null;
        for (Scanner.Entry e : entries) if (e.fqcn().equals(fqcn) && e.ga().equals(ga) && e.version().equals(bestVersion)) { chosen = e; break; }
        String src = Scanner.readEntrySource(chosen.jarPath(), fqcn.replace('.', '/') + ".java");
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

    static void doLs(List<Scanner.Entry> entries, String spec) {
        String[] parts = spec.split(":");
        String group = parts[0], artifact = parts.length > 1 ? parts[1] : null;
        String version = parts.length > 2 ? parts[2] : null;
        if (artifact == null) { Util.usageError("ls requires group:artifact"); return; }
        Scanner.Ga ga = new Scanner.Ga(group, artifact);
        if (version == null) {
            TreeSet<String> versions = new TreeSet<>((a, b) -> VersionCompare.cmpVersion(a, b));
            for (Scanner.Entry e : entries) if (e.ga().equals(ga)) versions.add(e.version());
            if (versions.isEmpty()) {
                notFoundGa(entries, ga.toString());
                return;
            }
            for (String v : versions) System.out.println(v);
        } else {
            TreeSet<String> fqcns = new TreeSet<>();
            for (Scanner.Entry e : entries) if (e.ga().equals(ga) && e.version().equals(version)) fqcns.add(e.fqcn());
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

    static void notFoundGa(List<Scanner.Entry> entries, String gaStr) {
        TreeSet<String> knownGas = new TreeSet<>();
        for (Scanner.Entry e : entries) knownGas.add(e.ga().toString());
        List<String> suggestions = new ArrayList<>();
        for (String k : knownGas) { if (k.startsWith(gaStr)) suggestions.add(k); if (suggestions.size() >= 3) break; }
        System.err.println(Util.errWithSuggestions("no artifact " + gaStr, suggestions));
        System.exit(1);
    }
}
