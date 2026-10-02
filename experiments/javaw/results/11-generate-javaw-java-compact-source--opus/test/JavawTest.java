import module java.base;

// Run from the project root: java test/JavawTest.java

record Result(int code, String out, String err) {}

Path root = Path.of("").toAbsolutePath(), tmp, classes;
int checks;

void main() throws Exception {
    tmp = Files.createTempDirectory("javaw-test").toRealPath();
    classes = tmp.resolve("classes");
    check(run(root, Map.of(), "javac", "-d", classes.toString(), "Javaw.java").code() == 0, "Javaw.java compiles");

    kebabNames();
    launchCommands();
    headerRegenerates();
    outputPaths();
    cliErrors();
    minJavaCheck();
    wrappersRunExample();
    IO.println("OK: " + checks + " checks passed");
}

void kebabNames() throws Exception {
    var cases = Map.of("Tool.java", "run-tool", "FooBar.java", "run-foo-bar", "HTTPServer.java", "run-http-server",
            "example1_assert.java", "run-example1-assert", "Example1.java", "run-example1", "Foo__Bar.java", "run-foo-bar",
            "My Tool.java", "run-my-tool", "a.b.Tool.java", "run-a-b-tool");
    for (var e : cases.entrySet()) {
        Path dir = dir();
        ok(javaw(dir, e.getKey()));
        check(Files.exists(dir.resolve(e.getValue())), e.getKey() + " -> " + e.getValue());
    }
    for (var jar : List.of("my-tool.jar", "tool.jar")) {
        Path dir = dir();
        ok(javaw(dir, "--jar", jar));
        check(Files.exists(dir.resolve(jar.equals("tool.jar") ? "run-tool" : "run-my-tool")), jar + " default name");
    }
}

void launchCommands() throws Exception {
    Path dir = dir();
    ok(javaw(dir, "Tool.java"));
    String s = Files.readString(dir.resolve("run-tool"));
    check(s.startsWith("#!/bin/sh\n"), "POSIX shebang");
    check(s.endsWith("exec java \"$dir/Tool.java\" \"$@\"\n"), "source launch line");
    check(!s.contains("-cp") && !s.contains("version"), "minimal wrapper has no unused options");
    check(!s.contains("[[") && !s.contains("<(") && !s.contains("readlink -f") && !s.contains("realpath"), "no bash-isms");
    check(Files.getPosixFilePermissions(dir.resolve("run-tool")).contains(PosixFilePermission.OWNER_EXECUTE), "executable");

    ok(javaw(dir, "--jar", "tool.jar", "--classpath", "lib/*", "--java-home", "/opt/jdk", "--jvm-opts", "-Xmx1g -Dx=1", "--force"));
    eq("exec /opt/jdk/bin/java -Xmx1g -Dx=1 -cp 'lib/*' -jar \"$dir/tool.jar\" \"$@\"", lastLine(dir.resolve("run-tool")), "all options");

    ok(javaw(dir, "src/Main.java", "-o", "bin/"));
    eq("exec java \"$dir/../src/Main.java\" \"$@\"", lastLine(dir.resolve("bin/run-main")), "program located relative to wrapper");
}

void headerRegenerates() throws Exception {
    Path dir = dir();
    ok(javaw(dir, "--jar", "it's.jar", "--jvm-opts", "-Da='x y' -Db=$HOME", "--min-java", "11", "-o", "out"));
    String before = Files.readString(dir.resolve("out"));
    String header = before.lines().filter(l -> l.startsWith("#   javaw ")).findFirst().orElseThrow();
    String java = "java -cp " + classes + " Javaw";
    ok(run(dir, Map.of(), "sh", "-c", "rm out && " + header.replaceFirst("#   javaw", java)));
    eq(before, Files.readString(dir.resolve("out")), "header command regenerates identical wrapper");
}

