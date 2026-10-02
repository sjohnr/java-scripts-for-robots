///usr/bin/env jbang "$0" "$@" ; exit $?
//JAVA 17+

import java.io.*;
import java.nio.file.*;
import java.nio.file.attribute.PosixFilePermissions;
import java.util.*;

/** javaw: generates a relocatable POSIX sh launcher script for a Java program. */
class javaw {
    static final String HELP = """
        Usage: javaw [options] Tool.java...
               javaw [options] --jar tool.jar...

        Writes an executable sh script (default: ./run-<kebab-name>) that launches
        the program relative to the script's own real location.

        Options:
          --jar FILE          launch FILE with java -jar (repeatable)
          --classpath PATH    add -cp PATH to the launch command (recorded as-is)
          --java-home DIR     use DIR/bin/java instead of java from the PATH
          --jvm-opts "OPTS"   JVM options inserted before the program
          --min-java N        fail with a clear error if Java is older than N
          -o, --output NAME   output file, or a directory if it ends in / or exists
          --force             overwrite existing files
          -h, --help          show this help
        """;

    // Resolves symlinks to the wrapper (chains, relative targets), then its directory.
    // The ./ prefix keeps paths away from CDPATH and from looking like options.
    static final String RESOLVE = """
        self=$0
        case $self in /*) ;; *) self=./$self ;; esac
        while [ -h "$self" ]; do
          link=$(readlink -- "$self")
          case $link in /*) self=$link ;; *) self=${self%/*}/$link ;; esac
        done
        dir=$(CDPATH= cd -P -- "${self%/*}/" && pwd -P) || exit 1
        """;

    static final String MIN_JAVA = """
        version=$(%1$s -version 2>&1 | sed -n 's/.* version "\\([^"]*\\)".*/\\1/p' | head -n 1)
        major=${version#1.}
        major=${major%%%%[!0-9]*}
        if [ -z "$major" ] || [ "$major" -lt %2$d ]; then
          echo "error: Java %2$d or newer is required (found: ${version:-none})" >&2
          exit 1
        fi
        """;

    record Input(String path, boolean jar) {}

    static class Opts {
        List<Input> inputs = new ArrayList<>();
        String classpath, javaHome, jvmOpts, output;
        Integer minJava;
        boolean force;
    }

    public static void main(String[] args) {
        System.exit(run(args, Path.of("").toAbsolutePath(), System.out, System.err));
    }

    static int run(String[] args, Path cwd, PrintStream out, PrintStream err) {
        try {
            Opts o = parse(args);
            if (o == null) { out.print(HELP); return 0; }
            Map<Path, Input> plan = plan(o, cwd);
            for (Path p : plan.keySet())
                if (Files.exists(p, LinkOption.NOFOLLOW_LINKS) && !o.force)
                    throw new IllegalArgumentException("refusing to overwrite " + p + " (use --force)");
            for (var e : plan.entrySet()) {
                Path p = e.getKey();
                Files.createDirectories(p.getParent());
                Files.deleteIfExists(p);
                Files.writeString(p, wrapper(o, e.getValue(), p, cwd));
                Files.setPosixFilePermissions(p, PosixFilePermissions.fromString("rwxr-xr-x"));
                out.println("wrote " + p);
            }
            return 0;
        } catch (IllegalArgumentException e) {
            err.println("javaw: " + e.getMessage() + "\nTry 'javaw --help' for usage.");
            return 2;
        } catch (IOException e) {
            err.println("javaw: " + e);
            return 1;
        }
    }

    static Opts parse(String[] args) {
        Deque<String> q = new ArrayDeque<>();
        for (String a : args) {
            if (a.contains("\n")) throw new IllegalArgumentException("arguments must not contain newlines");
            int eq = a.indexOf('=');
            if (a.startsWith("--") && eq > 0) { q.add(a.substring(0, eq)); q.add(a.substring(eq + 1)); }
            else q.add(a);
        }
        Opts o = new Opts();
        while (!q.isEmpty()) {
            String a = q.poll();
            switch (a) {
                case "-h", "--help" -> { return null; }
                case "--force" -> o.force = true;
                case "--jar" -> o.inputs.add(new Input(value(q, a), true));
                case "--classpath" -> o.classpath = value(q, a);
                case "--java-home" -> o.javaHome = value(q, a).replaceAll("(.)/+$", "$1");
                case "--jvm-opts" -> o.jvmOpts = value(q, a);
                case "-o", "--output" -> o.output = value(q, a);
                case "--min-java" -> {
                    String v = value(q, a);
                    if (!v.matches("[1-9][0-9]{0,3}")) throw new IllegalArgumentException("--min-java needs a whole number, got: " + v);
                    o.minJava = Integer.parseInt(v);
                }
                case "--" -> { while (!q.isEmpty()) o.inputs.add(new Input(q.poll(), false)); }
                default -> {
                    if (a.startsWith("-")) throw new IllegalArgumentException("unknown option: " + a);
                    o.inputs.add(new Input(a, false));
                }
            }
        }
        return o;
    }

