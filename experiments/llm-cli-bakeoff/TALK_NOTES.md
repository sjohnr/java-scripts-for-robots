# Talk notes

## Headline numbers

| # | Number | Source |
|---|---|---|
| 1 | Go noop starts in 1.27ms; plain `java Tool.java` starts in 167.90ms -- a 132x gap | `bench/results/raw/noop-startup.json` |
| 2 | Package the same program as a jar with an AOT cache and the gap drops to 14x (18.22ms) | same file |
| 3 | `jarsrc cat` replaces a 3-command find/unzip/grep sequence and cuts output tokens from 749 to 29 (25.8x) | `results/token_cost.md` |
| 4 | 227/227 conformance checks pass across Go, Python, Node, and up to 6 Java execution modes per CLI | `tests/run_conformance.py` output |
| 5 | An AOT cache mismatched to its jar doesn't error -- it silently falls back to a SLOWER path than not using AOT at all | found live during Phase 3, see REPORT.md |
| 6 | GraalVM native-image needed zero reflection config for zip-reading + regex code and starts in 2.11ms | `bench/results/raw/noop-startup.json`, build logs |
| 7 | Java has no JSON in the stdlib; the hand-rolled parser costs ~2x the tokens of Python/Node; JBang+Jackson erases that gap but adds a dependency and a noisy cold start | `results/token_cost.md`, `tools/jsonpick/java/jbang/` |
| 8 | Live pilot, 30 headless Haiku runs: a detailed Java steering snippet did WORSE than the bare instruction "write it in Java" -- 2/3 pass vs. 3/3, 23 median turns vs. 4 | `results/agent/summary.md` |
| 9 | When a CLI was on PATH and documented but not forced, the agent skipped it and confused a file's byte size for its line count -- twice | `results/agent/summary.md`, Experiment A3/C2 |

## Surprising findings

1. **The AOT-cache-goes-stale bug isn't hypothetical.** It happened live, mid-benchmark, in this very project -- rebuilding a jar after a bugfix silently broke every AOT cache and made the "optimized" path 80% slower than the naive one, with no error, only a log line most people would never see. This is the single best "gotcha" for a live demo.
2. **Compact source files (JEP 512) can't resolve `ClassName::method` on their own implicit class.** `Tool::cmpVersion` inside `Tool.java`'s own compact-source body fails to compile; `(a,b) -> cmpVersion(a,b)` works. An LLM writing "simple" Java without steering will very plausibly hit this.
3. **AOT/CDS caches are enormous relative to the program.** A 10MB+ cache for a 1-class, 800-byte-jar "hello world" -- almost entirely generic JDK class data, not app code. Startup speed isn't free disk-wise.
4. **Java wins on source size for 4 of 5 CLIs** (noop, jarsrc, logsum, treesum) against Python and Node, using modern records/sealed-interfaces/pattern-matching -- the "Java is verbose" reputation is measurably out of date for this style of code, EXCEPT for JSON handling, where the stdlib gap is real and costly.
5. **Go isn't immune to "no stdlib for the real thing" either.** Go's `encoding/json` doesn't preserve object key order on generic unmarshal, forcing a hand-rolled ordered JSON type for jsonpick -- the same category of problem as "Java has no JSON," just smaller in scope.
6. **AOT cannot help script-mode Java at all.** `java Tool.java` source-launch and AOT/CDS caches are fundamentally incompatible mechanisms (cache indexes against a classpath; source-launch never touches one) -- this is why the java-launcher shim exists as a separate artifact rather than "just add a flag."
7. **JBang's cold start is the loudest thing in the whole Java toolchain.** Every other execution mode measured was completely silent on stderr on JDK 25; JBang's first-ever run of a script with `//DEPS` prints multiple `[jbang]` progress lines while it resolves Maven coordinates and builds a jar, a genuine one-time noise/latency cost an agent would see.
8. **More steering made the live-piloted agent worse at writing Java, not better.** 30 headless Haiku runs (pilot, low N): "just write it in Java" passed 3/3 in a median of 4 turns; a detailed JDK-25-idioms steering snippet passed only 2/3 in a median of 23 turns. This directly contradicts the naive assumption behind H4 and is worth saying on stage exactly as measured.
9. **A documented, on-PATH tool doesn't guarantee use -- and the alternative can be confidently wrong.** In the live pilot, an agent with `treesum` on PATH and named in its own CLAUDE.md still answered "largest file" by hand and confused a file's byte count for its line count in both trials of that condition.

## Model matters (pilot, low N -- Haiku vs. Sonnet, logsum only)

Same harness and snippets, two different models. Not a controlled ablation -- treat every row as "what happened in these few runs for this model," not a claim about models in general.

