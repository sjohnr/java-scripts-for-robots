# Intro scorecard experiment

Build and run the experiment behind the talk's introduction: which language AI picks for a CLI when no language is given, and what happens when it is asked for Java. Work in `experiments/intro-scorecard/`. Do not use emoji in any markdown file. Prefer tables over long prose.

## Source of truth

The hypotheses, expectations, thresholds and definitions are in `slides/notes/01-introduction.md`. Read it first. This brief defines the procedure; if the two disagree, stop and ask me.

## Existing assets to reuse

| Asset | Location | Use |
|---|---|---|
| Task specs | `experiments/llm-cli-bakeoff/spec/` (`00-conventions.md`, `02-jarsrc.md`, `03-logsum.md`, `04-treesum.md`, `05-jsonpick.md`) | Basis for the task prompts (see Step 1) |
| Conformance cases | `experiments/llm-cli-bakeoff/tests/cases.json`, `tests/golden/`, `tests/run_conformance.py` | Pass/fail for every generated program |
| Fixtures | `experiments/llm-cli-bakeoff/scripts/gen_fixtures.py small` | Inputs for conformance and benchmarks |
| Baseline implementations | `experiments/llm-cli-bakeoff/tools/<cli>/{go,python,node}/` | Go, Python and Node baselines for Hypothesis 4 |
| Earlier harnesses | `experiments/llm-cli-bakeoff/bench/agent/`, `experiments/javaw/generate` | Patterns for isolation, recording and resuming |

Do not modify anything under `experiments/llm-cli-bakeoff/` or `experiments/javaw/`. Copy what you need.

## Parameters

| Parameter | Value |
|---|---|
| Models | `haiku`, `sonnet`, `opus` aliases. Record the exact model ID from each run's output. |
| Tasks | `logsum`, `treesum`, `jsonpick`, `jarsrc` |
| Condition A | Task prompt only. 3 runs per task per model (36 runs). |
| Condition B | Task prompt followed by the sentence "Write it in Java." 4 runs per task per model (48 runs). |
| Hard cap | 84 runs, including calibration, failed and aborted runs. Top-up runs (Step 6) need my approval and are capped at 24 more. |
| Per-run limits | 15-minute wall-clock timeout enforced by the harness, plus a spending cap if the CLI supports one (check `claude --help`; the earlier harness used `--max-budget-usd`). Record any run that hits a limit. |
| Execution | Sequential. Interleave the order across models, tasks and conditions (for example a shuffled order with a fixed, recorded seed) so drift in load or limits does not bias any one cell. |
| Resumable | Skip any run whose result already exists, so the experiment can be spread across sessions and limit resets. |

## Step 0: Auth check (mandatory)

I use a subscription and do not want per-token API billing. Check how `claude` is authenticated. If `ANTHROPIC_API_KEY` is set, run every trial with it unset. If you cannot confirm the runs use the subscription, stop and ask me. Record the auth mode (no secrets) in `results/environment.md`, along with OS, hardware, and the versions of `claude`, `java`, `javac`, `jbang`, `mvn`, `gradle`, `go`, `python3`, `node` and `hyperfine` found on the PATH. Agents can see installed toolchains, so this is part of the experimental setup.

## Step 1: Task prompts

