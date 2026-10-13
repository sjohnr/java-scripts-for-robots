# Meet `javaw`

A tool for generating Java wrapper scripts.

<div v-click class="pt-3">

- Designed for AI use (saves tokens, more accurate, etc.)
- Has opinions I don't need to teach my coding assistant
- Adds performance optimization, dependencies, and on-demand builds (without a build tool)
- Just a generated script that can be version-controlled alongside my Java program

</div>

<!--
So let's see what we can do today to improve things today.

- `javaw` is a tool for generating Java wrapper scripts [click]
- It has these benefits:
-->

---

# Requirements

Two versions of the requirements

<div class="grid grid-cols-2 gap-6 requirements">
<div v-click>

**Version 1: Generate a launcher**

| Feature | Description |
|---|---|
| Source launch | `javaw Tool.java` → `exec java Tool.java` |
| Jar launch | `--jar tool.jar` |
| Launch options | `--classpath`, `--java-home`, `--jvm-opts` |
| Version check | `--min-java N` fails clearly |
| Output | multiple inputs, `-o <file-name>`, `--force` |
| Wrapper | POSIX `sh`, relocatable, follows symlinks |

</div>
<div v-click>

**Version 2: Build without a tool**

| Feature | Description |
|---|---|
| Resources | `-r PATTERN` copies files into the jar |
| Build on first launch | Compile and jar with `javac`, `jar` |
| Dependencies | JBang `//DEPS` baked in, fetched with pinned SHA-256 |
| AOT cache | Created on demand, atomic writes |
| Profiles | `portable`, `small-disk`, `fast-start`, `max-perf` |
| Native mode | `--native` via GraalVM |

</div>
</div>

<style>
.requirements > div { min-width: 0; }
.requirements table { font-size: 0.7rem; }
.requirements p { margin: 0 0 0.4rem; }
</style>

<!--
In this experiment, I came up with two versions of the requirements.

- Version 1, with basic features for a generated wrapper script. For example:
  - Baked in jvm options for enabling assertions by default
  - A minimum java version check for distributing Java programs on laptops [click]
- Version 2, with advanced features, such as
  - Dependency downloading without build tools or dependency resolution
  - On demand AOT cache building and invalidation, performance tuning flags and opinionated launch profiles
  - native mode with GraalVM, and automatic fallback to AOT cache when missing
-->

---

# Launch profiles

Same Java program, multiple launch modes

| Profile | What it does |
|---|---|
| `small-disk` | Runs the source file directly. Nothing written to disk, but it compiles on every run. |
| `portable` | Builds a jar on the first run and reuses it. Plain `java`, no tuning flags. |
| `fast-start` | Jar plus an AOT cache, tuned for startup (C1 only, SerialGC). |
| `max-perf` | Jar plus an AOT cache, tuned for long runs (full JIT, ParallelGC). |
| `native` | Builds a native binary with GraalVM on the first run. No JVM at launch. |

<!--
The killer feature: Launch profiles.

- Opinionated configurations for generating wrapper scripts for different use cases.
-->

---
clicks: 1
---

# Benefits

Without `javaw`, "fast start" means:

<div class="benefits" :class="{ highlight: $clicks >= 1 }">

| By hand, every time | With `javaw` |
|---|---|
| `javac` and `jar` | First run |
| `java -XX:AOTMode=record ...` | First run |
| `java -XX:AOTMode=create ...` | First run |
| `java -XX:AOTMode=on ...` with matching flags | Every run |
| Redo it all when the source or JDK changes | Hash check |

</div>

<div class="pt-4 text-xl">

`javaw --profile fast-start -o tool Tool.java`

</div>

<style>
.benefits tbody tr { transition: opacity 0.4s, background-color 0.4s; }
.benefits.highlight tbody tr { opacity: 0.4; }
.benefits.highlight tbody tr:last-child {
  opacity: 1;
  font-weight: bold;
  background-color: rgba(var(--slidev-theme-primary-rgb, 59, 130, 246), 0.15);
}
</style>

<!--
Over 100 lines of shell script you don't want an agent to rewrite every time. [click]

This is the biggest one.
-->

---

# What each launcher runs

