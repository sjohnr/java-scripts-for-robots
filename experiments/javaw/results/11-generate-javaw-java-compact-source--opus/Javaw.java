import module java.base;

static final String HELP = """
    Usage: javaw [options] Program.java...
           javaw [options] --jar app.jar
    Writes a POSIX sh launcher script for each Java program.

    Options:
      --jar FILE          launch FILE with java -jar (repeatable)
      --classpath PATH    add -cp PATH to the launch command (recorded as-is)
      --java-home DIR     use DIR/bin/java instead of java from the PATH
      --jvm-opts "OPTS"   JVM options placed before the program (shell syntax)
      --min-java N        fail with a clear error if Java is older than N
      -o, --output NAME   output file, or directory if it ends in / or exists
      -f, --force         overwrite existing files
      -h, --help          show this help
    """;

static final String RESOLVE = """
    self=$0
    case $self in /*) ;; *) self=./$self ;; esac
    while [ -h "$self" ]; do
      link=$(readlink "$self") || exit 1
      case $link in /*) self=$link ;; *) self=${self%/*}/$link ;; esac
    done
    dir=$(CDPATH= cd -P -- "${self%/*}/" && pwd -P) || exit 1
    """;

static final String CHECK = """
    v=$(%1$s -version 2>&1 | sed -n 's/^[A-Za-z]* version "\\([^"]*\\)".*/\\1/p' | head -n 1)
    case $v in 1.*) n=${v#1.} ;; *) n=$v ;; esac
    n=${n%%%%[!0-9]*}
    if [ -z "$n" ] || [ "$n" -lt %2$d ]; then
      echo "error: Java %2$d or newer is required, but %3$s reports version: ${v:-unknown}" >&2
      exit 1
    fi
    """;

record Input(Path file, boolean jar) {}

String classpath, javaHome, jvmOpts, output;
Integer minJava;
boolean force;

void main(String[] args) {
    try {
        run(args);
    } catch (IllegalArgumentException e) {
        System.err.println("javaw: " + e.getMessage() + "\nTry 'javaw --help'.");
        System.exit(2);
    } catch (IOException e) {
        System.err.println("javaw: " + e.getMessage());
        System.exit(1);
    }
}

void run(String[] args) throws IOException {
    var inputs = new ArrayList<Input>();
    boolean options = true;
    for (int i = 0; i < args.length; i++) {
        String a = args[i];
        if (a.contains("\n")) throw new IllegalArgumentException("arguments must not contain newlines");
        if (!options || !a.startsWith("-")) { inputs.add(new Input(Path.of(a), false)); continue; }
        switch (a) {
            case "--" -> options = false;
            case "-h", "--help" -> { IO.print(HELP); return; }
            case "-f", "--force" -> force = true;
            case "--jar" -> inputs.add(new Input(Path.of(value(args, ++i, a)), true));
            case "--classpath" -> classpath = value(args, ++i, a);
            case "--java-home" -> javaHome = value(args, ++i, a);
            case "--jvm-opts" -> jvmOpts = value(args, ++i, a);
            case "-o", "--output" -> output = value(args, ++i, a);
            case "--min-java" -> {
                String n = value(args, ++i, a);
                if (!n.matches("[1-9][0-9]{0,3}")) throw new IllegalArgumentException("--min-java expects a version number, got: " + n);
                minJava = Integer.parseInt(n);
            }
            default -> throw new IllegalArgumentException("unknown option: " + a);
        }
    }
    if (inputs.isEmpty()) throw new IllegalArgumentException("missing input: give a .java file or --jar FILE");

    Path outDir = Path.of(""), outFile = null;
    if (output != null && (output.endsWith("/") || Files.isDirectory(Path.of(output)))) outDir = Path.of(output);
    else if (output != null && inputs.size() > 1) throw new IllegalArgumentException("-o with several inputs must be a directory (end it with /)");
    else if (output != null) outFile = Path.of(output);

    var targets = new LinkedHashMap<Path, Input>();
    for (var in : inputs) {
        Path target = (outFile != null ? outFile : outDir.resolve(defaultName(in.file()))).toAbsolutePath().normalize();
        if (targets.put(target, in) != null) throw new IOException("two inputs would both write " + target);
        if (Files.exists(target, LinkOption.NOFOLLOW_LINKS) && !force) throw new IOException(target + " already exists (use --force to overwrite)");
        if (!Files.exists(in.file())) System.err.println("javaw: warning: " + in.file() + " does not exist (yet)");
    }
    String command = Arrays.stream(args).map(this::quote).collect(Collectors.joining(" "));
    for (var e : targets.entrySet()) {
        Path target = e.getKey();
        Files.createDirectories(target.getParent());
        Files.writeString(target, script(target, e.getValue(), command));
        Files.setPosixFilePermissions(target, PosixFilePermissions.fromString("rwxr-xr-x"));
        IO.println("wrote " + target);
    }
}

String value(String[] args, int i, String option) {
    if (i >= args.length) throw new IllegalArgumentException(option + " requires a value");
    return args[i];
}

String script(Path target, Input in, String command) {
    Path program = real(target.getParent()).relativize(real(in.file().toAbsolutePath().getParent()).resolve(in.file().getFileName()));
    String java = javaHome == null ? "java" : quote(javaHome + "/bin/java");
    var s = new StringBuilder("#!/bin/sh\n# Generated by javaw. Regenerate with:\n#   javaw " + command + "\n");
    s.append(RESOLVE);
    if (minJava != null) s.append(CHECK.formatted(java, minJava, escape(javaHome == null ? "java" : javaHome + "/bin/java")));
    s.append("exec ").append(java);
    if (jvmOpts != null) s.append(' ').append(jvmOpts);
    if (classpath != null) s.append(" -cp ").append(quote(classpath));
    if (in.jar()) s.append(" -jar");
    return s.append(" \"$dir/").append(escape(program.toString())).append("\" \"$@\"\n").toString();
}

/** Resolves symlinks in the existing part of a path, as the wrapper's {@code pwd -P} will. */
Path real(Path p) {
    try { return p.toRealPath(); } catch (IOException e) { return real(p.getParent()).resolve(p.getFileName()); }
}

String defaultName(Path file) {
    String name = file.getFileName().toString();
    int dot = name.lastIndexOf('.');
    return "run-" + kebab(dot > 0 ? name.substring(0, dot) : name);
}

String kebab(String s) {
    return s.replaceAll("([a-z0-9])([A-Z])", "$1-$2")
            .replaceAll("([A-Z]+)([A-Z][a-z])", "$1-$2")
            .replaceAll("[-_.\\s]+", "-")
            .replaceAll("^-|-$", "")
            .toLowerCase(Locale.ROOT);
}

/** Single-quotes a word for sh unless it is plainly safe. */
String quote(String s) {
    return s.matches("[\\w@%+=:,./-]+") ? s : "'" + s.replace("'", "'\\''") + "'";
}

/** Escapes text for use inside a double-quoted sh string. */
String escape(String s) {
    return s.replaceAll("([$`\"\\\\])", "\\\\$1");
}
