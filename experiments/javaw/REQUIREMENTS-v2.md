# javaw: Version 2 requirements

Build a command-line tool called `javaw`. It generates a launcher shell script for a Java program. The generated wrapper is the product. `javaw` itself is never in the launch path: it does not build, bundle, or launch anything.

## Features

| Feature | Behavior |
|---|---|
| Source launch | `javaw Tool.java` writes an executable script that runs `exec java "$dir/Tool.java" "$@"`, where `$dir` is the directory of the wrapper's real file (after resolving symlinks) |
| Jar launch | `javaw --jar tool.jar` writes a script that runs `exec java -jar "$dir/tool.jar" "$@"`, where `$dir` is the directory of the wrapper's real file (after resolving symlinks) |
| Classpath | `--classpath PATH` records the given path as-is and adds `-cp` to the launch command |
| Java home | `--java-home DIR` makes the wrapper use `DIR/bin/java` instead of `java` from the PATH |
| JVM options | `--jvm-opts "OPTS"` adds the given options before the program in the launch command |
| Minimum Java version | `--min-java N` makes the wrapper check the installed Java version first and fail with a clear error message if it is older than N. The check runs the same `java` the wrapper launches (`DIR/bin/java` with `--java-home`), parses only the `version "..."` line, treats `1.8.0_x` as 8 and `28-ea` as 28, ignores any other line the JVM prints (such as JAVA_TOOL_OPTIONS notices), and reports a missing or unparseable version with the same clear error, not a shell error. |
| Output name | Default name is `run-` plus the input's base name (extension removed) converted to kebab-case, written to the current directory. `-o NAME` / `--output NAME` overrides it (a path sets the directory too). |
| Several inputs | `javaw A.java B.java` writes one wrapper per input, each with its default name. `-o` with several inputs must be a directory. |
| Output directory | If `-o` ends in `/` or names an existing directory, wrappers are written there using their default names |
| Overwriting | Refuse to overwrite an existing file unless `--force` is given |
| Permissions | Written with the executable bit set |
| Header comment | Each wrapper starts with a comment recording the exact `javaw` options used, so it can be regenerated. The recorded command is shell-quoted so it can be copied and run verbatim. |
| CLI | Argument parsing, `--help` text, and clear errors for unknown options or missing input |

## Version 2 features

Everything above still applies. Version 2 adds the following. Priority: Must, Nice, or Stretch (implement in that order).

| Priority | Feature | Behavior |
|---|---|---|
| Must | Build on first launch | For a `.java` source input, the wrapper compiles and packages a jar on its first run using only the JDK tools (`javac`, `jar`), then launches from the jar. Dependencies are not bundled; they stay on the classpath. Rebuild when the sources or the JDK change (compare modification times or a content hash recorded in a stamp), because the class files and any `--release` depend on the JDK that compiled them. |
| Must | Resources | `-r PATTERN` / `--resource PATTERN` (repeatable) copies files under the source root that match the glob into the built jar, so `getResource` finds them as it does under the source launcher. A pattern without `/` matches file names at any depth; one with `/` matches the path from the source root. Hidden files and directories and the wrappers' `lib/` are never copied. Changing, adding, or removing a matching file triggers a rebuild (and so a new AOT cache). The source root itself must not go on the runtime classpath, because the AOT cache rejects non-empty directories. Without `-r`, the wrapper contains no resource handling. `-r` with `--no-build` or a jar input is an error. |
| Must | AOT cache mode | The wrapper creates an AOT cache on demand on the first run and uses it on later runs. It stores a stamp derived from its own baked contents (including checksums) plus the JDK's identity. Rebuilding the jar invalidates the cache. On a mismatch it rebuilds the cache; it never silently falls back to running without a valid cache. |
| Must | Safe cache handling | Cache and jar writes are atomic (write a temporary file, then move it into place), so concurrent first runs cannot corrupt anything |
| Must | Dogfooding | `javaw javaw.jar` (or the equivalent for your packaging) generates the wrapper that launches `javaw` itself |
| Nice | Dependency adapters | Read JBang `//DEPS` lines from the source. Resolve them, including transitive dependencies, at generation time by delegating to existing tools (JBang or Maven) and bake the finished flat list into the wrapper as one download line per jar with a pinned SHA-256. The wrapper does no resolution. |
| Nice | Wrapper-side fetch | For each missing jar the wrapper runs `curl` over HTTPS from Maven Central, verifies the SHA-256, writes to a temporary file, then moves it into `lib/` next to the wrapper (git-ignored). A completion marker file gives a fast path on later runs. If `curl` is missing, fail with a clear message naming the tool. |
| Nice | Profiles | `javaw --profile NAME` loads opinionated generation options. Suggested profiles: `fast-start`, `small-disk`, `max-perf`, `portable`. Profiles configure `javaw` at generation time, not the wrapper. They may be downloaded from GitHub (pinned to a commit) and cached for offline use. The wrapper never downloads profiles. |
| Stretch | Native mode | `--native` builds with GraalVM `native-image` on first use if it is installed, caches the result, and falls back to the jar if it is not. The native image must behave like the jar: resources from `-r` are registered with `native-image` (for example a `resource-config.json` in the jar) so `getResource` finds them, and assertion options from `--jvm-opts` (`-ea`, `-da`, `-esa`, `-dsa`) are passed to `native-image`, because assertions are fixed at image build time. |

