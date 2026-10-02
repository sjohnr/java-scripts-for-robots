package jarsrc;

import java.io.BufferedOutputStream;
import java.io.FileDescriptor;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.PrintStream;
import java.math.BigInteger;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Set;
import java.util.TreeSet;
import java.util.regex.Pattern;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;

/** Command-line entry point for jarsrc. See SPEC.md. */
public final class Main {

    static final String HELP = """
            Usage:
              jarsrc find <fqcn-or-glob> [--root DIR]
              jarsrc cat <fqcn> [--lines A:B] [--root DIR]
              jarsrc ls <group:artifact[:version]> [--root DIR]
              jarsrc --help

            Subcommands:
              find   list FQCN and group:artifact:version for every class matching
                     <fqcn-or-glob>; "*" matches within a dot-separated segment,
                     "**" matches across dots
              cat    print the source of <fqcn> from its highest version
              ls     list versions of group:artifact, or the classes in
                     group:artifact:version

            Flags:
              --root DIR     root of the Gradle-style cache tree (default: .)
              --lines A:B    with cat, print only lines A through B (1-indexed, inclusive)
              --help         print this message and exit 0
            """;

    private static final PrintStream OUT =
            new PrintStream(new BufferedOutputStream(new FileOutputStream(FileDescriptor.out)), false, StandardCharsets.UTF_8);
    private static final PrintStream ERR =
            new PrintStream(new FileOutputStream(FileDescriptor.err), true, StandardCharsets.UTF_8);

    private static final Pattern LINES = Pattern.compile("(\\d+):(\\d+)");

    /** Carries an exit code and the complete stderr line to print. */
    static final class CliException extends RuntimeException {
        private static final long serialVersionUID = 1L;

        final int code;

        CliException(int code, String line) {
            super(line);
            this.code = code;
        }
    }

    record Range(int from, int to) {
    }

    record Match(String fqcn, String gav) {
    }

    public static void main(String[] argv) {
        int code = 0;
        try {
            run(argv);
        } catch (CliException e) {
            emit(ERR, e.getMessage());
            code = e.code;
        } catch (IOException e) {
            emit(ERR, "error: " + (e.getMessage() != null ? e.getMessage() : e.toString()));
            code = 2;
        }
        OUT.flush();
        ERR.flush();
        System.exit(code);
    }

    private static void run(String[] argv) throws IOException {
        List<String> args = List.of(argv);
        if (args.contains("--help")) {
            OUT.print(HELP);
            return;
        }
        if (args.isEmpty()) {
            throw usage("missing subcommand");
        }
        String command = args.get(0);
        if (command.startsWith("-")) {
            throw unknownFlag(command);
        }
        Set<String> flags = switch (command) {
            case "find", "ls" -> Set.of("--root");
            case "cat" -> Set.of("--root", "--lines");
            default -> throw usage("unknown subcommand: " + command);
        };

        String root = ".";
        Range range = null;
        List<String> positional = new ArrayList<>();
        for (int i = 1; i < args.size(); i++) {
            String arg = args.get(i);
            if (arg.length() < 2 || !arg.startsWith("-")) {
                positional.add(arg);
                continue;
            }
            String name = arg;
            String value = null;
            int eq = arg.indexOf('=');
            if (arg.startsWith("--") && eq >= 0) {
                name = arg.substring(0, eq);
                value = arg.substring(eq + 1);
            }
            if (!flags.contains(name)) {
                throw unknownFlag(name);
            }
            if (value == null) {
                if (i + 1 >= args.size()) {
                    throw usage(name + " requires a value");
                }
                value = args.get(++i);
            }
            if (name.equals("--root")) {
                root = value;
            } else {
                range = parseRange(value);
            }
        }

        String argName = switch (command) {
            case "find" -> "<fqcn-or-glob>";
            case "cat" -> "<fqcn>";
            default -> "<group:artifact[:version]>";
        };
        if (positional.isEmpty()) {
            throw usage("missing argument: " + argName);
        }
        if (positional.size() > 1) {
            throw usage("unexpected argument: " + positional.get(1));
        }
        String target = positional.get(0);

        Index index = Index.scan(Path.of(root));
        switch (command) {
            case "find" -> find(index, target);
            case "cat" -> cat(index, target, range);
            default -> ls(index, target);
        }
    }

    // ---- subcommands -------------------------------------------------------

    private static void find(Index index, String pattern) {
        Pattern re = glob(pattern);
        List<Match> matches = new ArrayList<>();
        for (Index.Artifact a : index.artifacts()) {
            for (String fqcn : a.classes) {
                if (re.matcher(fqcn).matches()) {
                    matches.add(new Match(fqcn, a.gav()));
                }
            }
        }
        if (matches.isEmpty()) {
            throw notFound("no matches for " + pattern,
                    suggest(lastSegment(pattern).split("\\*", -1)[0], simpleNames(index)));
        }
        matches.stream()
                .distinct()
                .sorted((x, y) -> {
                    int c = x.fqcn().compareTo(y.fqcn());
                    return c != 0 ? c : x.gav().compareTo(y.gav());
                })
                .forEach(m -> emit(OUT, m.fqcn() + " " + m.gav()));
    }