    static String value(Deque<String> q, String opt) {
        if (q.isEmpty()) throw new IllegalArgumentException("option " + opt + " needs a value");
        return q.poll();
    }

    static Map<Path, Input> plan(Opts o, Path cwd) {
        if (o.inputs.isEmpty()) throw new IllegalArgumentException("missing input: give a .java file or --jar FILE");
        Path dir = cwd;
        String name = null;
        if (o.output != null) {
            Path p = cwd.resolve(o.output).normalize();
            if (o.output.endsWith("/") || Files.isDirectory(p)) dir = p;
            else if (o.inputs.size() > 1) throw new IllegalArgumentException("-o must be a directory when several inputs are given");
            else { dir = p.getParent(); name = p.getFileName().toString(); }
        }
        Map<Path, Input> plan = new LinkedHashMap<>();
        for (Input in : o.inputs)
            if (plan.put(dir.resolve(name != null ? name : defaultName(in.path())), in) != null)
                throw new IllegalArgumentException("two inputs would both be written to " + dir.resolve(defaultName(in.path())));
        return plan;
    }

    static String defaultName(String input) {
        String base = Path.of(input).getFileName().toString();
        int dot = base.lastIndexOf('.');
        String k = kebab(dot > 0 ? base.substring(0, dot) : base);
        if (k.isEmpty()) throw new IllegalArgumentException("cannot derive a name from " + input + "; use -o");
        return "run-" + k;
    }

    static String kebab(String s) {
        return s.replaceAll("([a-z0-9])([A-Z])", "$1-$2")      // fooBar -> foo-Bar
                .replaceAll("([A-Z]+)([A-Z][a-z])", "$1-$2")   // HTTPServer -> HTTP-Server
                .replaceAll("[-_.\\s]+", "-")
                .replaceAll("^-|-$", "")
                .toLowerCase(Locale.ROOT);
    }

    static String wrapper(Opts o, Input in, Path target, Path cwd) {
        Path rel = target.getParent().relativize(cwd.resolve(in.path()).normalize());
        String java = o.javaHome == null ? "java" : shq(o.javaHome + "/bin/java");
        StringBuilder s = new StringBuilder("#!/bin/sh\n# Generated by javaw. Regenerate with:\n#   ")
                .append(command(o, in)).append("\n").append(RESOLVE);
        if (o.minJava != null) s.append(MIN_JAVA.formatted(java, o.minJava));
        s.append("exec ").append(java);
        if (o.jvmOpts != null && !o.jvmOpts.isBlank()) s.append(' ').append(o.jvmOpts.strip());
        if (o.classpath != null) s.append(" -cp ").append(shq(o.classpath));
        s.append(in.jar() ? " -jar" : "").append(" \"$dir/").append(rel.toString().replaceAll("([\\\\\"$`])", "\\\\$1"));
        return s.append("\" \"$@\"\n").toString();
    }

    /** The javaw command line that regenerates this one wrapper, shell-quoted. */
    static String command(Opts o, Input in) {
        List<String> c = new ArrayList<>(List.of("javaw"));
        if (o.classpath != null) c.addAll(List.of("--classpath", o.classpath));
        if (o.javaHome != null) c.addAll(List.of("--java-home", o.javaHome));
        if (o.jvmOpts != null) c.addAll(List.of("--jvm-opts", o.jvmOpts));
        if (o.minJava != null) c.addAll(List.of("--min-java", o.minJava.toString()));
        if (o.output != null) c.addAll(List.of("--output", o.output));
        if (o.force) c.add("--force");
        c.add(in.jar() ? "--jar" : in.path().startsWith("-") ? "--" : null);
        c.add(in.path());
        return String.join(" ", c.stream().filter(Objects::nonNull).map(javaw::shq).toList());
    }

    static String shq(String s) {
        return s.matches("[\\w@%+=:,./-]+") ? s : "'" + s.replace("'", "'\\''") + "'";
    }
}
