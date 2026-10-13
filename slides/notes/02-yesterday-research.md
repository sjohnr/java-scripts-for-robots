# Research: Yesterday

Research for the "Yesterday" section of *Java Scripts for Robots: Yesterday, Today, and Tomorrow*, following the flow in `OUTLINE-draft-3.md`. It's raw material for talking points, not a script. Draft 1 gave this section about 5 minutes. Draft 3 puts the weight on Today, so this section should move quickly.

## Section at a glance

| Outline beat | One-line answer from the research | Strongest material |
|---|---|---|
| Java "scripting" before JEP 330 (was not a thing?) | For the Java *language*, mostly not. On the Java *platform*, yes, but always in some other language (Rhino, BeanShell, Groovy, Nashorn, Kotlin). | JEP 330 says outright that turning Java into a scripting language is a non-goal |
| The journey (JEP 330, 458, 512, 511) | Each JEP removed one piece of ceremony: the compile step, the single-file limit, the class/`static`/`String[]` boilerplate, then the import list | The JEP 330 non-goal about keeping `public static void main` was later reversed by JEP 512 |
| Live demo of the JEPs | Optional. Your `examples/` folder already uses every one of these features, so a static "one program, four eras" slide covers it | The polyglot pre-330 file still runs on a modern JDK (verified, see below) |
| Do these get us all the way? | No. They removed the ceremony, not the costs: recompiling on every run, no AOT, no dependencies, single-file shebang, no JSON | Your own bake-off numbers: about 168ms for source-launch vs about 16-19ms for packaged or AOT Java |

## Timeline

