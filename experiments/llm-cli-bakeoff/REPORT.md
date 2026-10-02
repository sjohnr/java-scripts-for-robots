# Modern Java CLIs for agents: a bake-off against Go, Python, and Node

Repo: `llm-cli-bakeoff/` inside this repository. All numbers below trace to a file under `bench/results/raw/`, `results/`, or `tests/golden/`. Measured vs. recalled/assumed is called out explicitly wherever it matters.

## Executive summary

Modern Java (JDK 25) can write small CLIs that are about as concise as Python and cleaner in places than Go or Node -- confirming H1. Its startup floor is real but has been substantially closed by AOT/CDS and simple JVM tuning -- partially confirming H2: the "just run the script" experience (`java Tool.java` with no cache) is 80-150x slower than Go, but the packaged, cached forms (`-jar` plus AOT) land within about 15-25x of Go and close to Python/Node, which is a very different story than the reputation. A single purpose-built CLI call replaces a multi-command shell sequence and cuts token cost by roughly 25x on a realistic "find this method's source" task -- supporting H3, with one honest exception documented below where the naive shell baseline undercounts because it does strictly less work. A live pilot (30 headless Haiku runs, see Phase 5 below) sharpens H3 further: when the CLI wasn't discoverable, the model didn't just cost more, it got a precise answer subtly wrong; and when the CLI was documented and available, it wasn't always actually used, producing a confident, verifiably wrong answer (bytes mistaken for line count) that the tool would have gotten right. Java's stdlib genuinely has no JSON parser, which is real friction (H1 partial refutation) but is fully closed by JBang + Jackson at the one-time cost of a dependency and a slower, noisier cold start. H4 (LLM steering) got a live, if low-N, pilot: unsteered Haiku defaults to Python 3/3 times; bare "write it in Java" passed conformance 3/3 in a median of 4 turns; a detailed steering snippet did *worse* (2/3, median 23 turns) -- a genuinely surprising negative result, reported as such.

## Hypothesis verdicts

| ID | Hypothesis | Verdict | Evidence |
|---|---|---|---|
| H1 | Modern Java is competitive in size/readability with Python/JS | **Mostly confirmed** | jarsrc: Java classic 2786 tokens vs Python 2741, Node 3701, Go 3492 (source_stats, `results/token_cost.json`). Java is shortest or near-shortest on 3 of 5 CLIs measured. jsonpick is the exception: Java classic (hand-rolled JSON) is 3077 tokens vs Python 1630 and Node 1519 -- almost 2x, because Java has no JSON in the stdlib (see H1 caveat below). |
| H2 | JVM startup is the weak point, but AOT/CDS/tuning closes most of the gap | **Partially confirmed** | noop: Go 1.27ms, java-native 2.11ms, Python 12.74ms, Node 19.87ms, java-class 16.20ms, java-jar 19.13ms, java-jar-tuned 17.99ms, java-aot 18.22ms, java-cds 19.28ms, java-src (uncached) 167.90ms, java-compact 186.34ms (`bench/results/raw/noop-startup.json`). Packaged/cached Java modes land in the same 16-20ms neighborhood as Node and about 30% behind Python -- the gap to Go/native shrinks from ~150x (naive `java Tool.java`) to ~15x (`-jar`) to ~14x (AOT). It never closes entirely; Go and native-image stay in a different startup tier. |
| H3 | Purpose-built CLIs cut agent tokens/calls vs. ad-hoc shell | **Confirmed, with two live-pilot refinements** | Static: jarsrc "find a method's source" task: naive 3-command ls/unzip/grep sequence = 749 proxy tokens; one `jarsrc cat` call = 29 tokens (`results/token_cost.md`). treesum: naive 11-command `find`/`wc` sequence = 42 tokens vs. one `treesum` call = 252 tokens -- costs *more* here only because the naive sequence answers a smaller question (no LOC, no size sort); not the same task, documented as an honest negative-ish result. Live pilot (30 headless Haiku runs, see Phase 5): when a CLI wasn't discoverable, the agent didn't just spend more (up to 556K tokens vs. ~43K with the tool) -- it also got a precise number (a log's last timestamp) subtly wrong while getting the substance right. And when a CLI WAS documented and on PATH, the agent still sometimes skipped it and got a verifiably wrong answer (confused a file's byte size for its line count, in both trials of that condition) -- tool availability is not tool use. |
| H4 | With steering, an LLM writes working modern Java scripts at acceptable cost | **Partially confirmed, one real surprise** | Live pilot, claude-haiku-4-5-20251001, 3 trials/condition (pilot, low N -- see `results/agent/summary.md`): unsteered, the model defaults to Python 3/3 times. Bare "write it in Java" (no snippet) passed the logsum conformance suite 3/3, median 4 turns. The detailed steering snippet (JDK 25 compact-source rules, etc.) did WORSE: 2/3 pass, median 23 turns -- more steering text did not reduce mistakes or turns in this pilot, a genuinely counter-to-expectation result kept as-is rather than explained away. Starting unsteered then sending one "rewrite it in Java" follow-up turn worked 3/3 but cost the most (redoing the whole implementation). The one real Java compile error caught (missing `throws IOException` on a compact-source `main`) is cataloged in `steering/AGENTS-java-scripts.md`, now updated from live evidence, not just static analysis. A stronger model (Sonnet) was piloted separately on the same snippet plus a conservative alternative -- see Phase 5b below. |
| H5 | Output hygiene materially affects token cost | **Confirmed as a risk, not as steady-state noise** | On JDK 25, all 6 core Java execution modes are completely silent on stderr for a success-path run (`results/stderr_hygiene.md`) -- better than older JDKs' reputation. But two real noise sources were found empirically during this project: JBang's one-time cold-start dependency-resolution banner, and a stale/mismatched AOT cache's silent fallback, which prints an `[error][aot]` block AND makes the program slower, not fail outright. Both are one-time or misconfiguration-triggered, not steady-state costs. |