## Version 2 rules

| Rule | Detail |
|---|---|
| Downloads | The only download a wrapper may do is individual jars from Maven Central: HTTPS only, pinned SHA-256, one generated line per jar. Private repositories are out of scope. |
| Self-contained checkout | Downloaded jars live in a `lib/` directory next to the wrapper, so the checkout is self-contained. Nothing else to install beyond the JDK. |
| No new dependency format | Existing conventions are read and turned into a flat classpath |
| No bundling | No fat jar and no bundle verb. The tool stays single-purpose. |
| Generator out of the loop | After generation, `javaw` is not needed to launch anything |
| Offline first run | Fails with a clear error |
| Symlinks | Required. See the Relocatable wrapper rule. |

## Files a wrapper creates next to itself

Several wrappers may share a directory, so these must not collide:

| Artifact | Sharing rule |
|---|---|
| `lib/` (downloaded jars) | Shared. Jars are keyed by their coordinates and checksum, so wrappers with common dependencies reuse them. |
| Built jar | Per wrapper, named from the wrapper (for example `.NAME/NAME.jar`) |
| AOT cache | Per wrapper, named from the wrapper, so one wrapper's rebuild never invalidates another's cache |
| Native binary | Per wrapper, same naming rule |

## Wrapper rules

| Rule | Detail |
|---|---|
| POSIX sh | Shebang `#!/bin/sh`. No bash-only features (no arrays, no `[[ ]]`, no process substitution). |
| Relocatable | The wrapper resolves its own directory and locates the program relative to it, never relative to the wrapper's name or the current directory, so moving or renaming the wrapper together with its siblings still works. It first resolves symlinks to itself (chains and relative targets) to find the real file's directory, using only POSIX sh and tools on stock macOS and Linux (no `readlink -f`, `realpath`, or GNU-only flags), and is immune to `CDPATH` and to paths starting with `-`. |
| Hot path | Once built, a wrapper's only JVM start is the final exec. Version checks and JDK fingerprinting happen only when building, or use cheap file metadata. |
| Minimal | The wrapper contains only what the chosen options require. No unused options and no dependency resolution logic. Symlink and directory resolution are required. |
| Platform | Windows is out of scope. Private repositories are out of scope. |

## Kebab-case rules for the default name

| Rule | Example |
|---|---|
| Split at a lowercase-to-uppercase boundary | `FooBar` becomes `foo-bar` |
| Split an acronym from the following word | `HTTPServer` becomes `http-server` |
| Treat `_`, `.`, and spaces as separators | `example1_assert` becomes `example1-assert` |
| Keep digits attached to the preceding word | `Example1` becomes `example1` |
| Collapse repeated separators and lowercase everything | `Foo__Bar` becomes `foo-bar` |
| Already-kebab input is unchanged apart from the prefix | `my-tool.jar` becomes `run-my-tool` |

`Tool.java` and `tool.jar` both become `run-tool`.

## Quality

| Requirement | Detail |
|---|---|
| Size | Keep the generator readable and well organized. No fixed size target, but do not add features beyond this document. |
| Tests | Include automated tests that use assertions and run without extra setup |
| Verification | Verify that generated wrappers run a small example program correctly when invoked from a different directory, through a symlink (relative, and a chain of two), with `JAVA_TOOL_OPTIONS=-Dapp.version=2`, and with `CDPATH` exported while the wrapper is invoked by a relative path that matches a `CDPATH` entry. Also verify that a program reading a classpath resource (for example `getResourceAsStream("/data.json")`) prints the same output from its wrapper (built jar, with and without an AOT cache, and the native image when `native-image` is installed) as from `java Program.java`, and that with `--jvm-opts -ea` a failing `assert` fails the same way in each. Show how to run the tool. |