| Condition | Haiku: language | Haiku: conformance | Haiku: rules broken | Haiku: turns (median) | Haiku: cost (median) | Sonnet: language | Sonnet: conformance | Sonnet: rules broken | Sonnet: turns (median) | Sonnet: tokens (median) |
|---|---|---:|---|---:|---:|---|---:|---|---:|---:|
| B-a (no language stated) | Python 3/3 | 3/3 | n/a (no compliance rubric run on Haiku pilot) | 5 | $0.0832 | Python 2/2 | 2/2 | R1/R2 (expected -- no Java mandated) | 8 | 257,024 |
| B-b ("write it in Java") | Java 3/3 | 3/3 | n/a | 4 | $0.0769 | Java 3/3 | 3/3 | none (9/9 every run) | 9 | 348,268 |
| B-c (detailed steering snippet) | Java 3/3 | **2/3** | 1 compile error (missing `throws`) | 23 | $0.1895 | Java 3/3 | **3/3** | R2 (filename), all 3 runs | 15 | 623,409 |
| B-e (conservative snippet) | not run on Haiku | -- | -- | -- | -- | Java 3/3 | 3/3 | **none -- 10/10 every run** | 8 | 275,713 |

Note: `results/agent/summary.md` (Haiku) reports cost and turns per condition but not token counts; `results/agent-sonnet/summary.md` (Sonnet) reports both, so the two model columns above are not on identical units -- Haiku's compliance rubric (R1-R10) also did not exist yet when that pilot ran, so "rules broken" for Haiku is reconstructed narratively from that summary's own text, not from a rule-by-rule check.

## Three headline findings, model comparison (pilot, low N)

1. **The "more steering makes it worse" result from the Haiku pilot does not replicate on Sonnet.** Haiku's detailed steering snippet (B-c) was its least reliable condition (2/3, 23 median turns); Sonnet's B-c was fully reliable (3/3, 15 median turns) -- same snippet, same byte-for-byte SHA-256, different outcome. Model strength, not snippet quality, looks like the deciding factor here.
2. **An explicit filename instruction gets followed; an implied one doesn't -- confirmed on Sonnet.** The B-c snippet only names `Tool.java` inside an example command ("run directly with `java Tool.java`"); Sonnet named its file `logsum.java` in all 3 trials anyway. The new B-e condition states the filename directly ("a single file named Tool.java") and Sonnet complied 3/3, also scoring a clean 10/10 on every compliance rule checked -- the only condition to do so in either pilot.
3. **Sonnet costs roughly 3-4x Haiku's tokens per run** across every condition tested (e.g. B-b: 348,268 vs. Haiku's much smaller Experiment B token counts) -- unsurprising for a larger model, but worth stating plainly next to the reliability gain rather than assuming "better" is free.

## Slide-ready tables

**Startup floor, noop, JDK 25 (ms, mean, N=30+)**

| go | java-native | python | java-class | java-jar-tuned | java-aot | java-jar | node | java-src | java-compact |
|---:|---:|---:|---:|---:|---:|---:|---:|---:|---:|
| 1.27 | 2.11 | 12.74 | 16.20 | 17.99 | 18.22 | 19.13 | 19.87 | 167.90 | 186.34 |

**Token cost: one real agent task (find a method's source in a local jar cache)**

| approach | commands | tokens |
|---|---:|---:|
| naive shell (find/unzip/grep) | 3 | 749 |
| `jarsrc cat` | 1 | 29 |

**Source size, jarsrc (tokens, tiktoken proxy)**

| go | node | java-multi | java-classic | java-compact | python |
|---:|---:|---:|---:|---:|---:|
| 3492 | 3701 | 2898 | 2786 | 2709 | 2741 |

## Suggested live-demo scripts

1. **The startup gap, live.** Run `time java tools/noop/java/classic/Tool.java` (expect ~170ms), then `time java -jar tools/noop/java/build/noop.jar` (expect ~19ms), then `time java -XX:AOTCache=tools/noop/java/build/noop.aot -jar tools/noop/java/build/noop.jar` (expect ~18ms). Narrate: same program, three ways to run it, 9x difference between the naive and the tuned path.

2. **Break the AOT cache on stage.** Rebuild the jar (`cd tools/noop/java/build/classes && jar cfe ../noop.jar Tool .`), then rerun the AOT command from step 1 with `-Xlog:aot` appended. Show the `[error][aot] shared class paths mismatch` line and the fact that it still printed `ok` and exited 0 -- no crash, just quietly slower. Rebuild the cache (`build-java-modes.sh noop`) and rerun to show it recover.

3. **jarsrc vs. shell flailing.** In one terminal, do the naive thing: `find fixtures/small/jarsrc -name '*-sources.jar' | xargs -I{} unzip -l {}`, then grep for the class, then `unzip -p` the winning jar. In the other: `jarsrc cat com.example.core.alpha.Main --root fixtures/small/jarsrc`. Same answer, one command instead of three, and count the tokens live with `compare.sh`.

4. **`./compare.sh jarsrc find "**.Main" --root fixtures/small/jarsrc`** -- runs every implementation side by side with timing and token counts in one shot; good for "watch all four languages answer the same question" as a closer.