void outputPaths() throws Exception {
    Path dir = dir();
    ok(javaw(dir, "A.java", "-o", "custom"));
    check(Files.exists(dir.resolve("custom")), "-o NAME");
    ok(javaw(dir, "A.java", "-o", "sub/dir/name"));
    check(Files.exists(dir.resolve("sub/dir/name")), "-o PATH sets the directory");
    ok(javaw(dir, "A.java", "BeeTool.java", "-o", "out/"));
    check(Files.exists(dir.resolve("out/run-a")) && Files.exists(dir.resolve("out/run-bee-tool")), "-o DIR/ with several inputs");
    ok(javaw(dir, "C.java", "-o", "out"));
    check(Files.exists(dir.resolve("out/run-c")), "-o existing directory");
    var r = javaw(dir, "A.java", "B.java", "-o", "single");
    check(r.code() == 2 && r.err().contains("must be a directory"), "several inputs need a directory");

    r = javaw(dir, "A.java", "-o", "custom");
    check(r.code() == 1 && r.err().contains("already exists"), "refuses to overwrite");
    Files.writeString(dir.resolve("custom"), "keep");
    javaw(dir, "A.java", "-o", "custom");
    eq("keep", Files.readString(dir.resolve("custom")), "existing file untouched");
    ok(javaw(dir, "A.java", "-o", "custom", "--force"));
    check(Files.readString(dir.resolve("custom")).startsWith("#!/bin/sh"), "--force overwrites");
    r = javaw(dir, "Tool.java", "--jar", "tool.jar");
    check(r.code() == 1 && r.err().contains("both write"), "clashing default names rejected");
}

void cliErrors() throws Exception {
    Path dir = dir();
    var r = javaw(dir, "--help");
    check(r.code() == 0 && r.out().contains("Usage: javaw"), "--help");
    r = javaw(dir);
    check(r.code() == 2 && r.err().contains("missing input"), "missing input");
    r = javaw(dir, "--bogus", "A.java");
    check(r.code() == 2 && r.err().contains("unknown option: --bogus"), "unknown option");
    r = javaw(dir, "A.java", "--min-java");
    check(r.code() == 2 && r.err().contains("--min-java requires a value"), "missing option value");
    r = javaw(dir, "A.java", "--min-java", "eleven");
    check(r.code() == 2 && r.err().contains("expects a version number"), "bad --min-java");
}

void minJavaCheck() throws Exception {
    record Case(String versionOutput, int min, boolean passes) {}
    var cases = List.of(
            new Case("openjdk version \"28-ea\" 2027-03-23", 25, true),
            new Case("openjdk version \"17.0.2\" 2022-01-18", 17, true),
            new Case("openjdk version \"17.0.2\" 2022-01-18", 21, false),
            new Case("java version \"1.8.0_392\"", 8, true),
            new Case("java version \"1.8.0_392\"", 11, false),
            new Case("Picked up JAVA_TOOL_OPTIONS: -Dbanner=java version \"99\"\njava version \"1.8.0_392\"", 11, false),
            new Case("Picked up JAVA_TOOL_OPTIONS: -Dapp.version=2\nopenjdk version \"21\" 2023-09-19", 21, true),
            new Case("something unexpected", 11, false));
    for (var c : cases) {
        Path dir = dir(), home = dir.resolve("jdk");
        Files.createDirectories(home.resolve("bin"));
        Files.writeString(home.resolve("bin/java"), "#!/bin/sh\ncat >&2 <<'EOF'\n" + c.versionOutput() + "\nEOF\necho launched \"$@\"\n");
        Files.setPosixFilePermissions(home.resolve("bin/java"), PosixFilePermissions.fromString("rwxr-xr-x"));
        ok(javaw(dir, "App.java", "--java-home", home.toString(), "--min-java", "" + c.min()));
        var r = run(root, Map.of(), dir.resolve("run-app").toString(), "x");
        String what = c.versionOutput() + " vs " + c.min();
        if (c.passes()) eq("launched " + dir.resolve("App.java") + " x", r.out().strip(), what);
        else check(r.code() == 1 && r.out().isEmpty() && r.err().strip().startsWith("error: Java " + c.min() + " or newer is required")
                && r.err().strip().lines().count() == 1, what + " fails clearly: " + r.err());
    }
    Path dir = dir();
    ok(javaw(dir, "App.java", "--java-home", dir.resolve("missing").toString(), "--min-java", "11"));
    var r = run(root, Map.of(), dir.resolve("run-app").toString());
    check(r.code() == 1 && r.err().strip().equals("error: Java 11 or newer is required, but " + dir.resolve("missing/bin/java")
            + " reports version: unknown"), "missing java reported clearly: " + r.err());
}

