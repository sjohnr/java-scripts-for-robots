package jarsrc;

import java.io.IOException;
import java.io.InputStream;
import java.io.PrintStream;
import java.math.BigInteger;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.Comparator;
import java.util.List;
import java.util.Set;
import java.util.SortedSet;
import java.util.TreeSet;
import java.util.regex.Pattern;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;

import static java.util.stream.Collectors.toSet;

public final class Main {

    private static final String HELP = """
            usage: jarsrc <command> [args] [--root DIR]

            commands:
              find <fqcn-or-glob> [--root DIR]
                  list classes matching an FQCN or glob (* matches within a name, ** across dots)
              cat <fqcn> [--lines A:B] [--root DIR]
                  print the source of a class from its highest version
              ls <group:artifact[:version]> [--root DIR]
                  list the versions of an artifact, or the classes in one version

            flags:
              --root DIR    root of the cache tree (default: .)
              --lines A:B   with cat, print only lines A through B (1-based, inclusive)
              --help        print this message and exit
            """;

    private static final Pattern LINES = Pattern.compile("(-?\\d+):(-?\\d+)");

    private Main() {}

    public static void main(String[] args) {
        StringBuilder out = new StringBuilder();
        StringBuilder err = new StringBuilder();
        int code = run(args, out, err);
        write(System.out, out);
        write(System.err, err);
        System.exit(code);
    }

    static int run(String[] args, StringBuilder out, StringBuilder err) {
        List<String> positional = new ArrayList<>();
        String root = ".";
        String linesArg = null;
        for (int i = 0; i < args.length; i++) {
            String a = args[i];
            if (a.equals("--help")) {
                out.append(HELP);
                return 0;
            } else if (a.equals("--root") || a.equals("--lines")) {
                if (++i == args.length) {
                    return usage(err, a + " requires a value");
                }
                if (a.equals("--root")) {
                    root = args[i];
                } else {
                    linesArg = args[i];
                }
            } else if (a.startsWith("-") && a.length() > 1) {
                err.append("unknown flag: ").append(a).append('\n');
                return 2;
            } else {
                positional.add(a);
            }
        }

        if (positional.isEmpty()) {
            return usage(err, "missing command, see --help");
        }
        String command = positional.remove(0);
        if (!Set.of("find", "cat", "ls").contains(command)) {
            return usage(err, "unknown command: " + command);
        }
        if (linesArg != null && !command.equals("cat")) {
            err.append("unknown flag: --lines\n");
            return 2;
        }
        if (positional.size() != 1) {
            return usage(err, positional.isEmpty()
                    ? command + " requires an argument"
                    : "unexpected argument: " + positional.get(1));
        }
        String arg = positional.get(0);

        Range range = null;
        if (linesArg != null) {
            range = Range.parse(linesArg);
            if (range == null) {
                return usage(err, "--lines expects A:B, got " + linesArg);
            }
        }

        Index index = new Index(Path.of(root));
        return switch (command) {
            case "find" -> find(arg, index, out, err);
            case "ls" -> ls(arg, index, out, err);
            default -> cat(arg, range, index, out, err);
        };
    }

    private static int find(String query, Index index, StringBuilder out, StringBuilder err) {
        Pattern pattern = glob(query);
        SortedSet<Hit> hits = new TreeSet<>(Hit.ORDER);
        for (Index.Artifact a : index.artifacts()) {
            for (String fqcn : index.classesIn(a)) {
                if (pattern.matcher(fqcn).matches()) {
                    hits.add(new Hit(fqcn, a.gav()));
                }
            }
        }
        if (hits.isEmpty()) {
            return notFound(err, "no matches for " + query, lastSegment(query), simpleNames(index));
        }
        for (Hit h : hits) {
            out.append(h.fqcn()).append(' ').append(h.gav()).append('\n');
        }
        return 0;
    }

    private static int cat(String fqcn, Range range, Index index, StringBuilder out, StringBuilder err) {
        List<Index.Artifact> holders = index.holdersOf(fqcn);
        if (holders.isEmpty()) {
            return notFound(err, "no source for " + fqcn, lastSegment(fqcn), simpleNames(index));
        }
        SortedSet<String> gas = new TreeSet<>(Text::lex);
        for (Index.Artifact a : holders) {
            gas.add(a.ga());
        }
        if (gas.size() > 1) {
            err.append("error: ").append(fqcn).append(" is ambiguous across: ")
                    .append(String.join(", ", gas)).append('\n');
            return 2;
        }
        Index.Artifact best = holders.get(0);
        for (Index.Artifact a : holders) {
            if (Version.compare(a.version(), best.version()) > 0) {
                best = a;
            }
        }

        String[] lines = readSource(best.jar(), fqcn).split("\n", -1);
        int total = lines.length;
        if (total > 0 && lines[total - 1].isEmpty()) {
            total--;
        }
        int from = 1;
        int to = total;
        if (range != null) {
            BigInteger lo = range.from().max(BigInteger.ONE);
            BigInteger hi = range.to().min(BigInteger.valueOf(total));
            if (lo.compareTo(hi) > 0) {
                return 0;
            }
            from = lo.intValue();
            to = hi.intValue();
        }
        for (int i = from; i <= to; i++) {
            out.append(lines[i - 1].stripTrailing()).append('\n');
        }
        return 0;
    }

