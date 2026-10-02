# javaw

`javaw` writes a POSIX `sh` wrapper that launches a Java program. The wrapper is
the product: after generation, `javaw` is not needed for anything.

`javaw` is a single JDK 28 compact source file with a shebang
(`--source 28 --enable-preview --add-modules jdk.incubator.json`). There is no
build step. It needs JDK 28 as `java` on the `PATH`.

```sh
./javaw --help
./javaw Tool.java                  # writes ./run-tool (builds .run-tool/run-tool.jar on first run)
./javaw --aot --min-java 25 Tool.java
./javaw -r '*.json' -r '*.csv' Tool.java   # copy matching files into the jar for getResource
./javaw --jar lib/tool.jar -o bin/ # writes bin/run-tool
./javaw --profile fast-start A.java B.java -o bin/
./javaw javaw                      # dogfood: writes ./run-javaw, a wrapper for javaw itself
```

Files a wrapper creates next to itself:

| Path | Contents |
|---|---|
| `.NAME/` | per-wrapper state: `NAME.jar`, `NAME.aot`, native binary `NAME`, stamps, the `deps.ok` marker |
| `lib/` | jars downloaded from Maven Central, shared by wrappers, in Maven layout |

Both directories contain a `.gitignore` with `*`.

## Tests

```sh
tests/javaw_test            # all tests (about 40 s)
tests/javaw_test AOT        # tests whose name contains "AOT"
GRAALVM_HOME=/path/to/graalvm tests/javaw_test native   # include the real native-image build
```

The tests run each wrapper under every installed POSIX shell (`sh`, `dash`, `ksh`,
`bash`): from another directory, through a relative symlink and a chain of two,
from a path starting with `-`, with `JAVA_TOOL_OPTIONS=-Dapp.version=2`, and with
`CDPATH` exported and pointing at a decoy directory with the same relative name.
The `//DEPS` test needs `jbang` and access to Maven Central. Without them, it is skipped.