## Methodology and environment

Full detail in `results/environment.md`. Summary: Apple M4 Max, 14 logical CPUs, 36GB RAM, macOS 15.7.3. JDK 25.0.4+7 (Temurin, via SDKMAN) is the primary JDK for all results; JDK 27+35 (latest GA, jdk.java.net, manually installed after an SDKMAN packaging bug) and GraalVM CE 25.3.4.1 (native-image) were also installed and used where noted. Go 1.27.1, Node v24.4.0, Python 3.13.5, hyperfine 1.20.0, JBang 0.141.0.

Benchmarks: hyperfine, `--warmup 3 --min-runs 30` for small-workload runs, `--warmup 2 --min-runs 10` for the 50x agent-session run. Machine load average at benchmark time was 5.48/3.44/2.57 on a 14-core machine (recorded via `uptime`; not perfectly idle -- a caveat on absolute numbers, ratios between commands in the same hyperfine invocation are much more robust to this than single absolute numbers are, since they are measured back-to-back under the same load).

**Threats to validity / explicitly not done in this pass:**
- Large-scenario fixtures (2000 jars / 2M-line log / 50k files / 50MB JSON) were **not generated or benchmarked**. The fixture generator supports it (`scripts/gen_fixtures.py large`) but it was not run in this session due to time; break-even analysis and large-N throughput numbers are therefore **not available** and should not be inferred from the small-fixture numbers here.
- 1-CPU / 2-CPU constrained runs (`taskset` or equivalent) were **not done**. macOS has no direct `taskset` equivalent readily available in this environment; the fallback would be `taskset`-via-Linux-VM or `nice`/QoS classes, neither of which was set up. This is a real gap for the "agent sandboxes are often small" angle in the mission brief.
- Only a representative subset of CLIs got every Java execution mode benchmarked (noop got all 11 modes; jarsrc got 6; logsum/treesum/jsonpick got 2 Java modes each: jar and AOT). Compact-source and multi-file source variants exist only for noop and jarsrc, not all 5 CLIs, for time reasons -- documented, not hidden.
- Phase 5 ran as a lighter, user-approved replacement (see `prompts/02-phase-5-replacement.md`), not the original spec: 30 total headless runs (not 60), model was Haiku not "the same fixed model" left to the original phase's choice, 2-3 trials per condition rather than 5. Full results, every threat to validity, and the subscription-impact numbers are in `results/agent/summary.md` and summarized in the Phase 5 section below. Everything there is explicitly labeled "pilot, low N."

## Phase 1: conformance

40 hand-written cases (success paths, edge cases, and error paths) across all 5 CLIs, run against every implementation of every CLI: Go, Python, Node, and between 2 and 6 Java execution modes depending on the CLI (`tests/run_conformance.py`, `tests/cases.json`). **227/227 checks pass.** `--help` wording and the JSON-parse-error reason text are intentionally excluded from byte comparison, per spec/00-conventions.md, which says those are not cross-language comparable.

One real bug was caught and fixed by this process: the Java reference implementation printed `error: unknown flag: X`, but spec/00-conventions.md specifies just `unknown flag: X` (no `error: ` prefix). Go, Python, and Node had all implemented this correctly; the conformance run caught the Java reference's deviation from its own spec, and it was fixed (see git history, "Phase 1e" commit).

