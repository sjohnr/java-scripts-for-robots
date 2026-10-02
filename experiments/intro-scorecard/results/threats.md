## Threats to validity

| Threat | Detail | Effect on the verdicts |
|---|---|---|
| N per cell | 3 runs per task and model in condition A, 4 in condition B (84 in total). Pooled N is 36 (A) and 48 (B); per-model and per-task cells are too small for their own verdicts | Wilson intervals are wide; a proportion needs to be extreme to clear a threshold. The per-model tables are supporting detail only |
| Prompt wording | One fixed sentence per condition, no system-prompt changes. "Build the `<cli>` command-line tool described in SPEC.md" may itself suggest a script-like deliverable | Results describe this prompt, not every way of asking |
| Spec edits | Language and approach hints removed (`tasks/CHANGES.md`); everything the cases check kept verbatim | Some residual cues remain by design (jarsrc is about jars and `.java` files; treesum's ignore list names ecosystem directories) |
| Spec lines that are easy to misread | `object{<N keys>}` (golden `object{2 keys}`, 8 runs printed `object{2}`, Python and Java alike) and `unknown flag: <flag>` (golden has no `error:` prefix; 2 Java runs added one). Comparison is byte-exact, as in the bake-off | Lowers the pass rate (Hypothesis 2) for reasons unrelated to language; all 10 failing runs fail only one of these two cases |
| Isolation limits | User settings, memory and MCP servers excluded; built-in skills and plugins remain; network allowed; the agent sees everything installed on the machine (`results/environment.md`) | Models run at default effort, not the user's setting |
| Installed toolchains | JDK 25 GA (pinned by the harness), Python 3.13, Node 24, Go 1.27, JBang on PATH; no Maven, Gradle, Rust, uv, Bun or Deno | Could steer choices (for example away from Maven/Gradle in condition B) |
| Run command | Taken from a follow-up turn and normalized (placeholders and a trailing `--help` removed; every change recorded in `measure.json`) | A wrong reading would cause a false conformance failure; all normalized commands were checked by hand |
| Parallel generation | Runs 13-84 (attempts) ran 4 at a time (decision D8) | Per-run wall time is inflated; no effect on code, conformance, size or benchmarks |
| Usage-limit retries | 5 attempts cut off by the Pro session limit were retried (decision D9); the cut-off attempts are kept as `results/raw/*.limit-*` | A retried run is a fresh sample of the same cell |
| Tokenizer proxy | Source size uses tiktoken `o200k_base`, not Claude's tokenizer | Ratios between languages are more reliable than absolute counts |
| Machine load during benchmarks | Benchmarks ran sequentially after generation, on the author's laptop with normal background activity; load average recorded at the start of every run's benchmark (`load_avg_at_bench_start`) and hyperfine's spread kept | Small absolute differences (a few ms) are within noise; the Java vs. Python gap is far larger |
| Disk definition | Project files + per-run caches, excluding runtime downloads and the Go compiler cache (decision D6) | Go and Java-with-dependencies totals depend on this choice; breakdowns are in `measure.json` |
| Hypothesis 4 rules | Median expectations judged as shares against 50%; startup and disk as "every" (decisions D5 and "Decided" table) | Documented interpretation; other readings could change borderline verdicts |
