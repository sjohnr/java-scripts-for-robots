Create the tool described in the requirements below, as the most modern example of a Java tool possible. Use the latest and greatest Java, preview features included. Use these options:

| Option | Detail |
|---|---|
| JDK | JDK 28 (the installed `java` is a 28 early-access build) |
| Preview features | Enable preview features (`--enable-preview` with `--source 28`, both on the shebang line) |
| Launch | A single source file named `javaw` (no `.java` extension) whose first line is a shebang: `#!/usr/bin/env -S java --source 28 --enable-preview ...` (JEP 330). Make it executable so it runs as `./javaw`, never as `java javaw.java`. No build step, no build files. |
| Compact source file | Write the shebang file as a compact source file: no class declaration, top-level `void main` (JEP 512) |
| Console output | Use `IO.println` and friends instead of `System.out` (JEP 512) |
| Module imports | `import module java.base;` instead of individual imports (JEP 511) |
| Simple JSON API | `import module jdk.incubator.json;` with `--add-modules jdk.incubator.json` on the shebang line (JEP 540, incubating). Make it available to the program; use it only where it fits naturally. |
| Other language features | Use any other final or preview language and library feature in JDK 28 that fits naturally: records, sealed interfaces, pattern matching for switch, record patterns, unnamed variables, text blocks, switch expressions, and so on |
| Dependencies | The JDK should be enough. If a dependency is truly needed, declare it with JBang (`//DEPS`) and replace the `java` shebang with JBang's (`///usr/bin/env jbang "$0" "$@" ; exit $?`), so it still runs directly as an executable. |
| JBang directives | If JBang is used, set the Java version and preview options with its directives instead of command-line flags |
