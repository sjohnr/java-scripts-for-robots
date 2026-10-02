# javaw

Generates a small, relocatable POSIX `sh` launcher script for a Java program.
`javaw` only writes the wrapper; it never builds, bundles, or launches anything.

Written in Java 25+ with a compact source file and module imports (`Javaw.java`, ~150 lines).

## Run it

```sh
java Javaw.java --help                 # run straight from source
./javaw --help                         # or use the wrapper javaw generated for itself

./javaw example/Hello.java             # writes ./run-hello
./run-hello a b                        # Hello [a] [b] (app.version=1)

./javaw --jar app.jar --min-java 21 --jvm-opts "-Xmx512m" -o bin/
./javaw A.java B.java -o tools/ --force
```

| Option | Effect |
|---|---|
| `--jar FILE` | launch with `java -jar` |
| `--classpath PATH` | add `-cp PATH` (recorded as-is) |
| `--java-home DIR` | use `DIR/bin/java` |
| `--jvm-opts "OPTS"` | JVM options before the program |
| `--min-java N` | fail clearly if Java is older than N |
| `-o, --output NAME` | output file, or directory if it ends in `/` or exists |
| `-f, --force` | overwrite existing files |

The default name is `run-` plus the kebab-cased base name: `HTTPServer.java` → `run-http-server`.

## Test

```sh
java test/JavawTest.java
```

The tests compile `Javaw.java`, check naming, options, output paths, errors, and
the header's regenerate command, then run real wrappers around `example/Hello.java`
from another directory, through a relative symlink and a chain of two, with
`JAVA_TOOL_OPTIONS=-Dapp.version=2`, with `CDPATH` pointing at a decoy directory,
from a path starting with `-`, after moving and renaming, under `dash`, and as a jar.
The `--min-java` check is exercised against fake `java` binaries
(`1.8.0_x`, `28-ea`, JAVA_TOOL_OPTIONS noise, garbage, and a missing JVM).