````md magic-move {lines: true}
```sh
# small-disk: nothing written to disk
exec java -ea --add-modules=jdk.incubator.json \
  "$dir"/Hello.java "$@"
```
```sh
# portable: builds a jar on the first run
exec java -ea --add-modules=jdk.incubator.json \
  -cp "$jar" Hello "$@"
```
```sh
# fast-start: jar + AOT cache, startup optimized
exec java -XX:AOTMode=on -XX:AOTCache="$aot" \
  -XX:TieredStopAtLevel=1 -XX:+UseSerialGC \
  -ea --add-modules=jdk.incubator.json \
  -cp "$jar" Hello "$@"
```
```sh
# max-perf: jar + AOT cache, runtime optimized
exec java -XX:AOTMode=on -XX:AOTCache="$aot" \
  -XX:+UseParallelGC \
  -ea --add-modules=jdk.incubator.json \
  -cp "$jar" Hello "$@"
```
```sh
# native: binary built with GraalVM on the first run
exec "$native" "$@"

# no native-image installed? jar + AOT cache instead
exec java -XX:AOTMode=on -XX:AOTCache="$aot" \
  -ea -cp "$jar" Hello "$@"
```
````

<style>
.slidev-code { font-size: 0.8rem !important; }
</style>

---
preload: false
---

# `javaw` demo

```java {monaco-run} {autorun: true, runnerOptions: javawCsv}
///usr/bin/env jbang "$0" "$@" ; exit $?
//DEPS org.apache.commons:commons-csv:1.14.1

void main() throws IOException {
    var format = CSVFormat.DEFAULT.builder().setHeader().setSkipHeaderRecord(true).get();
    try (var parser = format.parse(new StringReader(readResource("/example5.csv")))) {
        var people = parser.stream()
                .map(record -> new Person(record.get("name"), Integer.parseInt(record.get("age")), record.get("city")))
                .toList();
        people.forEach(IO::println);
    }
}
```

<script setup>
const javawCsv = { file: 'Csv.java', source: 'snippets/examples/jbang/example5_csv.java#main', files: ['snippets/examples/jbang/example5.csv'], runs: [
  { launcher: 'javaw --profile portable -r example5.csv -o csv --force', jdk: 28 },
  { launcher: './csv', jdk: 28 },
] }
</script>

<style>
.slidev-code { font-size: 0.7rem !important; }
</style>

<!--
So here I've run `javaw` on my machine.

- It detects JBang was used for this script, and generates a portable wrapper
- `javaw` resolves the //DEPS with JBang and bakes in pinned downloads
- The first run of ./csv downloads the jars and builds csv.jar
-->

---

# Inside the wrapper

```sh {1-7|9-11|13-17|19}{lines: true}
fetch() {  # path under Maven Central, pinned SHA-256
  # already in lib/ with the right hash? done
  # else download it with curl
  # check the hash, delete it on a mismatch
  # move it into lib/
  ...
}

fetch org/apache/commons/commons-csv/1.14.1/commons-csv-1.14.1.jar 32be0e1e…
fetch commons-io/commons-io/2.20.0/commons-io-2.20.0.jar df90bba0…
fetch commons-codec/commons-codec/1.19.0/commons-codec-1.19.0.jar 5c3881e4…

if [ "$(cat jar.stamp)" != "$stamp" ]; then  # hash of sources, resources, JDK
  # copy resources (example5.csv) into classes/
  javac -d classes -cp "$cp" Csv.java
  jar --create --file csv.jar --main-class Csv -C classes .
fi

exec java -cp "$jar:$cp" Csv "$@"
```

<!--
Here's a sketch of the generated csv wrapper (the real one is about 85 lines).

- javaw resolves the //DEPS with JBang when it writes the wrapper, so the full dependency list is baked in, pinned by SHA-256 hashes [click]
- No JBang, Maven or Gradle on this machine, just curl [click]
- Then it builds the jar if needed [click] and execs plain java
-->

---
layout: center
class: text-center
clicks: 1
---

# The scorecard, with `javaw`

<Scorecard :rows="$clicks >= 1 ? javawRows : introRows" :columns="$clicks >= 1 ? javawColumns : languages" />

<script setup>
import { introRows, javawRows, javawColumns, languages } from '../data/scorecard'
</script>

<!--
Here's the original scorecard from the beginning of the presentation. Java was down in every category of performance and cost. [click]

And with a native image optimized on first run, or an AOT cache fallback, Java is competitive.
-->
