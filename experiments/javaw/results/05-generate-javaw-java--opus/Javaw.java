import java.io.IOException;
import java.io.PrintStream;
import java.nio.file.*;
import java.nio.file.attribute.PosixFilePermissions;
import java.util.*;

/** javaw: generates a relocatable POSIX sh launcher script for a Java program. */
public class Javaw {
    static final String HELP = """
        Usage: javaw [options] INPUT...
        Write a launcher shell script for each Java source file (or jar, with --jar).

          --jar               inputs are jars: launch with java -jar
          --classpath PATH    add -cp PATH to the launch command (recorded as-is)
          --java-home DIR     use DIR/bin/java instead of java from the PATH
          --jvm-opts "OPTS"   JVM options (split at spaces) placed before the program
          --min-java N        fail with a clear error if the Java version is older than N
          -o, --output NAME   output file, or a directory if it ends in / or exists
          --force             overwrite existing files
          -h, --help          show this help

        Default output: run-<input name in kebab-case>, in the current directory.
        """;

    static class Fail extends RuntimeException { Fail(String m) { super(m); } }

    public static void main(String[] args) throws IOException {
        try {
            System.exit(run(args, Path.of("").toAbsolutePath(), System.out));
        } catch (Fail e) {
            System.err.println("javaw: " + e.getMessage());
            System.exit(2);
        }
    }

    static int run(String[] args, Path cwd, PrintStream out) throws IOException {
        boolean jar = false, force = false;
        String cp = null, home = null, opts = null, min = null, output = null;
        List<String> options = new ArrayList<>(), inputs = new ArrayList<>();
        for (int i = 0; i < args.length; i++) {
            String a = args[i];
            if (a.equals("--")) { inputs.addAll(Arrays.asList(args).subList(i + 1, args.length)); break; }
            if (!a.startsWith("-") || a.equals("-")) { inputs.add(a); continue; }
            options.add(a);
            switch (a) {
                case "-h", "--help" -> { out.print(HELP); return 0; }
                case "--jar" -> jar = true;
                case "--force" -> force = true;
                case "--classpath", "--java-home", "--jvm-opts", "--min-java", "-o", "--output" -> {
                    if (++i == args.length) throw new Fail(a + " requires a value");
                    String v = args[i];
                    options.add(v);
                    switch (a) {
                        case "--classpath" -> cp = v;
                        case "--java-home" -> home = v;
                        case "--jvm-opts" -> opts = v;
                        case "--min-java" -> min = v;
                        default -> output = v;
                    }
                }
                default -> throw new Fail("unknown option: " + a + " (see --help)");
            }
        }
        if (inputs.isEmpty()) throw new Fail("missing input file (see --help)");
        if (min != null && !min.matches("[1-9][0-9]{0,3}")) throw new Fail("--min-java needs a version number, got: " + min);

        Path outPath = output == null ? cwd : cwd.resolve(output);
        boolean outIsDir = output == null || output.endsWith("/") || Files.isDirectory(outPath);
        if (inputs.size() > 1 && !outIsDir) throw new Fail("-o must be a directory when there are several inputs");

        Map<Path, String> plan = new LinkedHashMap<>();  // wrapper path -> input
        for (String in : inputs) {
            Path src = cwd.resolve(in);
            if (!Files.isRegularFile(src)) throw new Fail("input not found: " + in);
            String name = kebab(src.getFileName().toString().replaceFirst("(?<=.)\\.[^.]*$", ""));
            if (name.isEmpty()) throw new Fail("cannot derive a name from: " + in);
            Path target = outIsDir ? outPath.resolve("run-" + name) : outPath;
            if (plan.put(target, in) != null) throw new Fail("two inputs would both write " + target);
            if (!force && Files.exists(target, LinkOption.NOFOLLOW_LINKS))
                throw new Fail(target + " already exists (use --force to overwrite)");
        }

        for (var e : plan.entrySet()) {
            Path target = e.getKey(), src = cwd.resolve(e.getValue());
            Files.createDirectories(target.getParent());
            Path real = src.getParent().toRealPath().resolve(src.getFileName());
            String prog = target.getParent().toRealPath().relativize(real).toString();
            List<String> cmd = new ArrayList<>(options);
            if (e.getValue().startsWith("-")) cmd.add("--");
            cmd.add(e.getValue());
            String header = "javaw " + String.join(" ", cmd.stream().map(Javaw::sq).toList());
            Files.deleteIfExists(target);
            Files.writeString(target, wrapper(header, prog, jar, cp, home, opts, min));
            Files.setPosixFilePermissions(target, PosixFilePermissions.fromString("rwxr-xr-x"));
            out.println("wrote " + target);
        }
        return 0;
    }

    static String wrapper(String header, String prog, boolean jar, String cp, String home, String opts, String min) {
        String java = home == null ? "java" : sq(home.replaceFirst("/+$", "") + "/bin/java");
        StringBuilder s = new StringBuilder("#!/bin/sh\n# Generated by: " + header + "\n");
        s.append("""
            self=$0
            while [ -L "$self" ]; do
              link=$(readlink -- "$self")
              case $link in /*) self=$link ;; *) self=$(dirname -- "$self")/$link ;; esac
            done
            dir=$(CDPATH= cd -P -- "$(dirname -- "$self")" && pwd -P) || exit 1
            """);
        if (min != null) s.append("""
            v=$(%1$s -version 2>&1 | sed -n 's/.*version "\\([^"]*\\)".*/\\1/p' | head -n 1)
            n=${v#1.}; n=${n%%%%[!0-9]*}
            if [ -z "$n" ] || [ "$n" -lt %2$s ]; then
              echo "error: this program requires Java %2$s or newer, but found: ${v:-no usable Java}" >&2
              exit 1
            fi
            """.formatted(java, min));
        s.append("exec ").append(java);
        if (opts != null && !opts.isBlank())
            for (String o : opts.trim().split("\\s+")) s.append(' ').append(sq(o));
        if (cp != null) s.append(" -cp ").append(sq(cp));
        if (jar) s.append(" -jar");
        s.append(" \"$dir/").append(prog.replaceAll("([\\\\$`\"])", "\\\\$1")).append("\" \"$@\"\n");
        return s.toString();
    }

    /** Converts a name such as HTTPServer or Example1_Assert to kebab-case. */
    static String kebab(String name) {
        return name.replaceAll("([a-z0-9])([A-Z])", "$1-$2")
                .replaceAll("([A-Z])([A-Z][a-z])", "$1-$2")
                .replaceAll("[-_. ]+", "-")
                .replaceAll("^-|-$", "")
                .toLowerCase(Locale.ROOT);
    }

    /** Quotes a word for POSIX sh, leaving plain words unquoted. */
    static String sq(String w) {
        return w.matches("[A-Za-z0-9_@%+=:,./-]+") ? w : "'" + w.replace("'", "'\\''") + "'";
    }
}
