# Mission

I am preparing a conference talk with this premise: modern Java (source-file launch, compact source files, module imports, AOT/CDS caching) makes it practical to write small, fast, familiar CLI tools that AI agents call themselves, instead of letting an LLM default to Python or flail through ls/find/grep/awk/head/tail. These CLIs exist to save an agent tokens and tool calls (example: reading library source from a local Gradle cache via *-sources.jar files in one call).

Your job is to test this premise empirically and produce:
1. A reproducible bake-off repo with identical small CLIs in Java, Go, Python, and JavaScript (Node).
2. A thorough REPORT.md with measured results, the Java features that made it work (with reference links), and honest analysis.
3. Runnable artifacts I can play with to compare the tools myself.

Be objective. I want the truth, including where Java loses. Do not cherry-pick. Do not use emoji in any markdown file (encoding problems). Prefer tables over long prose in all reports.

# Hypotheses to confirm or refute

| ID | Hypothesis | Evidence needed |
|---|---|---|
| H1 | Modern Java scripts are competitive in size and readability with Python/JS | Source bytes, lines, and tokens per implementation |
| H2 | JVM startup is the weak point, but AOT cache / CDS / tuning closes most of the gap | Startup medians per mode vs Go/Python/Node |
| H3 | Purpose-built CLIs cut agent tokens and tool calls vs ad-hoc shell | Real headless agent runs, with and without the CLI |
| H4 | With steering, an LLM writes working modern Java scripts at acceptable extra cost | Language-steering experiment |
| H5 | Output hygiene (stderr noise, warnings, banners) materially affects token cost | Tokenized stderr/stdout per mode |

# Parameters (use these defaults; ask only if blocked)

| Parameter | Default |
|---|---|
| JDK | Latest GA JDK and latest LTS (25) if both obtainable via SDKMAN or Temurin download; record exact versions. Primary results on the newest. |
| Go / Node / Python | Latest stable available; record versions |
| Benchmark runner | hyperfine (install if missing); fall back to your own harness |
| Runs | 30 runs + 3 warmup for small workloads; 10 runs + 2 warmup for large |
| Agent experiment trials | 5 per condition; hard cap of 60 headless agent runs total unless I raise it |
| Agent model | The same fixed model for every run; record it |
| Working dir | ./llm-cli-bakeoff |

# Phase 0: Environment and verification

- Detect OS/CPU/RAM. Install or locate JDK(s), Go, Node, Python, hyperfine. Optional extras if easily available: JBang, GraalVM native-image. If something cannot be installed, say so in the report and use the documented fallback.
- Record all versions and the exact install method in results/environment.md.
- Before relying on ANY Java feature claim below, fetch the official page and verify status (preview/final/removed) for the JDK you actually use. My recollections may be wrong; correct them in the report.

# Phase 1: Test CLI suite (identical behavior in all four languages)

Write a precise behavior spec for each CLI in spec/ FIRST (arguments, exact output format, sorting and tie-breaking rules, exit codes, error message text). Output must be byte-identical across languages. Stdlib only in the primary implementations (dependencies are a finding, not a shortcut).

| CLI | Behavior | Exercises |
|---|---|---|
| noop | Prints "ok", exit 0 | Pure startup floor |
| jarsrc | Against a Gradle-cache-like tree: `find <fqcn-or-glob>` lists matches with group:artifact:version; `cat <fqcn> [--lines A:B]` prints source from the highest version's *-sources.jar (numeric-segment version comparison, specified precisely); `ls <group:artifact[:ver]>` lists entries. Not-found errors are one line and say what to try next (prefix-match suggestions, capped at 3). | Zip reading, filesystem walking, error design |
| logsum | Streaming log summarizer from file or stdin: counts by level, first/last timestamp, top N normalized error messages (digits, hex, UUIDs normalized by a specified regex), `--since`, `--level`. | Streaming IO, regex, hash aggregation |
| treesum | Compact repo overview: file counts and LOC by extension, top N largest files, fixed ignore list (.git, node_modules, build, .gradle, target, etc.). | Directory walking, throughput |
| jsonpick | Extract values by path (`a.b[0].c`) from JSON file/stdin; `--shape` prints a depth-limited type/array-length summary instead of dumping the document. | Parsing without a stdlib JSON in Java |