void wrappersRunExample() throws Exception {
    Path app = Files.createDirectories(tmp.resolve("app"));
    Files.copy(root.resolve("example/Hello.java"), app.resolve("Hello.java"));
    ok(javaw(app, "Hello.java", "--min-java", "17"));
    Path wrapper = app.resolve("run-hello"), elsewhere = dir();
    String expected = "Hello [a b] [c] (app.version=1)";

    eq(expected, run(elsewhere, Map.of(), wrapper.toString(), "a b", "c").out().strip(), "runs from a different directory");
    eq(expected, run(tmp, Map.of(), "app/run-hello", "a b", "c").out().strip(), "runs via relative path");

    Path links = Files.createDirectories(tmp.resolve("links")), bin = Files.createDirectories(tmp.resolve("bin"));
    Files.createSymbolicLink(links.resolve("hello"), Path.of("../app/run-hello"));
    Files.createSymbolicLink(bin.resolve("hi"), Path.of("../links/hello"));
    eq(expected, run(elsewhere, Map.of(), links.resolve("hello").toString(), "a b", "c").out().strip(), "relative symlink");
    eq(expected, run(elsewhere, Map.of(), bin.resolve("hi").toString(), "a b", "c").out().strip(), "chain of two symlinks");
    eq(expected, run(tmp, Map.of(), "bin/hi", "a b", "c").out().strip(), "symlink chain via relative path");
    if (Files.exists(Path.of("/bin/dash")))
        eq(expected, run(elsewhere, Map.of(), "/bin/dash", bin.resolve("hi").toString(), "a b", "c").out().strip(), "strict POSIX dash");

    var r = run(elsewhere, Map.of("JAVA_TOOL_OPTIONS", "-Dapp.version=2"), wrapper.toString());
    eq("Hello  (app.version=2)", r.out().strip(), "JAVA_TOOL_OPTIONS passes the version check and reaches the program");

    Path decoy = Files.createDirectories(tmp.resolve("decoy/app"));
    Files.writeString(decoy.resolve("Hello.java"), "void main() { IO.println(\"decoy\"); }");
    eq(expected, run(tmp, Map.of("CDPATH", tmp.resolve("decoy").toString()), "app/run-hello", "a b", "c").out().strip(), "immune to CDPATH");

    Path dash = Files.createDirectories(tmp.resolve("-dash"));
    Files.copy(app.resolve("Hello.java"), dash.resolve("Hello.java"));
    Files.copy(wrapper, dash.resolve("run-hello"));
    eq(expected, run(tmp, Map.of(), "sh", "--", "-dash/run-hello", "a b", "c").out().strip(), "path starting with -");

    Path moved = Files.createDirectories(tmp.resolve("moved"));
    Files.move(app.resolve("Hello.java"), moved.resolve("Hello.java"));
    Files.move(wrapper, moved.resolve("renamed"));
    eq(expected, run(elsewhere, Map.of(), moved.resolve("renamed").toString(), "a b", "c").out().strip(), "moved and renamed");

    Path jarDir = dir();
    ok(run(root, Map.of(), "javac", "-d", jarDir.resolve("classes").toString(), "example/Hello.java"));
    ok(run(jarDir, Map.of(), "jar", "--create", "--file", "hello.jar", "--main-class", "Hello", "-C", "classes", "."));
    ok(javaw(jarDir, "--jar", "hello.jar", "--jvm-opts", "-Dapp.version=3"));
    eq("Hello [a b] [c] (app.version=3)", run(elsewhere, Map.of(), jarDir.resolve("run-hello").toString(), "a b", "c").out().strip(), "jar launch");
}

// --- helpers ---

Result javaw(Path dir, String... args) throws Exception {
    var cmd = new ArrayList<>(List.of("java", "-cp", classes.toString(), "Javaw"));
    cmd.addAll(List.of(args));
    return run(dir, Map.of(), cmd.toArray(String[]::new));
}

Result run(Path dir, Map<String, String> env, String... cmd) throws Exception {
    var pb = new ProcessBuilder(cmd).directory(dir.toFile());
    pb.environment().remove("JAVA_TOOL_OPTIONS");
    pb.environment().remove("CDPATH");
    pb.environment().putAll(env);
    Path err = Files.createTempFile(tmp, "err", ".txt");
    var p = pb.redirectError(err.toFile()).start();
    String out = new String(p.getInputStream().readAllBytes());
    return new Result(p.waitFor(), out, Files.readString(err));
}

int dirs;
Path dir() throws IOException { return Files.createDirectories(tmp.resolve("t" + dirs++)); }

String lastLine(Path p) throws IOException { return Files.readAllLines(p).getLast(); }

void ok(Result r) { check(r.code() == 0, "command failed: " + r); }

void eq(Object expected, Object actual, String what) {
    check(Objects.equals(expected, actual), what + "\n  expected: " + expected + "\n  actual:   " + actual);
}

void check(boolean condition, String what) {
    if (!condition) throw new AssertionError(what);
    checks++;
}