    private static int ls(String target, Index index, StringBuilder out, StringBuilder err) {
        String[] parts = target.split(":", -1);
        if (parts.length < 2 || parts.length > 3 || Arrays.stream(parts).anyMatch(String::isEmpty)) {
            return usage(err, "expected group:artifact[:version], got " + target);
        }
        String ga = parts[0] + ":" + parts[1];
        List<Index.Artifact> matches = index.artifacts().stream().filter(a -> a.ga().equals(ga)).toList();
        Set<String> knownGas = index.artifacts().stream().map(Index.Artifact::ga).collect(toSet());
        if (matches.isEmpty()) {
            return notFound(err, "no artifact " + ga, ga, knownGas);
        }

        if (parts.length == 2) {
            List<String> versions = matches.stream().map(Index.Artifact::version).distinct().toList();
            for (String v : Version.ascending(versions)) {
                out.append(v).append('\n');
            }
            return 0;
        }

        List<Index.Artifact> exact = matches.stream().filter(a -> a.version().equals(parts[2])).toList();
        if (exact.isEmpty()) {
            return notFound(err, "no artifact " + target, ga, knownGas);
        }
        SortedSet<String> names = new TreeSet<>(Text::lex);
        for (Index.Artifact a : exact) {
            names.addAll(index.classesIn(a));
        }
        for (String n : names) {
            out.append(n).append('\n');
        }
        return 0;
    }

    /** Reads one source file as UTF-8. An unreadable jar yields no text. */
    private static String readSource(Path jar, String fqcn) {
        try (ZipFile zip = new ZipFile(jar.toFile())) {
            ZipEntry entry = zip.getEntry(fqcn.replace('.', '/') + ".java");
            if (entry == null) {
                return "";
            }
            try (InputStream in = zip.getInputStream(entry)) {
                return new String(in.readAllBytes(), StandardCharsets.UTF_8);
            }
        } catch (IOException e) {
            return "";
        }
    }

    /** Glob to regex: {@code **} crosses dots, {@code *} does not, and anything else is literal. */
    static Pattern glob(String glob) {
        StringBuilder re = new StringBuilder();
        int i = 0;
        while (i < glob.length()) {
            if (glob.startsWith("**", i)) {
                re.append(".*");
                i += 2;
            } else if (glob.charAt(i) == '*') {
                re.append("[^.]*");
                i++;
            } else {
                int j = i;
                while (j < glob.length() && glob.charAt(j) != '*') {
                    j++;
                }
                re.append(Pattern.quote(glob.substring(i, j)));
                i = j;
            }
        }
        return Pattern.compile(re.toString());
    }

    private static String lastSegment(String name) {
        return name.substring(name.lastIndexOf('.') + 1);
    }

    private static Set<String> simpleNames(Index index) {
        return index.allClasses().stream().map(Main::lastSegment).collect(toSet());
    }

    /** Prints one "not found" line, with up to three prefix-match suggestions. */
    private static int notFound(StringBuilder err, String what, String prefix, Collection<String> known) {
        List<String> hints = known.stream()
                .filter(k -> k.startsWith(prefix))
                .distinct()
                .sorted(Text::lex)
                .limit(3)
                .toList();
        err.append("error: ").append(what);
        if (!hints.isEmpty()) {
            err.append(" (did you mean: ").append(String.join(", ", hints)).append("?)");
        }
        err.append('\n');
        return 1;
    }

    private static int usage(StringBuilder err, String message) {
        err.append("error: ").append(message).append('\n');
        return 2;
    }

    private static void write(PrintStream stream, StringBuilder text) {
        byte[] bytes = text.toString().getBytes(StandardCharsets.UTF_8);
        stream.write(bytes, 0, bytes.length);
        stream.flush();
    }

    private record Hit(String fqcn, String gav) {
        static final Comparator<Hit> ORDER =
                Comparator.comparing(Hit::fqcn, Text::lex).thenComparing(Hit::gav, Text::lex);
    }

    private record Range(BigInteger from, BigInteger to) {
        static Range parse(String s) {
            var m = LINES.matcher(s);
            return m.matches() ? new Range(new BigInteger(m.group(1)), new BigInteger(m.group(2))) : null;
        }
    }
}