For jsonpick, Java gets two variants: (a) hand-rolled minimal parser, (b) JBang with `//DEPS` (Jackson or Gson), if JBang is obtainable. Report the effort, size, and startup cost of each. This is the honest "Java has no JSON in the JDK" data point.

Fixtures: write one deterministic, seeded generator (any language) producing:
- Small (typical agent call): about 10 jars, 1k-line log, 200-file tree, 5 KB JSON.
- Large: about 2,000 jars, 2M-line log, 50k-file tree, 50 MB JSON.
  Do not commit large fixtures; commit the generator and checksums.

Conformance suite (tests/): language-agnostic runner that executes every implementation of every CLI over a shared case list (including error cases and edge cases) and diffs stdout, stderr, and exit code against golden files. Nothing is benchmarked until conformance passes for all implementations. Have parity reviewed independently of whoever wrote each implementation (idiomatic per language, no heroic per-language tuning, same algorithms, single-threaded by default; parallel variants only as clearly labeled extras).

# Phase 2: Java execution modes to compare

Implement the Java CLIs so each mode below is a real, runnable, documented way to run the same tool. Verify feature availability per JDK.

| Mode ID | Description |
|---|---|
| java-src | `java Tool.java` source-launch, classic `public static void main` (JEP 330) |
| java-compact | Compact source file + instance main + `java.io.IO` + module import declarations (JEP 512, JEP 511; verify status on the JDK used) |
| java-shebang | Executable script with `#!` line, invoked directly |
| java-multi | One CLI split across several files, launched via multi-file source-code programs (JEP 458), no build step |
| java-class | Precompiled classes, `java -cp` |
| java-jar | Executable jar |
| java-jar-tuned | Jar plus flags, each measured individually then combined: -Xshare:auto, -XX:TieredStopAtLevel=1, -XX:+UseSerialGC, -XX:-UsePerfData, others you find relevant |
| java-aot | Jar plus AOT cache from Project Leyden (JEP 483, JEP 514 one-step creation, JEP 515 method profiling; verify which apply), and classic AppCDS dynamic archive for comparison |
| java-src-aot | Investigate whether CDS/AOT can benefit source-launch mode at all. Document what works, what does not, and why. |
| java-launcher | Your own tiny launcher/shim: hash the source, compile once into ~/.cache, build jar and AOT cache, then exec. Goal: script ergonomics with near-binary startup. Measure warm-path overhead and first-run cost; make it a reusable artifact. |
| java-native | GraalVM native-image if obtainable. Reference point only, not the main story. |

Other languages: Go (`go run`, and built binary; note size with and without -ldflags="-s -w"), Python (`python3 tool.py`), Node (`node tool.mjs`, plus the Node compile cache if the version supports it).

# Phase 3: Performance benchmarks

Metrics per (CLI x scenario x mode): wall time (median, mean, stddev, min, max, p95), user+sys CPU, peak RSS, and first-run (cold) vs warm.
Derived metrics:
- Startup floor (noop) per mode.
- "Agent session cost": 50 sequential small-workload invocations, total wall time.
- Break-even: at what input size or call count does each Java mode overtake or lose to Python/Node/Go?
- 1-CPU and 2-CPU runs (taskset on Linux, or the closest available equivalent; if not possible on this OS, say so) for the key modes. Agent sandboxes are often small.
- Stderr/stdout noise per mode: any warnings, banners, JAVA_TOOL_OPTIONS notices, CDS/AOT warnings. Record verbatim.
- Artifact sizes: jar, AOT cache, native binary, Go binary.
  Hygiene: do not run benchmarks while anything else heavy is running (including parallel subagents); record machine load; save raw hyperfine JSON to results/raw/; every number in the report must trace to a file there.

# Phase 4: Token-cost analysis (static)

Tokenizer: use the Anthropic token-counting API if ANTHROPIC_API_KEY is set; otherwise use tiktoken (o200k_base) as a proxy and label it clearly as a proxy everywhere it appears.
Count tokens (plus bytes and lines) for:
- Each implementation's source code (the "cost for an LLM to write or read the tool").
- Each CLI's --help output.
- Typical successful outputs and typical error outputs.
- Stderr noise per Java mode.
- A scripted "naive shell flailing" baseline for the jarsrc and treesum tasks (the ls/find/grep/unzip/head sequence a typical agent would use): total output tokens and command count vs the single CLI call.