Create language-neutral copies of the specs in `tasks/<cli>/SPEC.md` (conventions merged into each task's spec, keeping only the rules that apply to that task). The originals were written for a multi-language bake-off and contain text that would bias the result. Remove at least:

| Remove | Example |
|---|---|
| Mentions of specific languages, runtimes or libraries | "no JVM/Node/Go/Python banners", "Java has no JSON in the JDK", the `jsonpick` "Java variants" section, "no argparse/commander/picocli" |
| Constraints on implementation approach | "Argument parsing is hand-rolled", references to dependency footprint |
| References to the bake-off itself | "Phase 3", "measured finding", "cross-language" |

Keep every behavior the conformance cases check (output formats, exit codes, error messages, `--help`). `jarsrc` is inherently about jars and `.java` files; keep that, since it is the Java-flavored control task. Record every change in `tasks/CHANGES.md` (original text, new text, reason) so I can review it.

The prompt sent to the agent:

| Condition | Prompt |
|---|---|
| A | ``Build the `<cli>` command-line tool described in SPEC.md.`` |
| B | ``Build the `<cli>` command-line tool described in SPEC.md. Write it in Java.`` |

Do not add anything else to the prompt: no file names, no "single file", no request to explain the choice of language.

## Step 2: Harness

| Requirement | Detail |
|---|---|
| Working directory | A fresh directory outside the repository for every run (so no parent `CLAUDE.md` applies), containing only `SPEC.md`. |
| Isolation | Exclude user-level settings, memory and MCP servers as far as the CLI allows (for example `--setting-sources project` and `--strict-mcp-config`; verify the flags). Document what was and was not isolated. |
| Permissions | `--permission-mode acceptEdits --allowedTools Read,Write,Edit,Glob,Grep,Bash`. Network access is allowed so the agent can fetch dependencies if it chooses to. |
| Dependency caches | Point every package cache at a per-run directory so dependency disk use can be measured and my real caches are not touched: `GRADLE_USER_HOME`, `MAVEN_OPTS=-Dmaven.repo.local=...`, `JBANG_DIR`, `PIP_CACHE_DIR`, `npm_config_cache`, `GOPATH`/`GOMODCACHE`, and any others you find necessary. Do not change `HOME`. |
| Run command | After the agent finishes, resume the same session with one follow-up turn: "Reply with only the shell command, run from this directory, that runs the tool." Use it to invoke the program. Record this turn's cost separately and exclude it from generation cost. |
| Recording | Per run, in `results/raw/<run-id>/`: the composed prompt, `meta.json` (model ID, task, condition, seed position, start and end time, turns, tokens, cost, exit status, limit hits, run command), the full stream-json transcript, and a copy of the generated files. Exclude dependency caches and build output from the copy, but record their sizes first. |
| Dry run | `--dry-run` prints the planned run list and counts without running anything. |
| Arguments | `--models`, `--tasks`, `--conditions`, `--runs N`, `--only <run-id>`, so I can rerun or extend without code changes. |

## Step 3: Measurements (no model tokens)

| Measurement | Applies to | Method |
|---|---|---|
| Language | A and B | Language of the entry point, from file types and the run command. Flag anything ambiguous for manual review. |
| Conformance | A and B | Every case in `cases.json` for that task, run through the recorded run command against the fixtures and compared with the golden files, using the same comparison rules as `run_conformance.py`. A run passes only if all cases pass. |
| Java style | B (and any Java in A) | Every dimension in the Hypothesis 3 definitions table. Use static checks (build files, shebang lines, `void main` without `static`, `import module`, file count, declared dependencies) and record the evidence for each value. |
| Startup | Every passing program, plus the baselines | `hyperfine --warmup 3 --min-runs 20` on the task's `--help` invocation, using the recorded run command. |
| Optimized startup | Every passing Java program | Package as a jar (use the project's own build if it has one, otherwise `javac` and `jar`), create an AOT cache with a training run, and benchmark with the cache. Record any program that cannot be packaged and why. |
| Source size | Every passing program, plus the baselines | Tokens in source files, excluding tests, build files and build output (tiktoken `o200k_base`, labeled as a proxy). Report build-file tokens separately. |
| Disk | Every passing program, plus the baselines | Source + build output + per-run dependency cache, excluding the language runtime. |

The classifier is the riskiest part. Validate it before calibration against the existing Java variants in `experiments/llm-cli-bakeoff/tools/*/java/` and the results in `experiments/javaw/results/`, and include the validation table in the report.

## Step 4: Calibration (6 runs, then stop)

Run `logsum` in conditions A and B once per model. Report per run: tokens, cost, turns, wall time, language, pass/fail, run command, and classifier output. Then project the total for the full 84 runs and **stop for my approval**. Include anything that should change before the main runs (prompt problems, isolation leaks, classifier mistakes). If the prompts and harness do not change, these 6 runs count toward the totals.

## Step 5: Main runs

The remaining 78 runs, in the recorded shuffled order. Stop and report immediately if two consecutive runs hit a rate or usage limit, the auth mode changes, or the hard cap is reached. Partial results are fine; write them up.

## Step 6: Analysis and verdicts

For every expectation in `slides/notes/01-introduction.md`, report:

| Column | Content |
|---|---|
| Expectation | As written in the notes |
| Observed | Count and share, pooled across models |
| 95% interval | Wilson interval for proportions. For "every" expectations (Hypothesis 2 Java share, Hypothesis 4 startup), report the number of exceptions instead. |
| Verdict | Supported, not supported, or inconclusive, using the rule in the notes' Method table |

If a verdict is inconclusive and up to 24 more runs (2 per task per model, in the condition concerned) could plausibly resolve it, propose the top-up with its projected cost and wait for my approval.

Also report:
- Per-model breakdown of every expectation, in a separate table, labeled as supporting detail.
- Per-task language distribution for condition A, noting whether `jarsrc` differs.
- The full style-combination table for condition B, with counts.
- `jsonpick` in condition B: dependency vs. hand-written parser, with source size and disk for each.
- Hypothesis 4 comparisons against both baseline sets (bake-off implementations and condition A outputs).
- A threats-to-validity table: N per cell, prompt wording, spec edits, isolation limits, installed toolchains, tokenizer proxy, machine load during benchmarks.

## Deliverables

| File | Content |
|---|---|
| `README.md` | How to run the harness, measurements and analysis |
| `tasks/` | Language-neutral specs and `CHANGES.md` |
| `results/environment.md` | Auth mode, machine, toolchain versions, isolation notes |
| `results/raw/` | Everything recorded per run |
| `results/summary.md` | Verdict tables and the reports listed in Step 6 |
| `results/scorecard.json` | The verdict and observed values for each expectation, plus the per-language Hypothesis 4 numbers, in a flat structure the slides can import |