| Year | JDK | Event | Why it matters for "Java scripts" |
|---|---|---|---|
| 1996 | 1.0 | `javac` then `java`, `public static void main(String[] args)` | Edit/build/run from day one |
| 1998 | 1.2 | Executable JARs (`Main-Class` manifest, `java -jar`) | Packaging, not scripting |
| ~1999-2005 | -- | BeanShell (Pat Niemeyer); JSR 274 accepted in 2005 to standardize it | Interpreted, loosely typed, Java-*like* scripting. Never became part of Java SE |
| 2003-2004 | -- | Groovy launched; JSR 241 | "An alternate programming language for the Java VM" (Niemeyer's description) |
| 2006 | 6 | JSR 223 `javax.script`, bundled Rhino JavaScript engine, experimental `jrunscript` tool; JSR 199 compiler API (`javax.tools`) | The platform's official scripting story was "embed another language." The compiler API is what later made in-memory source launch possible |
| 2014 | 8 | Nashorn replaces Rhino (JEP 174); `jjs` tool | Still JavaScript, not Java |
| 2016 | -- | kscript (Kotlin scripting with dependencies) | Led directly to JBang ("why can't I just use Java?") |
| 2017 | 9 | JShell (JEP 222); `jshell file.jsh` runs snippet files | A REPL. JEP 330 says adding batch execution to it was an explicit non-goal of jshell's design |
| 2018 | 11 | **JEP 330: Launch Single-File Source-Code Programs** | `java Hello.java` and `#!` shebang files. The first time Java itself could be run as a script |
| 2019 (Dec) | -- | JBang launched (Max Rydahl Andersen) | Fills the dependency gap with `//DEPS` comments, caching, and IDE support |
| 2020 | 15 | Nashorn and `jjs` removed (JEP 372); `javax.script` kept | The "embed JavaScript" era ends |
| 2022 (Sep) | -- | Brian Goetz, "Paving the On-Ramp" design note | The case for removing beginner ceremony; it names JEP 330 as the launcher this pairs with |
| 2023 | 21 | JEP 445 preview: Unnamed Classes and Instance Main Methods | First preview of `void main()` |
| 2024 (Mar) | 22 | **JEP 458: Launch Multi-File Source-Code Programs**; JEP 463 (second preview, renamed "Implicitly Declared Classes") | Source launch grows past one file |
| 2024 (Sep) | 23 | JEP 477 (third preview, adds `java.io.IO`); JEP 476 module imports, first preview | -- |
| 2025 (Mar) | 24 | JEP 495 (fourth preview, "Simple Source Files"); JEP 494 module imports, second preview | -- |
| 2025 (Sep) | 25 LTS | **JEP 512: Compact Source Files and Instance Main Methods** (final); **JEP 511: Module Import Declarations** (final) | `void main() { IO.println("hi"); }` works with no flags |
| 2026 | 26, 27 | No launcher or source-mode JEPs (JDK 26 added JEP 516, AOT object caching with any GC) | Yesterday ends at JDK 25. Everything after that belongs in Today or Tomorrow |

## Beat 1: Java "scripting" before JEP 330

**Answer to "was not a thing?":** Running Java *source* as a script wasn't supported. The JDK's official answer was to script the JVM in a *different* language. That's a good framing for the hook, since "AI writes Python" is a newer version of the same story: when you wanted a script, you reached for something other than Java.

| Approach | Language you actually wrote | How it ran | Why it doesn't count as "Java scripts" |
|---|---|---|---|
| `javac` + `java` (+ a shell script wrapper) | Java | Compile to `.class` files, then run | Ceremony: a build step plus class files to manage |
| Executable JAR | Java | `java -jar tool.jar` | Needs a build and packaging step |
| BeanShell (JSR 274) | Java-like, loosely typed | Interpreter in a 150K jar | Not Java; standardization stalled |
| Groovy (JSR 241) | Groovy | `groovy script.groovy` | Different language |
| JSR 223 / Rhino / `jrunscript` (JDK 6) | JavaScript | Embedded engine | Different language; `jrunscript` was experimental |
| Nashorn / `jjs` (JDK 8-14) | JavaScript | Embedded engine | Different language; removed in JDK 15 |
| JShell (JDK 9) | Java snippets | `jshell file.jsh` | REPL dialect; the script must end with `/exit` or the session stays open |
| Polyglot "shebang" hack | Java | Shell runs line 1, which compiles and then `exec`s java | Clever, fragile, unofficial |
| kscript / JBang | Kotlin / Java | Third-party launcher | Not in the JDK (JBang is still the practical answer today) |

**The polyglot hack.** Verified in this research on JDK 21. Line 1 is a valid path to `sh` and a valid `//` comment to `javac`, so the same file works under both:

```java
//usr/bin/env true; d=$(mktemp -d); javac -d "$d" "$0" && exec java -cp "$d" Hello "$@"; exit $?
public class Hello {
    public static void main(String[] args) {
        System.out.println("Hello, " + (args.length > 0 ? args[0] : "world"));
    }
}
```

| Command | Result (JDK 21) |
|---|---|
| `sh Hello.java KC` | `Hello, KC` (the old way: the shell compiles to a temp dir, then runs) |
| `java Hello.java JUG` | `Hello, JUG` (the JEP 330 way: the first line is just a comment) |

Possible talking point: JBang uses the same trick today (`///usr/bin/env jbang "$0" "$@" ; exit $?`) so its files stay valid Java.

## Beat 2: The journey (JEP cards)

### JEP 330: Launch Single-File Source-Code Programs

| Field | Detail |
|---|---|
| Release | JDK 11 (Sep 2018) |
| Owner | Jonathan Gibbons |
| What it did | `java Hello.java` compiles in memory and runs the first top-level class. No `.class` files are written. Non-`.java` files with a `#!` first line run with `--source N` |
| Key quote | "In this context, it is pure ceremony to have to compile the program before running it." |
| Non-goal (quote) | "...it is not a goal to evolve the Java language into a general purpose scripting language." |
| Non-goal (quote) | "It is not a goal of this JEP to change the Java Language Specification to accommodate simpler ways of writing small programs, such as eliminating the need for the standard public static void main(String[] args) method." |
| On jshell | Rejected as the vehicle: batch execution "was an explicit non-goal in the design of jshell" |
| Limits | One file only; annotation processing disabled; custom class loader; recompiles on every launch |
| Shebang quirk (verified) | A `#!` line is stripped only for files *without* the `.java` extension. Put `#!` in `S.java` and you get `error: illegal character: '#'` |

**Irony worth a slide:** JEP 330 explicitly declined to touch `public static void main`. Seven years later, JEP 512 did exactly that.

### JEP 458: Launch Multi-File Source-Code Programs

| Field | Detail |
|---|---|
| Release | JDK 22 (Mar 2024) |
| Author | Ron Pressler |
| What it did | The launched file can reference other `.java` files in the same source tree (package = directory). They're compiled lazily, on first use. Pre-built JARs work via `--class-path '*'` or `-p libs` |
| Key quote | "The traditional edit/build/run cycle becomes simply edit/run." |
| Key quote | "Developers can decide for themselves when it is time to set up a build process rather be forced to do so by the limitations of the tooling." |
| Non-goal (quote) | "It is not a goal to launch multi-file source-code programs via the 'shebang' mechanism." |
| Non-goal (quote) | "It is not a goal to ease the use of external library dependencies in source-code programs." (The JEP adds that this "may be the subject of a future JEP") |
| Verified quirk | On JDK 21, `java Main.java` with a sibling `Util.java` fails with `cannot find symbol ... Util`. On JDK 22+ it works (your bake-off report confirms jarsrc split across 5 files on JDK 25) |

### JEP 512: Compact Source Files and Instance Main Methods

| Field | Detail |
|---|---|
| Release | Final in JDK 25 (Sep 2025), after four previews |
| Lineage | JEP 445 (21, "Unnamed Classes") -> JEP 463 (22, "Implicitly Declared Classes") -> JEP 477 (23, adds `java.io.IO`) -> JEP 495 (24, "Simple Source Files") -> JEP 512 (25, "Compact Source Files") |
| What it did | Top-level methods and fields with no class declaration; `main` can be an instance method with no `String[]` parameter; a compact source file implicitly imports `module java.base`; new `java.lang.IO` (`println`, `print`, `readln`) |
| Final-version changes | `IO` moved from `java.io` to `java.lang`, and its methods are no longer auto-statically imported (you write `IO.println`, not `println`). `IO` now uses `System.out`/`System.in` instead of `Console`, so it works with redirected I/O, which matters for agents piping output |
| Origin | JEP 445: "Evolve the Java language so that students can write their first programs without needing to understand language features designed for large programs." Goetz's on-ramp note: Java "imposes its heaviest ceremony on those first learning the language, who need and appreciate it the least." |
| Talking point | It was designed for *students*, but students and agents want the same thing: the shortest valid program. The same features that help a beginner reduce the tokens an agent spends |
| Verified quirk | On JDK 21, `void main()` without `--enable-preview` fails with `unnamed classes are a preview feature` (the error message still uses the original name) |

### JEP 511: Module Import Declarations

| Field | Detail |
|---|---|
| Release | Final in JDK 25, after JEP 476 (23) and JEP 494 (24). Finalized without further changes |
| What it did | `import module java.base;` imports every package a module exports. Compact source files get `java.base` implicitly |
| Ambiguity rule | Two modules exporting the same simple name (e.g. `Date` from `java.base` and `java.sql`) is a compile error on use; a single-type import (`import java.sql.Date;`) resolves it |
| Talking point | It solves the "which imports do I need" problem that agents and beginners both hit, at the cost of occasional name clashes |

### Arc summary (possible single slide)

| JEP | Ceremony removed | What's left |
|---|---|---|
| 330 (JDK 11) | Compile step, class files | One file only; `public static void main` |
| 458 (JDK 22) | One-file limit | Shebang still single-file; no dependencies |
| 512 (JDK 25) | Class declaration, `public static`, `String[] args`, `System.out` | -- |
| 511 (JDK 25) | Import lists | Name ambiguity on clashes |

## Beat 3: Live demo (keep or cut)

Your `examples/` folder already covers every Yesterday JEP: `examples/java/*.java` are compact source files using `void main` and `IO.println`, and `examples/shebang/*` use `#!/usr/bin/env -S java --source 28 -ea`. So the features get shown in Today either way. Options:

| Option | Time | What it shows | Tradeoff |
|---|---|---|---|
| A. Cut the demo, use a static "one program, four eras" slide | ~30s | The same Hello program as pre-330 polyglot, JEP 330 `java Hello.java`, JEP 458 two files, JEP 512 `void main() { IO.println(...) }` | Frees time for Today; nothing can break |
| B. 60-second terminal flash | ~1m | Run the polyglot file both ways, then a compact source file | One quick "wow" moment; little risk |
| C. Full JEP walkthrough | 3-4m | Each JEP live | Duplicates what Today's examples already show |

Leaning A or B fits the stated plan to save time for Today. If B, the polyglot file is the most surprising moment: it's "yesterday's hack still running on today's launcher."

Suggested four-frame code for option A:

```java
// 1996-2018: compile, then run
//   javac Hello.java && java Hello
public class Hello {
    public static void main(String[] args) {
        System.out.println("Hello");
    }
}

// JDK 11 (JEP 330): java Hello.java  -- same source, no javac
// JDK 22 (JEP 458): java Main.java   -- Main.java can use Util.java next to it

// JDK 25 (JEP 512 + 511): java Hello.java
void main() {
    IO.println("Hello");
}
```

## Beat 4: Do these advancements get us all the way?

**Short answer:** No. The JEPs removed *ceremony*, not *cost*. Each gap below sets up a part of Today or Tomorrow.

| Gap | Evidence | Where it lands in the talk |
|---|---|---|
| Recompiles on every run | Bake-off `noop`: java-src 167.9ms, java-compact 186.3ms vs java-jar 19.1ms, java-aot 18.2ms, python 12.7ms, go 1.3ms (`bench/results/raw/noop-startup.json`) | Today: launch modes and costs |
| AOT cache doesn't apply to source-launch | Bake-off report: `-XX:AOTCache=` is ignored with `java Tool.java` because source-launch never touches the classpath the cache indexes | Today costs; Tomorrow wish list (AOT for source-launch) |
| No dependencies | JEP 458 non-goal: "not a goal to ease the use of external library dependencies" | Today: JBang comparison; javaw's Maven Central jar download |
| Shebang is single-file only | JEP 458 non-goal | Today: launch modes |
| No JSON in `java.base` | Bake-off: hand-rolled JSON costs about 2x the tokens of Python or Node (jsonpick 3077 vs 1630/1519 tokens) | Tomorrow: JEP 540 (Simple JSON API, incubator, targeted to JDK 28) |
| Assertions off by default | Needs `-ea` (your `example1_assert`, shebang `-ea`) | Today: gaps and quirks |
| No arg parsing, help text, or CSV in the JDK | Your examples 3 and 5 (picocli and commons-csv in the JBang versions) | Today: gaps and quirks |
| Source launch needs `jdk.compiler` | Not verified in this research: a jlinked runtime without `jdk.compiler` presumably can't source-launch. Check before stating it on stage | Optional aside |
| Nothing new since 25 | JDK 26 and 27 shipped no launcher or source-mode JEPs | Bridge: "Yesterday ends at JDK 25. Today is what we can do with it." |

**Possible bridge line:** "The JDK spent seven years getting rid of the ceremony. It never set out to make Java fast to start as a script. JEP 330 said so in its non-goals. So: how does what we have *today* actually perform?"

## Audience hooks and trivia

| Hook | Use |
|---|---|
| JEP 330's non-goal: not "a general purpose scripting language" | Opener for Beat 2, and ironic in a talk called *Java Scripts*... |
| JEP 330 kept `psvm`; JEP 512 removed it | The 2018-to-2025 arc in one contrast |
| Four previews and four names for one feature (unnamed -> implicitly declared -> simple -> compact) | Shows how carefully the JDK moves; the JDK 21 error message still says "unnamed classes" |
| JBang's FAQ: it was inspired by kscript ("Cool you can do this with Kotlin, but why can't I just use Java?") | Same question as your abstract, asked in 2019 |
| `IO` moved to `java.lang` late, and switched off `Console` so redirected I/O works | Agents almost never have a console, so this small final change matters for them |
| Groovy, Rhino, Nashorn, BeanShell | "When Java people needed a script, the JDK's answer was a different language. Sound familiar?" Ties straight back to "AI writes Python" |

## Caveats and fact-check notes

| Item | Note |
|---|---|
| JBang FAQ wording | Says the author was reading about "the new shebang (#!) feature support in Java 10," but JEP 330 shipped in JDK 11. Don't repeat "Java 10" on stage |
| BeanShell and Groovy dates | Project start dates are approximate (around 1999 and 2003). The JSR numbers and the 2005 BeanShell JSR article are confirmed |
| `jrunscript` | Shipped in JDK 6 as an experimental tool. Its history after Nashorn's removal wasn't researched; don't claim either way |
| Verified demos | Polyglot file, JEP 330 launch, shebang, `.java` + `#!` error, multi-file failure, and compact-source preview error were all run here on OpenJDK 21.0.12. JDK 25 behavior comes from your bake-off report, not re-run here |
| JEP 512/511 pages | openjdk.org blocked direct fetches of these two pages during research. Details come from secondary sources (happycoders.eu, InfoQ, Baeldung) and match the JEP text as I know it. Spot-check the IO method list against the JDK 25 javadoc before putting it on a slide |

## Sources

- [JEP 330: Launch Single-File Source-Code Programs](https://openjdk.org/jeps/330)
- [JEP 458: Launch Multi-File Source-Code Programs](https://openjdk.org/jeps/458)
- [JEP 512: Compact Source Files and Instance Main Methods](https://openjdk.org/jeps/512)
- [JEP 511: Module Import Declarations](https://openjdk.org/jeps/511)
- [JEP 445: Unnamed Classes and Instance Main Methods (Preview)](https://openjdk.org/jeps/445)
- [JEP 222: jshell](https://openjdk.org/jeps/222)
- [JEP 372: Remove the Nashorn JavaScript Engine](https://openjdk.org/jeps/372)
- [Brian Goetz, "Paving the On-Ramp"](https://openjdk.org/projects/amber/design-notes/on-ramp)
- [Java 25 Features (happycoders.eu)](https://www.happycoders.eu/java/java-25-features/) -- JEP 512/511 preview history and final changes
- [InfoQ: Instance Main Methods Move from Preview to Final in JDK 25](https://www.infoq.com/news/2025/05/jdk25-instance-main-methods)
- [Baeldung: New Features in Java 25](https://www.baeldung.com/java-25-features) -- module import ambiguity example
- [Oracle JShell User's Guide: Scripts](https://docs.oracle.com/en/java/javase/23/jshell/scripts.html)
- [JBang FAQ](https://www.jbang.dev/documentation/jbang/latest/faq.html)
- [Artima: Scripting Java, the BeanShell JSR (2005)](https://www.artima.com/articles/scripting-java-the-beanshell-jsr)
- [JSR 274 (BeanShell)](https://jcp.org/en/jsr/detail?id=274)
- [Java 26 Features (happycoders.eu)](https://www.happycoders.eu/java/java-26-features/)
- [InfoQ: JDK 27 and JDK 28, What We Know So Far](https://www.infoq.com/news/2026/08/java-27-so-far/)
- Your repo: `experiments/llm-cli-bakeoff/REPORT.md`, `examples/`
