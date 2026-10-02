# JBang Examples

Single-file Java scripts run with [JBang](https://www.jbang.dev). Dependencies are declared inline with
`//DEPS group:artifact:version` comments at the top of each file; JBang resolves them from Maven Central and
caches the compiled jar, so there's no build file.

## Run

```sh
# Run directly (the first line is a shebang for JBang)
./example3_argparse.java -P 8080 --param1 hello --param2 42 --enable-featureX

# Or via jbang
jbang example3_argparse.java --help
```

## Edit in an IDE

```sh
# Generates a temporary project with dependencies on the classpath and opens it
jbang edit --open=idea example3_argparse.java
```

## Examples

Each script is a port of the matching example in `examples/src/main/java`. Examples 5 and 6 bundle their
resources (`example5.csv`, `example6.json`) from this directory into the jar with `//FILES`.

| File                     | Dependencies                     | Description                                                 |
|--------------------------|----------------------------------|-------------------------------------------------------------|
| `example1_assert.java`   | `org.assertj:assertj-core`       | `assert` statements replaced with AssertJ (no JUnit needed) |
| `example2_args.java`     | none                             | Unchanged, only the JBang header added                      |
| `example3_argparse.java` | `info.picocli:picocli`           | Hand-rolled arg parsing replaced with picocli               |
| `example4_glob.java`     | none                             | Full glob syntax via the JDK's `PathMatcher`                |
| `example5_csv.java`      | `org.apache.commons:commons-csv` | `String.split` replaced with Apache Commons CSV             |
| `example6_json.java`     | `com.grack:nanojson`             | `jdk.incubator.json` replaced with nanojson                 |