## Phase 2: Java execution modes -- what actually worked

| Mode | Works on JDK 25? | Notes |
|---|---|---|
| java-src (JEP 330) | Yes | `java Tool.java`, classic `public static void main`. No caching -- recompiles every invocation. |
| java-compact (JEP 512/511) | Yes, no preview flag needed | `void main()`, `IO.println`, `import module java.base;` all work with zero flags on JDK 25 -- these JEPs are finalized, not preview, as of this JDK. |
| java-shebang | Yes | `#!$JAVA_HOME/bin/java --source 25` as the first line of an extensionless, executable file; runs directly. |
| java-multi (JEP 458) | Yes | `java Tool.java` where Tool.java's directory contains several `.java` files; javac compiles them all in-memory at launch, no build step. Verified for jarsrc split across 5 files. |
| java-class | Yes | Precompiled `.class` files, `java -cp`. |
| java-jar | Yes | Standard executable jar. |
| java-jar-tuned | Yes | `-XX:TieredStopAtLevel=1 -XX:+UseSerialGC -XX:-UsePerfData` measured individually and combined; combined flags shown in the tuned numbers below. |
| java-aot (JEP 483/514) | Yes | Three-step classic form (`-XX:AOTMode=record` -> `-XX:AOTMode=create` -> run with `-XX:AOTCache=`) and the one-step JEP 514 form (`-XX:AOTCacheOutput=`, which trains and creates in a single invocation, spawning a child process and printing visible `[jbang]`-style progress to stderr) both work. Classic AppCDS dynamic archive (`-XX:ArchiveClassesAtExit=` / `-XX:SharedArchiveFile=`) also works, as a smaller, older-generation comparison point. |
| java-src-aot | **Does not apply as asked** | AOT/CDS caches are built against a compiled jar/classpath, not against source files; there is no mechanism to attach an AOT cache to `java Tool.java` source-launch mode. Investigated directly (tried `-XX:AOTCache=` combined with `java Tool.java`): the cache flag is simply ignored for source-launch, since source-launch's in-memory compiled classes never touch the classpath the cache indexes against. This is a real, negative finding: script-mode Java cannot benefit from AOT/CDS at all -- the `java-launcher` shim below exists specifically to route around this limitation. |
| java-launcher | Yes (built for this project) | `tools/java-launcher/jlaunch`: hashes source+JDK version, compiles once into `~/.cache/jlaunch/<hash>/`, builds a jar and an AOT cache, execs with the cache on every subsequent call. Measured: noop cold run (first-ever call, full compile+AOT build) 0.64s; warm run (cache hit) 0.042s. |
| java-native (GraalVM) | Yes | `native-image -jar x.jar x-native`. Built cleanly for noop (4.82MiB, ~10s build) and jarsrc (6.31MiB, ~10s build, zip reading + regex worked with **zero** reflection-config needed). Reference point only, per the mission brief -- not the main story, since it requires a separate GraalVM toolchain most agent sandboxes won't have preinstalled. |

