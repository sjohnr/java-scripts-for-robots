# javaw: Version 1 requirements

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

## Wrapper rules

| Rule | Detail |
|---|---|
| POSIX sh | Shebang `#!/bin/sh`. No bash-only features (no arrays, no `[[ ]]`, no process substitution). |
| Relocatable | The wrapper resolves its own directory and locates the program relative to it, never relative to the wrapper's name or the current directory, so moving or renaming the wrapper together with its siblings still works. It first resolves symlinks to itself (chains and relative targets) to find the real file's directory, using only POSIX sh and tools on stock macOS and Linux (no `readlink -f`, `realpath`, or GNU-only flags), and is immune to `CDPATH` and to paths starting with `-`. |
| Minimal | The wrapper contains only what the chosen options require. No unused options and no dependency resolution logic. Symlink and directory resolution are required. |
| Platform | Windows is out of scope |

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
| Size | Keep it small enough to read on a conference slide or two |
| Tests | Include automated tests that use assertions and run without extra setup |
| Verification | Verify that generated wrappers run a small example program correctly when invoked from a different directory, through a symlink (relative, and a chain of two), with `JAVA_TOOL_OPTIONS=-Dapp.version=2`, and with `CDPATH` exported while the wrapper is invoked by a relative path that matches a `CDPATH` entry. Show how to run the tool. |
