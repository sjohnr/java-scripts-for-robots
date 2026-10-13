---
layout: section
---

# Yesterday

How we got here

<!--
[About 5 minutes total. Keep this moving.]
-->

---

# Before JEP 330

We scripted the JVM, not Java

| Year | JDK | What you actually wrote | How it ran |
|---|---|---|---|
| ~1999 | -- | BeanShell (Java-like, loosely typed) | Interpreter jar; JSR 274 stalled |
| ~2003 | -- | Groovy | `groovy script.groovy` |
| 2006 | 6 | JavaScript (Rhino) | `javax.script`, experimental `jrunscript` |
| 2014 | 8 | JavaScript (Nashorn) | `jjs`; removed in JDK 15 |
| 2016 | -- | Kotlin (kscript) | Third-party launcher; inspired JBang |
| 2017 | 9 | Java *snippets* | `jshell file.jsh` (a REPL; end with `/exit`) |

<div v-click class="pt-6 text-xl text-center">
When Java needed a script, the answer was <b>another language</b>.
</div>

<!--
Before JEP 330, "Was Java scripting a thing?" We scripted the JVM, not Java.

**Notes:** 
- JEP 330 - Single-file source launch.

- Framing: The JDK spent seven years removing the ceremony of Java scripts, but not the cost.
-->

---

# The polyglot hack

One file, valid as both shell and Java

```java {1|2-6}
//usr/bin/env true; d=$(mktemp -d); javac -d "$d" "$0" && exec java -cp "$d" Hello "$@"; exit $?
public class Hello {
    public static void main(String[] args) {
        System.out.println("Hello, " + (args.length > 0 ? args[0] : "world"));
    }
}
```

<div v-click>

- To `sh`, line 1 is the command `/usr/bin/env`
- To `javac`, line 1 is a `//` comment

</div>

<style>
.slidev-code { font-size: 0.7rem !important; }
</style>

<!--
This is something I learned about. Clever, fragile, unofficial.

- Apparently, JBang still uses this trick.
-->

---
preload: false
---

# Yesterday's hack, today's launcher

```java {monaco-run} {autorun: true, runnerOptions: hack}
//usr/bin/env true; d=$(mktemp -d); javac -d "$d" "$0" && exec java -cp "$d" Hello "$@"; exit $?
public class Hello {
    public static void main(String[] args) {
        System.out.println("Hello, " + (args.length > 0 ? args[0] : "world"));
    }
}
```

<script setup>
// Fence options can't nest braces, so runner options live here
const hack = { launchers: ['sh', 'java'], args: ['KC'] }
</script>

<style>
.slidev-code { font-size: 0.7rem !important; }
</style>

<!--
One click runs the same file twice: `sh Hello.java KC` (the old way: the shell compiles to a temp dir, then runs) and `java Hello.java KC` (JEP 330: line 1 is just a comment).
The sh run uses whatever java/javac is on the PATH, not SLIDES_JAVA.
Needs npm run dev. Candidate to cut.
-->

---

# One program, four eras

<div class="grid grid-cols-[3fr_2fr] gap-6 items-start">
<div>

````md magic-move {at: 1, lines: true}
```java
// 1996: compile, then run
// $ javac Hello.java && java Hello
public class Hello {
    public static void main(String[] args) {
        System.out.println("Hello, robots!");
    }
}
```
```java
// JDK 11: same source, no javac
// $ java Hello.java
public class Hello {
    public static void main(String[] args) {
        System.out.println("Hello, robots!");
    }
}
```
```java
#!/usr/bin/env -S java --source 11
// JDK 11: a script file named hello, no .java
// $ chmod +x hello && ./hello
public class Hello {
    public static void main(String[] args) {
        System.out.println("Hello, robots!");
    }
}
```
```java
// JDK 22: Greeter.java sits next to Hello.java
// $ java Hello.java
public class Hello {
    public static void main(String[] args) {
        System.out.println(Greeter.greet("robots"));
    }
}
```
```java
// JDK 25: no class, no static, no String[]
// $ java Hello.java
void main() {
    IO.println("Hello, robots!");
}
```
```java
// JDK 25: java.base comes free; import the rest by module
// $ java Hello.java
import module java.net.http;

void main() {
    var client = HttpClient.newHttpClient();
    IO.println("Hello, robots! Speaking " + client.version());
}
```
```java
#!/usr/bin/env -S java --source 25
// JDK 25: the whole script
// $ ./hello
void main() {
    IO.println("Hello, robots!");
}
```
````

</div>
<v-switch at="1" class="jep-card">
<template #0>

### JDK 1.0 · 1996

**Edit, build, run**

`javac` writes `.class` files, `java` runs them.

</template>
<template #1>

### JEP 330 · JDK 11 · 2018

**Launch single-file source-code programs**

> "...it is pure ceremony to have to compile the program before running it."
>
> <span class="attribution">Jonathan Gibbons, JEP 330 owner</span>

Compiles in memory, writes no `.class` files.

</template>
<template #2>

### JEP 330 · JDK 11 · 2018

**Shebang files**

A file without the `.java` extension can start with `#!`.

Put `#!` in a `.java` file and you get `error: illegal character: '#'`.

</template>
<template #3>

### JEP 458 · JDK 22 · 2024

**Launch multi-file source-code programs**

> "The traditional edit/build/run cycle becomes simply edit/run."
>
> <span class="attribution">Ron Pressler, JEP 458 author</span>

Other files in the source tree compile on first use.

</template>
<template #4>

### JEP 512 · JDK 25 · 2025

**Compact source files and instance main methods**

No class declaration, instance `main`, new `java.lang.IO`. Final after four previews.

</template>
<template #5>

### JEP 511 · JDK 25 · 2025

**Module import declarations**

`import module` imports every package a module exports. Compact source files get `java.base` implicitly.

</template>
<template #6>

### JDK 25 · today

**A Java script**

`#!`, no class, no `static`, no imports.

It still compiles from source on every run.

</template>
</v-switch>
</div>

<style>
.jep-card { @apply rounded-lg border border-gray-400/30 p-4 text-sm; }
.jep-card h3 { @apply text-base op-70 mb-2; }
.jep-card blockquote { @apply my-3 text-sm; }
.jep-card .attribution { @apply text-xs op-70; }
</style>

<!--
- Since 1996, we've been writing "Hello, World" programs [click]
- But in JDK 11, JEP 330 gave us single-file source launch... [click] ...*and* support for shebang files. [click]
- JEP 458 gave us multi-file source launch to evolve our programs more easily [click]
- And then came JDK 25, with JEP 512. [click]
- And finally JEP 511, which gives these nice module imports. [click]

Designed for beginners is also great for agents. Right?
-->

---

# Do these get us all the way?

<div v-click class="text-xl pb-4">No. They removed the <b>ceremony</b>, not the <b>cost</b>.</div>

<div v-click="1">

<v-clicks at="2">

| Gap | What it means |
|---|---|
| Recompiles on every run | Slower startup |
| AOT cache doesn't apply to source launch | Slower startup |
| No dependencies (a JEP 458 non-goal) | More tokens |
| Shebang is single-file only (also a non-goal) | Launcher scripts |
| Assertions off by default; no arg parsing or help text | Dependencies |
| No JSON or CSV parsing in the JDK | More dependencies |

</v-clicks>

</div>