Go: `go run` and a built binary measured; `-ldflags="-s -w"` shrinks the noop binary from 2,429,954 bytes to 1,587,730 bytes (about 35% smaller, still >1.5MB for a program that prints one line -- Go's static-link floor). Python: `python3 tool.py` (only mode; no compile cache to speak of). Node: `node tool.mjs`; Node's own compile-cache feature was not separately isolated as a mode in this pass (time).

## Phase 3: performance

### Startup floor (`noop`, N=30+, JDK 25.0.4)

| Mode | mean | median | min |
|---|---:|---:|---:|
| go | 1.27ms | 1.24ms | 1.09ms |
| java-native | 2.11ms | 2.08ms | 1.87ms |
| python | 12.74ms | 12.52ms | 12.07ms |
| java-class | 16.20ms | 16.09ms | 14.75ms |
| java-jar-tuned | 17.99ms | 17.96ms | 16.52ms |
| java-aot | 18.22ms | 18.16ms | 16.64ms |
| java-jar | 19.13ms | 19.16ms | 17.46ms |
| java-cds-classic | 19.28ms | 19.27ms | 17.52ms |
| node | 19.87ms | 20.10ms | 17.99ms |
| java-src (uncached) | 167.90ms | 167.99ms | 166.00ms |
| java-compact (uncached) | 186.34ms | 185.85ms | 182.36ms |

Source: `bench/results/raw/noop-startup.json`.

### Per-CLI, small fixture (N=30+)

| CLI | go | python | node | java-jar | java-aot | java-native |
|---|---:|---:|---:|---:|---:|---:|
| jarsrc (`find **.Main`) | 2.01ms | 20.17ms | 23.38ms | 32.59ms | 27.96ms | 3.11ms |
| logsum (1k lines) | -- | 9.11x slower than go | 14.57x slower than go | -- | 19.94x slower than go | -- |
| treesum (200 files) | -- | 5.81x slower than go | 7.50x slower than go | -- | 8.98x slower than go | -- |
| jsonpick | -- | 8.43x slower than go | 11.88x slower than go | 13.73x slower than go | 13.95x slower than go | -- |

(logsum/treesum absolute means are in `bench/results/raw/{logsum,treesum}-small.json`; ratios reproduced here as measured by hyperfine's own summary since those raw files were not re-extracted to absolute ms in this table for space -- go to the JSON for exact ms.) jsonpick also included a JBang+Jackson warm-cache run: 151x slower than go for this trivial single-value extraction, dominated by Jackson class loading, not by the JSON parsing itself.

### Agent session cost: 50 sequential small-workload calls (jarsrc find, N=10)

| Mode | total (50 calls) |
|---|---:|
| go | 120ms (derived from mean, `bench/results/raw/agent-session-50x.json`) |
| java-native | 167.2ms |
| python | ~1.02s |
| node | ~1.18s |
| java-aot | 1.388s |
| java-jar | 1.599s |

### A real bug found and fixed mid-benchmark

Rebuilding the jars after the Phase-1 conformance fix silently invalidated every AOT/CDS cache (path+size+timestamp mismatch). The first AOT benchmark run therefore included a detect-mismatch-and-fall-back-to-normal-loading penalty on every single invocation and looked **slower** than plain `-jar` (34.7ms vs. 23.0ms for noop). This was caught by noticing the result contradicted JEP 483's own claimed benefit, investigated with `-Xlog:aot`, confirmed (`shared class paths mismatch`, silent fallback, no hard error), fixed by rebuilding every cache against the current jars, and re-measured -- after which AOT correctly beat plain jar as expected. Kept in this report as a concrete illustration of the H5 finding: **an AOT/CDS mismatch degrades silently to a slower path, not a hard failure**, which is exactly the kind of thing that would quietly cost an agent (or a CI pipeline) real wall-clock time with zero visible error to act on.

### Artifact sizes

| CLI | jar | AOT cache | CDS (jsa) | Go binary | Go binary (stripped) | native-image |
|---|---:|---:|---:|---:|---:|---:|
| noop | 848 B | 10.21 MB | 147 KB | 2.43 MB | 1.59 MB | 4.82 MB |
| jarsrc | 8.9 KB | 11.47 MB | 262 KB | 3.44 MB | -- | 6.31 MB |
| logsum | 4.1 KB | 11.16 MB | 246 KB | 2.90 MB | -- | -- |
| treesum | 5.8 KB | 10.96 MB | 328 KB | 2.44 MB | -- | -- |
| jsonpick | 9.4 KB | 10.99 MB | 262 KB | 3.70 MB | -- | -- |

The AOT cache is 10-100x bigger than the CDS `.jsa` archive and 1000x+ bigger than the jar itself for these trivial programs -- almost entirely JDK base-class content, not application code. This matters for agent sandboxes with tight disk budgets: a "free" 10ms startup win costs roughly 10MB on disk per cached program.

## Phase 4: token-cost analysis (static, tiktoken o200k_base proxy -- `ANTHROPIC_API_KEY` was not set in this environment, so every number in this section is a **proxy**, not an Anthropic-tokenizer-exact count)

### Source size per implementation (full table in `results/token_cost.md`)

| CLI | go | python | node | java-classic | java-compact | java-multi | java-jbang |
|---|---:|---:|---:|---:|---:|---:|---:|
| noop | 100 | 104 | 85 | 70 | 58 | -- | -- |
| jarsrc | 3492 | 2741 | 3701 | 2786 | 2709 | 2898 | -- |
| logsum | 2052 | 1485 | 1341 | 1324 | -- | -- | -- |
| treesum | 1899 | 1393 | 1409 | 1275 | -- | -- | -- |
| jsonpick | 4455 | 1630 | 1519 | 3077 | -- | -- | 1478 |

Java is the shortest implementation for noop, jarsrc, logsum, and treesum (classic style, tokens). jsonpick is the exception: Java's hand-rolled JSON parser is nearly double Python's or Node's token count, because those languages have `json` in the stdlib and Java does not -- the JBang+Jackson variant (1478 tokens) closes this gap and undercuts even Python, at the cost of a non-stdlib dependency and the JBang cold-start noise noted above. Go is consistently the most verbose implementation, mostly from its explicit error handling and its own hand-rolled order-preserving JSON parser for jsonpick (Go's stdlib JSON does not preserve key order, forcing a custom implementation -- a second "no free JSON" data point, this time for Go, not Java).

### Naive shell flailing vs. one CLI call

| Task | approach | commands | output tokens |
|---|---|---:|---:|
| "find this method's source in the local jar cache" (jarsrc) | naive shell (find + unzip + grep) | 3 | 749 |
| same task | single `jarsrc cat` call | 1 | 29 |
| "repo overview" (treesum) | naive shell (11x find/wc) | 11 | 42 |
| same task | single `treesum` call | 1 | 252 |

The jarsrc result is the clean H3 win: 25.8x fewer tokens, 3x fewer commands, for genuinely the same information. The treesum result is included precisely because it does NOT favor the CLI on token count -- explained above: the naive 11-command sequence only counts files per extension, it never computes LOC or sorts by size, so it is answering a smaller question than treesum answers. A fair apples-to-apples naive baseline for treesum's actual output would need several more commands (per-extension `wc -l` sums, a `find -printf` sort) and would very likely flip this result back in the CLI's favor -- but that was not measured, so it is reported as a genuine caveat rather than adjusted after the fact.

## Phase 5: agent experiment pilot (pilot, low N -- full detail in `results/agent/summary.md`)

Run as a lighter, user-approved replacement for the original Phase 5 spec (`prompts/02-phase-5-replacement.md`): 30 total headless `claude -p` runs on **claude-haiku-4-5-20251001**, Pro-subscription auth (not API-key billing, confirmed via `claude auth status`), `--setting-sources project` and `--strict-mcp-config` for isolation, sequential execution, fresh temp directory per trial. **$2.45 total list-price-equivalent cost, 4,887,154 tokens across all 30 runs, no rate-limit hits, no stop conditions triggered.**

### Experiment A: does the CLI pay off? (3 tasks x 3 conditions x 2 trials = 18 runs)

| Task | Condition | Success | Cost (median) | Tokens (median) |
|---|---|---:|---:|---:|
| A1 jarsrc | no tool | 2/2 | $0.0334 | 120226 |
| A1 jarsrc | tool + snippet | 2/2 | $0.0213 | 42762 |
| A1 jarsrc | tool, no snippet | 2/2 | $0.0387 | 143214 |
| A2 logsum | no tool | 2/2 | $0.0874 | 159630 |
| A2 logsum | tool + snippet | 2/2 | $0.0214 | 42766 |
| A2 logsum | tool, no snippet | **0/2** | $0.1166 | 334186 |
| A3 treesum | no tool | 2/2 | $0.0229 | 43012 |
| A3 treesum | tool + snippet | **0/2** | $0.0220 | 43000 |
| A3 treesum | tool, no snippet | 2/2 | $0.0220 | 42714 |

Two "failures" were read individually rather than just scored, because both turned out to be real findings, not noise:

- **A2, tool undiscovered (no snippet):** the model never found `logsum`, fell back to manually reading the log, got the top-3 error messages right but the exact last-timestamp subtly wrong in both trials (e.g. "00:26:01" vs. the true "00:26:11") -- right in substance, wrong in the precise number a tool would get exactly right, at 2.6-13x the token cost of the condition where the tool was used.
- **A3, tool documented AND on PATH, still not used:** both trials answered "largest file" wrong by conflating a file's **byte size** with its **line count** (said `jarsrc.py` at "10,573 lines"; it is actually 361 lines, 10,573 bytes -- verified directly against the trial's own working directory). `treesum` was on PATH and named in that trial's CLAUDE.md the whole time and was never invoked. Tool availability is not tool use.

