# Assertions

````md magic-move {lines: true}
<<< @/snippets/examples/python/example1_assert.py
<<< @/snippets/examples/java/example1_assert.java
````

<style>
.slidev-code { font-size: 0.75rem !important; }
</style>

<!--
Simple one first. The `assert` keyword.
-->

---
preload: false
---

# Assertions demo

<<< @/snippets/examples/java/example1_assert.java java {monaco-run} {autorun: true, runnerOptions: asserts}

<script setup>
const asserts = { file: 'Assert.java', launchers: ['java', 'java -ea'], args: ['World'] }
</script>

<style>
.slidev-code { font-size: 0.75rem !important; }
</style>

---

# Argument parsing

````md magic-move {lines: true}
<<< @/snippets/examples/python/example3_argparse.py
<<< @/snippets/examples/java/example3_argparse.java#main
````

<style>
.slidev-code { font-size: 0.75rem !important; }
</style>

---
preload: false
---

# Argument parsing demo

<<< @/snippets/examples/java/example3_argparse.java#main java {monaco-run} {autorun: true, runnerOptions: argparseErrors}

<script setup>
const argparseErrors = { file: 'Argparse.java', source: 'snippets/examples/java/example3_argparse.java#main', runs: [
  { launcher: 'java -ea', args: ['--help'] },
  { launcher: 'java -ea', args: ['-P', 'NaN'] },
] }
</script>

<style>
.slidev-code { font-size: 0.75rem !important; }
</style>

<!--
- No `--help` support or help text.
- Parse errors are `NumberFormatException`
-->

---
preload: false
---

# Argument parsing demo

<<< @/snippets/examples/java/example3_argparse.java#main java {monaco-run} {autorun: true, runnerOptions: argparse}

<script setup>
const argparse = { file: 'Argparse.java', source: 'snippets/examples/java/example3_argparse.java#main', runs: [
  { launcher: 'java -ea', args: ['-P', '8080', '--param1', 'hello', '--param2', '42', '--enable-featureX'] },
] }
</script>

<style>
.slidev-code { font-size: 0.75rem !important; }
</style>

---

# Argument parsing with picocli

````md magic-move {lines: true}
<<< @/snippets/examples/java/example3_argparse.java#main
<<< @/snippets/examples/jbang/example3_argparse.java#class
````

<style>
.slidev-code { font-size: 0.7rem !important; }
</style>

---
preload: false
---

# Argument parsing with picocli demo

<<< @/snippets/examples/jbang/example3_argparse.java#main java {monaco-run} {autorun: true, runnerOptions: picocli}

<script setup>
const picocli = { file: 'Argparse.java', source: 'snippets/examples/jbang/example3_argparse.java#main', runs: [
  { launcher: 'jbang', args: ['--help'] },
  { launcher: 'jbang', args: ['-P', 'NaN'] },
] }
</script>

<style>
.slidev-code { font-size: 0.7rem !important; }
</style>

---
preload: false
---

# Argument parsing with picocli demo

<<< @/snippets/examples/jbang/example3_argparse.java#main java {monaco-run} {autorun: true, runnerOptions: picocli}

<script setup>
const picocli = { file: 'Argparse.java', source: 'snippets/examples/jbang/example3_argparse.java#main', runs: [
  { launcher: 'jbang', args: ['-P', '8080', '--param1', 'hello', '--param2', '42', '--enable-featureX'] },
] }
</script>

<style>
.slidev-code { font-size: 0.7rem !important; }
</style>

---

# CSV

````md magic-move {lines: true}
<<< @/snippets/examples/python/example5_csv.py
<<< @/snippets/examples/java/example5_csv.java
````

<style>
.slidev-code { font-size: 0.7rem !important; }
</style>

---
preload: false
---

# CSV demo

<<< @/snippets/examples/java/example5_csv.java java {monaco-run} {autorun: true, runnerOptions: csv}

<script setup>
const csv = { file: 'Csv.java', files: ['snippets/examples/java/example5.csv'], runs: [
  { launcher: 'java -ea' },
] }
</script>

<style>
.slidev-code { font-size: 0.7rem !important; }
</style>

---

# CSV with Commons CSV

````md magic-move {lines: true}
<<< @/snippets/examples/java/example5_csv.java
<<< @/snippets/examples/jbang/example5_csv.java#all
````

<style>
.slidev-code { font-size: 0.7rem !important; }
</style>

<!--
Source: examples/java/example5_csv.java and examples/jbang/example5_csv.java (#main region; the JBang header and imports are hidden).
Not shown: //DEPS org.apache.commons:commons-csv:1.14.1 and //FILES example5.csv, which bundles the CSV into the jar so getResourceAsStream still finds it.
-->

---
preload: false
---

# CSV with Commons CSV demo

<<< @/snippets/examples/jbang/example5_csv.java#all java {monaco-run} {autorun: true, runnerOptions: commonsCsv}

<script setup>
const commonsCsv = { file: 'Csv.java', source: 'snippets/examples/jbang/example5_csv.java#all', files: ['snippets/examples/jbang/example5.csv'], runs: [
  { launcher: 'jbang' },
] }
</script>

<style>
.slidev-code { font-size: 0.7rem !important; }
</style>

---

# JSON with nanojson

````md magic-move {lines: true}
<<< @/snippets/examples/python/example6_json.py
<<< @/snippets/examples/jbang/example6_json.java#main
````

<style>
.slidev-code { font-size: 0.7rem !important; }
</style>

---
preload: false
---

# JSON with nanojson demo

<<< @/snippets/examples/jbang/example6_json.java#main java {monaco-run} {autorun: true, runnerOptions: nanojson}

<script setup>
const nanojson = { file: 'Json.java', source: 'snippets/examples/jbang/example6_json.java#main', files: ['snippets/examples/jbang/example6.json'], runs: [
  { launcher: 'jbang' },
] }
</script>

<style>
.slidev-code { font-size: 0.7rem !important; }
</style>

<!--
Works on JDK 25 today, at the cost of a dependency.
-->

---

# JSON in JDK 28

````md magic-move {lines: true}
<<< @/snippets/examples/jbang/example6_json.java#main
<<< @/snippets/examples/java/example6_json.java#all
````

<style>
.slidev-code { font-size: 0.7rem !important; }
</style>

<!--
- JEP 540: Simple JSON API (Incubator), **Status:** Integrated
- Enable with `--add-modules=jdk.incubator.json`
-->

---
preload: false
---

# JSON in JDK 28 demo

<<< @/snippets/examples/java/example6_json.java#main java {monaco-run} {autorun: true, runnerOptions: incubatorJson}

<script setup>
const incubatorJson = { file: 'Json.java', source: 'snippets/examples/java/example6_json.java#main', files: ['snippets/examples/java/example6.json'], runs: [
  { jdk: 28, launcher: 'java -ea --add-modules jdk.incubator.json' },
] }
</script>

<style>
.slidev-code { font-size: 0.7rem !important; }
</style>