    private static void cat(Index index, String fqcn, Range range) throws IOException {
        List<Index.Artifact> hits = index.containing(fqcn);
        if (hits.isEmpty()) {
            throw notFound("no source for " + fqcn,
                    suggest(lastSegment(fqcn), simpleNames(index)));
        }
        TreeSet<String> gas = new TreeSet<>();
        for (Index.Artifact a : hits) {
            gas.add(a.ga());
        }
        if (gas.size() > 1) {
            throw new CliException(2, "error: " + fqcn + " is ambiguous across: " + String.join(", ", gas));
        }
        Index.Artifact best = null;
        for (Index.Artifact a : hits) {
            if (best == null || Version.compare(a.version, best.version) > 0) {
                best = a;
            }
        }

        String text;
        try (ZipFile zip = new ZipFile(best.jar.toFile())) {
            ZipEntry entry = zip.getEntry(Index.toEntryName(fqcn));
            if (entry == null) {
                throw new IOException("missing entry " + Index.toEntryName(fqcn) + " in " + best.jar);
            }
            text = new String(zip.getInputStream(entry).readAllBytes(), StandardCharsets.UTF_8);
        }

        List<String> lines = splitLines(text);
        int from = 1;
        int to = lines.size();
        if (range != null) {
            from = Math.max(range.from, 1);
            to = Math.min(range.to, lines.size());
        }
        for (int i = from; i <= to; i++) {
            emit(OUT, lines.get(i - 1));
        }
    }

    private static void ls(Index index, String target) {
        String[] parts = target.split(":", -1);
        boolean valid = (parts.length == 2 || parts.length == 3)
                && !parts[0].isEmpty() && !parts[1].isEmpty()
                && (parts.length == 2 || !parts[2].isEmpty());
        if (!valid) {
            throw usage("invalid group:artifact[:version]: " + target);
        }
        String ga = parts[0] + ":" + parts[1];

        if (parts.length == 2) {
            if (!index.hasGa(ga)) {
                throw notFound("no artifact " + ga, suggest(ga, index.allGas()));
            }
            index.versionsOf(ga).stream()
                    .map(a -> a.version)
                    .sorted(Version::compare)
                    .distinct()
                    .forEach(v -> emit(OUT, v));
            return;
        }

        String version = parts[2];
        Index.Artifact match = null;
        for (Index.Artifact a : index.versionsOf(ga)) {
            if (a.version.equals(version)) {
                match = a;
            }
        }
        if (match == null) {
            String missing = index.hasGa(ga) ? target : ga;
            throw notFound("no artifact " + missing, suggest(ga, index.allGas()));
        }
        new TreeSet<>(match.classes).forEach(c -> emit(OUT, c));
    }

    // ---- helpers -----------------------------------------------------------

    /** Compiles a find pattern: "**" matches any run of characters, "*" any run except ".". */
    static Pattern glob(String glob) {
        StringBuilder re = new StringBuilder();
        StringBuilder literal = new StringBuilder();
        int i = 0;
        while (i < glob.length()) {
            if (glob.startsWith("**", i) || glob.charAt(i) == '*') {
                if (!literal.isEmpty()) {
                    re.append(Pattern.quote(literal.toString()));
                    literal.setLength(0);
                }
                boolean deep = glob.startsWith("**", i);
                re.append(deep ? ".*" : "[^.]*");
                i += deep ? 2 : 1;
            } else {
                literal.append(glob.charAt(i));
                i++;
            }
        }
        if (!literal.isEmpty()) {
            re.append(Pattern.quote(literal.toString()));
        }
        return Pattern.compile(re.toString());
    }

    private static String lastSegment(String fqcn) {
        return fqcn.substring(fqcn.lastIndexOf('.') + 1);
    }

    private static TreeSet<String> simpleNames(Index index) {
        TreeSet<String> names = new TreeSet<>();
        for (String fqcn : index.allClasses()) {
            names.add(lastSegment(fqcn));
        }
        return names;
    }

    /** Up to three sorted names starting with prefix; empty prefix yields none. */
    private static List<String> suggest(String prefix, Collection<String> names) {
        if (prefix.isEmpty()) {
            return List.of();
        }
        return names.stream().filter(n -> n.startsWith(prefix)).limit(3).toList();
    }

    private static CliException notFound(String what, List<String> suggestions) {
        String hint = suggestions.isEmpty() ? "" : " (did you mean: " + String.join(", ", suggestions) + "?)";
        return new CliException(1, "error: " + what + hint);
    }

    private static CliException usage(String message) {
        return new CliException(2, "error: " + message);
    }

    private static CliException unknownFlag(String flag) {
        return new CliException(2, "unknown flag: " + flag);
    }

    private static Range parseRange(String value) {
        var m = LINES.matcher(value);
        if (!m.matches()) {
            throw usage("invalid --lines value: " + value + " (expected A:B)");
        }
        return new Range(clamp(m.group(1)), clamp(m.group(2)));
    }

    private static int clamp(String digits) {
        return new BigInteger(digits).min(BigInteger.valueOf(Integer.MAX_VALUE)).intValue();
    }

    /** Splits text into lines; a trailing newline does not start an extra line. */
    private static List<String> splitLines(String text) {
        String[] parts = text.replace("\r\n", "\n").replace('\r', '\n').split("\n", -1);
        List<String> lines = new ArrayList<>(List.of(parts));
        if (!lines.isEmpty() && lines.get(lines.size() - 1).isEmpty()) {
            lines.remove(lines.size() - 1);
        }
        return lines;
    }

    /** Writes one line, LF-terminated, with trailing whitespace removed. */
    private static void emit(PrintStream stream, String line) {
        stream.print(line.stripTrailing());
        stream.print('\n');
    }
}