# Phase 5: Agent experiments (spends real API money)

STOP AND ASK ME before starting this phase. Show the exact planned runs, trial counts, model, and estimated cost. Proceed only after I confirm.
Use headless Claude Code (`claude -p`, with JSON output for usage; check `claude --help` for the exact flags). Run each trial in a clean temp directory with fixtures, isolated from my global config/CLAUDE.md/memory as far as the CLI allows (document how). Record per run: input/output/cache tokens, cost, turns, tool calls, wall time, and success (verified programmatically).

Experiment A: does the CLI pay off?
- Tasks (4 or more): e.g. "show the signature and Javadoc of method M in library L using the local cache", "summarize errors in this log", "where is X defined in this repo".
- Conditions: (1) no custom tool, (2) with the Java CLI plus a short instruction snippet, (3) with the CLI available but discoverable only via --help.
- Report: tokens, cost, tool calls, time, success rate, with variance.

Experiment B: steering an LLM to Java.
- Give the same CLI spec and the conformance suite. Conditions: (a) no language stated (record the default language chosen), (b) "write it in Java", (c) Java plus a steering snippet (Java 25 single-file, `java Tool.java`, compact source, no Maven/Gradle scaffold, stdlib only, and any further rules you discover), (d) start with (a), then a correction turn "rewrite in Java".
- Metrics: turns to a passing conformance run, tokens, cost, pass rate, lines/tokens of output, language chosen.
- Catalog every recurring LLM-Java mistake (old-style boilerplate, unneeded build files or dependencies, preview flags, wrong-version APIs, etc.) as a table with frequency.
- Deliverable: steering/AGENTS-java-scripts.md, the snippet that measurably worked best.

# Phase 6: Java feature research

For every Java feature that actually mattered in the implementations or measurements, write a section with: what it is, JDK version introduced and finalized, official link (openjdk.org/jeps/N, docs.oracle.com API docs, dev.java, jbang.dev, openjdk.org/projects/leyden as applicable), a minimal excerpt from our code, and the measured effect. Candidates to verify (numbers are recollection, verify): JEP 330, 458, 512, 511, 483, 514, 515, 519 (compact object headers), 444 (virtual threads), 400 (UTF-8 default), 485 (stream gatherers), plus java.nio.file, java.util.zip, HexFormat, records, pattern-matching switch, text blocks. Note anything new in the newest JDK that I did not list. Fetch each link and confirm it resolves.

# Deliverables

| Path | Contents |
|---|---|
| REPORT.md | Executive summary; hypothesis verdicts (H1-H5); methodology and environment; results tables per phase; Java feature deep-dive with links; gotchas; when NOT to use Java; threats to validity; reproduction steps |
| TALK_NOTES.md | 5-7 headline numbers, 5 surprising findings, slide-ready tables, suggested live-demo scripts with expected output and timing |
| spec/ | Behavior specs for each CLI |
| tools/<cli>/{java,go,python,node}/ | Implementations, including every Java mode |
| tests/ | Conformance runner, cases, golden files |
| bench/ | quick.sh (about 2 minutes), full.sh, raw results in results/raw/, processing scripts |
| compare.sh | `./compare.sh <cli> <args...>` runs every implementation side by side: output equality, timing, and token counts |
| steering/ | AGENTS-java-scripts.md and Experiment B prompts |
| README.md | One-screen quick start: setup, run conformance, run compare.sh, run quick bench |

Include simple charts (SVG or PNG, generated by script) in the report only if tooling is available; every chart must have a table that stands alone.

# Working rules

- Plan first, keep a task list, commit at the end of each phase.
- Conformance before benchmarks; benchmarks before conclusions.
- No fabricated numbers. Separate "measured" from "recalled or assumed". State N and variance. Report negative results plainly.
- If a tool or feature is unavailable, record the fallback and how it limits the conclusion.
- You may parallelize implementation work, but one agent owns the conformance suite and the final numbers, and no benchmarking happens while parallel work is running.
- When finished, reply with: the top 5 findings, the repo path, and the exact quick-start commands.