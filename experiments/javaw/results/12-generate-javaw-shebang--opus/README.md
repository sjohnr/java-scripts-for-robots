# javaw

Generates a relocatable POSIX `sh` launcher for a Java program. `javaw` is a single
Java source file with a shebang (`#!/usr/bin/env -S java --source 25`), so it needs a JDK 25+ and no build.

```sh
./javaw --help
./javaw Tool.java                       # -> ./run-tool: exec java "$dir/Tool.java" "$@"
./javaw --jar build/tool.jar -o bin/    # -> bin/run-tool: exec java -jar "$dir/../build/tool.jar" "$@"
./javaw --min-java 17 --jvm-opts "-Xmx512m" --java-home /opt/jdk-21 Tool.java
./javaw A.java B.java -o bin/           # one wrapper per input
```

The program path is stored relative to the wrapper's real directory (after symlinks are resolved), so
you can move the wrapper and program together, or symlink to the wrapper from anywhere.

## Tests

```sh
./test.sh          # or: dash ./test.sh
```

The tests build a small `Hello` program and check the generated wrappers: default names, overwrite
protection, minimal output, and launching from another directory, through a relative symlink and a
chain of two, with `JAVA_TOOL_OPTIONS=-Dapp.version=2`, with `CDPATH` exported, from a path starting
with `-`, and after moving or renaming. They also cover jar, classpath, java-home, `--min-java` (using
fake JDKs for `1.8.0_x`, `28-ea`, garbage, and missing `java`), and regenerating from the header.
