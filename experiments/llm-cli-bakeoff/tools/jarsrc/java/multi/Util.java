import java.util.ArrayList;
import java.util.List;
import java.util.TreeSet;

public class Util {
    public static void unknownFlag(String f) {
        System.err.println("unknown flag: " + f);
        System.exit(2);
    }

    public static void usageError(String msg) {
        System.err.println("error: " + msg);
        System.exit(2);
    }

    public static void printHelp() {
        System.out.println("usage: jarsrc <find|cat|ls> ... [--root DIR] [--lines A:B]");
        System.out.println("  find <fqcn-or-glob> [--root DIR]");
        System.out.println("  cat <fqcn> [--lines A:B] [--root DIR]");
        System.out.println("  ls <group:artifact[:version]> [--root DIR]");
    }

    public static String globToRegex(String glob) {
        StringBuilder sb = new StringBuilder();
        int i = 0;
        while (i < glob.length()) {
            if (glob.startsWith("**", i)) { sb.append(".*"); i += 2; }
            else if (glob.charAt(i) == '*') { sb.append("[^.]*"); i += 1; }
            else { sb.append(java.util.regex.Pattern.quote(String.valueOf(glob.charAt(i)))); i += 1; }
        }
        return sb.toString();
    }

    public static List<String> suggest(List<String> known, String query) {
        String simple = query.contains(".") ? query.substring(query.lastIndexOf('.') + 1) : query;
        TreeSet<String> matches = new TreeSet<>();
        for (String k : known) if (k.startsWith(simple)) matches.add(k);
        List<String> out = new ArrayList<>();
        for (String m : matches) { if (out.size() >= 3) break; out.add(m); }
        return out;
    }

    public static String errWithSuggestions(String msg, List<String> suggestions) {
        if (suggestions.isEmpty()) return "error: " + msg;
        return "error: " + msg + " (did you mean: " + String.join(", ", suggestions) + "?)";
    }
}