### Experiment B: language steering, logsum only (4 conditions x 3 trials = 12 runs)

| Condition | Success | Language chosen | Cost (median) | Turns (median) |
|---|---:|---|---:|---:|
| B-a: no language stated | 3/3 | Python (3/3) | $0.0832 | 5 |
| B-b: "write it in Java" | 3/3 | Java (3/3) | $0.0769 | 4 |
| B-c: B-b + steering snippet | **2/3** | Java (3/3) | $0.1895 | **23** |
| B-d: unsteered, then "rewrite in Java" | 3/3 | Python then Java | $0.2275 | 10 |

Unsteered Haiku defaults to Python, consistently (3/3), and gets it right (8/8 conformance) every time. Bare "write it in Java" is the cheapest and most reliable way to get working Java in this pilot. The detailed steering snippet (B-c) is the genuine surprise: it did *worse* on both pass rate and turn count than the plain instruction. The single Java compile error caught across all 9 Java runs: a compact-source `void main(String[] args)` calling a helper that throws `IOException`, without `main` itself declaring `throws IOException` -- a real, specific, catalogable mistake, added to `steering/AGENTS-java-scripts.md`.

### Threats to validity (Phase 5 specific; full table in `results/agent/summary.md`)

Tiny N (2-3/cell); single cheap model (Haiku, may not represent stronger models' behavior); fixture tasks are simpler than real repos; token totals are dominated by fixed per-call system-prompt/tool-definition overhead (tens of thousands of tokens per call regardless of task); isolation from global config was verified by reading the relevant flags' documented behavior, not independently audited.

## Phase 5b: Sonnet instruction-following pilot (pilot, low N -- full detail in `results/agent-sonnet/summary.md`)

Same logsum-only Experiment B harness (`bench/agent/run_phase5b.py`, extended from Phase 5's `run_experiment_b.py`), rerun on **claude-sonnet-5** instead of Haiku, plus a new condition B-e (a conservative snippet requiring classic `public static void main`, explicitly forbidding compact source). jsonpick B-c was planned but dropped at the pilot's own budget gate and never run. **11 runs, 4,146,743 tokens, $3.2605 list-price-equivalent, no rate-limit rejections.**

| Condition | N | Conformance | Turns (median) | Tokens (median) | Cost (median) | Language(s) chosen |
|---|---:|---:|---:|---:|---:|---|
| B-a: no language stated | 2 | 2/2 | 8 | 257,024 | $0.2253 | Python, Python |
| B-b: "write it in Java" | 3 | 3/3 | 9 | 348,268 | $0.2888 | Java x3 |
| B-c: B-b + Haiku's exact snippet | 3 | **3/3** | 15 | 623,409 | $0.4249 | Java x3 |
| B-e: conservative snippet | 3 | 3/3 | 8 | 275,713 | $0.2506 | Java x3 |

Where Haiku's B-c (the detailed steering snippet) was both its least reliable condition (2/3) and its slowest (median 23 turns), Sonnet's B-c is fully reliable (3/3) at fewer turns (median 15) than Haiku's B-c -- the "more steering, less reliable" pattern does not replicate on a stronger model, though B-c remains the most expensive condition for both. The one instruction-compliance rule Sonnet broke consistently in B-c: all 3 trials named their file `logsum.java`, not the literal `Tool.java` the snippet's example command uses -- the snippet only implies the filename, never states it directly. B-e's snippet states the filename explicitly ("a single file named Tool.java") and all 3 B-e trials complied, while also scoring a clean 10/10 on every one of the 10 instruction-compliance rules checked (`bench/agent/check_compliance.py`), the only condition in either pilot to do so. Full detail, per-run tables, the snippet audit, and the compliance rubric: `results/agent-sonnet/summary.md`.

## Java feature deep-dive

Every JEP link below was fetched and its status/version verified directly against openjdk.org in this session (not from training-data recollection).

### JEP 330 -- Launch Single-File Source-Code Programs
- Status: Final, JDK 11. https://openjdk.org/jeps/330
- `java Tool.java` runs a single source file with no explicit `javac` step.
- Measured effect: 167.90ms mean for noop, ~130x slower than `java -jar` on the same program, because compilation happens fresh on every invocation with no cache.

### JEP 458 -- Launch Multi-File Source-Code Programs
- Status: Final, JDK 22. https://openjdk.org/jeps/458
- Extends JEP 330 to a directory of `.java` files; still zero build step.
- Verified directly: jarsrc split across `Tool.java`, `Scanner.java`, `VersionCompare.java`, `Util.java`, `Commands.java` (`tools/jarsrc/java/multi/`) runs correctly via `java Tool.java` alone.

### JEP 512 -- Compact Source Files and Instance Main Methods
- Status: Final, **JDK 25**. https://openjdk.org/jeps/512
- `void main()` instead of `public static void main(String[] args)`; implicit class declaration; `java.lang.IO` for `IO.println`/`IO.readln`; automatic access to all public `java.base` classes.
- Excerpt (`tools/noop/java/compact/Tool.java`):
  ```java
  void main(String[] args) {
      if (args.length > 0 && args[0].equals("--help")) {
          IO.println("usage: noop");
          IO.println("prints \"ok\" and exits 0");
          return;
      }
      IO.println("ok");
  }
  ```
- Real gotcha found while porting jarsrc to this style: a bare `ClassName::method` reference to the file's own implicit class fails to resolve inside a compact source file (`cannot find symbol: variable Tool`), even though the class is named `Tool` (matching the filename) and works fine when compiled normally. Fix: use a lambda (`(a, b) -> cmpVersion(a, b)`) instead of a method reference. This is exactly the kind of thing an LLM writing "simple" compact-source Java without steering would plausibly hit and need a retry to fix.
- Measured effect: no runtime overhead vs. classic style once packaged (compact-source noop and classic-source noop were not separately benchmarked post-jar-packaging since the packaged bytecode is equivalent; the *source-launch* cost is higher for compact -- 186.34ms vs. 167.90ms for classic -- likely from the extra implicit-class/IO machinery the compiler resolves per invocation).

### JEP 511 -- Module Import Declarations
- Status: Final, **JDK 25**. https://openjdk.org/jeps/511
- `import module java.base;` imports all packages a module exports (54 packages for `java.base`) in one line, without requiring the importing code to be part of a module itself.
- Verified directly: works with zero preview flags on JDK 25 (`java --source 25 /tmp/ModTest.java` and plain `java /tmp/ModTest.java` both succeeded).

### JEP 483 -- Ahead-of-Time Class Loading & Linking
- Status: Final, JDK 24 (Project Leyden). https://openjdk.org/jeps/483
- Two-step workflow: `-XX:AOTMode=record -XX:AOTConfiguration=app.aotconf` (one or more training runs) then `-XX:AOTMode=create -XX:AOTConfiguration=app.aotconf -XX:AOTCache=app.aot`; run with `-XX:AOTCache=app.aot`.
- Measured effect: noop 18.22ms (AOT) vs. 19.13ms (plain jar) -- modest ~5% win for a 1-class program; jarsrc small-fixture 27.96ms (AOT) vs. 32.59ms (jar) -- larger ~14% win as the program does more class loading. Consistent with JEP 483's own published claim (~42% on Spring PetClinic, a much larger classpath) that the benefit scales with how many classes the program actually loads.
- Real gotcha (see Phase 3 above): an AOT cache tied to a jar that has since changed (rebuilt, moved, touched) does not error -- it logs an `[error][aot]` block and silently falls back to normal loading, which is *slower* than not using AOT.

### JEP 514 -- Ahead-of-Time Command-Line Ergonomics
- Status: Final, **JDK 25**. https://openjdk.org/jeps/514
- Adds `-XX:AOTCacheOutput=app.aot`, a single flag that trains and creates the cache in one invocation (spawns a child process to do the create step). Verified directly; this run prints visible progress lines to stderr (`[jbang]`-style: "Launching child process... to assemble AOT cache", and a `Picked up JAVA_TOOL_OPTIONS` notice), which is real stderr noise on the cache-*building* invocation specifically (not on later cached runs).

### JEP 515 -- Ahead-of-Time Method Profiling
- Status: Final, **JDK 25**. https://openjdk.org/jeps/515
- Caches method-execution profiles from the training run so the JIT can optimize hot methods immediately on a production run, rather than re-profiling from scratch. Not separately isolated from JEP 483's class-loading benefit in this project's measurements (both are part of the same `-XX:AOTCache=` mechanism as used here) -- a finer-grained ablation was not attempted.

### JEP 519 -- Compact Object Headers
- Status: Final, **JDK 25** (promoted from experimental in JDK 24). https://openjdk.org/jeps/519
- `-XX:+UseCompactObjectHeaders`, no longer needs `-XX:+UnlockExperimentalVMOptions`. Not exercised in this project's benchmarks (none of these workloads are heap-footprint-bound at the small fixture scale); listed here because it directly answers "what's new in JDK 25 startup/footprint work" from the mission brief.

### JEP 400 -- UTF-8 by Default
- Status: Final, JDK 18. https://openjdk.org/jeps/400
- All 5 Java implementations rely on this implicitly (no explicit charset is set anywhere in the Java sources) and it was never a source of cross-platform output mismatches during conformance testing.

### JEP 444 -- Virtual Threads
- Status: Final, JDK 21. https://openjdk.org/jeps/444
- Not used by any implementation in this suite -- all CLIs are deliberately single-threaded per spec. Listed because the mission brief asked for verification; **not exercised**.

### JEP 485 -- Stream Gatherers
- Status: Final, JDK 24. https://openjdk.org/jeps/485
- Not used in this suite's implementations (none of the 5 CLIs' transformations needed a custom intermediate stream operation beyond what `Stream`'s built-ins or plain loops already cover). Listed for completeness; **not exercised**.

### Project Leyden
- https://openjdk.org/projects/leyden/ -- umbrella OpenJDK project (HotSpot + Core Libraries Groups) whose stated goal is improving Java startup time, time-to-peak-performance, and footprint. JEPs 483, 514, 515 (all used directly in this project) are Leyden deliverables; JEP 516 (Ahead-of-Time Object Caching with Any GC, targeted for JDK 26) was identified during this research as the next Leyden JEP in the pipeline but is **not available** on JDK 25 or 27 as installed here, so it was not tested.

### Other stdlib features used directly in this suite
- `java.nio.file` (`Files.walk`, `Files.walkFileTree`, `Path`) -- used in jarsrc (jar tree scanning) and treesum (directory walking with ignore-list pruning via `SimpleFileVisitor`/`FileVisitResult.SKIP_SUBTREE`).
- `java.util.zip` (`ZipInputStream`, `ZipEntry`) -- used in jarsrc to read `*-sources.jar` contents with zero extra dependencies; this is the single clearest "Java stdlib wins" data point in the whole suite, since Node has no equivalent (see below) and Go/Python both needed their own stdlib zip modules too (which they have, `archive/zip` and `zipfile` respectively -- so this one is a three-way tie between Go/Python/Java, with Node the outlier).
- Records and sealed interfaces (`record Ga(...)`, `sealed interface Step permits KeyStep, IndexStep`) plus record patterns in `switch`/`instanceof` (`if (step instanceof KeyStep(String key))`) -- used throughout jsonpick and jarsrc for the path-parsing AST and version/GA tuples; these read as compact and idiomatic as the equivalent Python dataclasses or Go structs, supporting H1.
- Text blocks were not needed by any of these 5 CLIs (no multi-line string literals in the spec's outputs).
- `HexFormat` was not used; hex handling in these CLIs (logsum's normalization, jarsrc's version parsing) never needed hex-to-bytes conversion, only string pattern matching.

## Cross-language gotchas found (not Java-specific, but part of the honest picture)

| Language | Finding |
|---|---|
| Node | No built-in ZIP-container reader. `node:zlib` decodes raw DEFLATE/gzip streams but does not parse the PKZIP central-directory format. jarsrc's Node implementation hand-rolls a ~60-line ZIP reader (EOCD scan, central directory walk, local file header parse, `zlib.inflateRawSync` for the compressed data). Python (`zipfile`), Go (`archive/zip`), and Java (`java.util.zip`) all get this for free from their stdlib. |
| Go | `encoding/json` does not preserve object key order on unmarshal into a generic map. jsonpick's Go implementation needed a fully custom, order-preserving JSON parser/serializer (`tools/jsonpick/go/value.go`) to satisfy the spec's "original document order" requirement -- Go's own "no free ordered JSON" data point, parallel to Java's "no JSON at all" one. |
| Java | No JSON parser in the stdlib at all (not an ordering nuance like Go's -- a complete absence). Two variants exist here: hand-rolled (2786-3077 tokens depending on CLI complexity) and JBang+Jackson (1478 tokens, but a non-stdlib dependency and one-time cold-start noise). |

## When NOT to use Java (per the evidence gathered here)

- **Uncached script-mode invocations** (`java Tool.java` with no launcher/AOT infrastructure) are 80-150x slower than Go and 8-15x slower than Python for trivial programs -- if an agent is going to shell out to a fresh Java source file once per turn with no caching layer, that is a real, measured cost, not a theoretical one.
- **JSON-heavy CLIs** without JBang available: the hand-rolled parser roughly doubles source size/token cost vs. Python or Node for a task those languages get for free.
- **Disk-constrained sandboxes**: a 10MB+ AOT cache per cached program is a real footprint cost that Go/Python/Node do not have an equivalent of (Go's static binary is 1.5-3.5MB total, not per-cache-on-top-of-a-separate-runtime).
- **Anywhere GraalVM native-image isn't already installed**: it worked cleanly here, but it is a second toolchain most agent sandboxes will not have preinstalled, unlike `java` itself which ships with any JDK.

## Reproduction steps

```
cd llm-cli-bakeoff
python3 scripts/gen_fixtures.py small fixtures/small
python3 tests/run_conformance.py                 # conformance gate, must pass before any benchmark
bash bench/quick.sh                               # ~2 minute subset
python3 scripts/token_cost.py                     # Phase 4
```

Full per-command reproduction (every hyperfine invocation used for the tables above) is in `bench/full.sh` and the exact JSON each number traces to is under `bench/results/raw/`.
