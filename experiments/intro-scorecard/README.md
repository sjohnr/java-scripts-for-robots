# Intro scorecard experiment

Data for the introduction's hypotheses (`slides/notes/01-introduction.md`): which language AI picks for a CLI when no language is given (condition A), and what it produces when asked for Java (condition B).

| Path | Contents |
|---|---|
| `prompts/01-experiment.md` | The brief given to Claude Code to build and run the experiment |
| `tasks/<cli>/SPEC.md` | Language-neutral task specs given to the agent |
| `tasks/CHANGES.md` | Every edit made to the bake-off specs, with reasons |
| `conformance/` | Cases, golden files and fixture generator, copied from `llm-cli-bakeoff` |
| `harness/` | `run.py` (agent runs), `classify.py`, `measure.py`, `analyze.py`, `validate_classifier.py` |
| `results/environment.md` | Auth mode, machine, toolchain versions, isolation notes |
| `results/raw/<run-id>/` | Per run: `prompt.txt`, `meta.json`, `transcript.jsonl`, `followup.jsonl`, `files/`, `measure.json`, `bench/` |
| `results/ledger.jsonl` | Every attempt (start and end), used for the run cap |
| `results/summary.md` | Verdicts and reports |
| `results/scorecard.json` | Flat verdicts and per-language Hypothesis 4 numbers for the slides |

## Requirements

`claude` (logged in with a subscription), `python3` with `tiktoken`, `hyperfine`, a JDK 25+ on `PATH` (for the AOT cache), `go`, `node`, `jbang`.

## Run IDs and order

A run ID is `<condition>-<task>-<model>-<replicate>`, for example `B-jsonpick-opus-3`. The order is a shuffle seeded with `20261010` (`common.SEED`): runs are sorted by `sha256("<seed>:<run-id>")`, so the order is reproducible and existing runs keep their relative order when the plan is extended. `meta.json` records the seed and the run's position in the default 84-run plan.

## Steps

```sh
cd experiments/intro-scorecard
python3 conformance/gen_fixtures.py small fixtures/small     # fixtures (not committed)
python3 harness/validate_classifier.py                       # classifier vs hand labels

python3 harness/run.py --dry-run                             # planned runs and counts
python3 harness/run.py --tasks logsum --runs 1               # calibration (6 runs)
python3 harness/run.py --parallel 4                          # everything not yet recorded (decision D8)
python3 harness/run.py --only B-logsum-opus-1                # one run
python3 harness/run.py --conditions B --tasks jsonpick --runs 6 --cap 108   # top-up (needs approval)

python3 harness/measure.py --baselines --bench               # bake-off Go/Python/Node baselines
python3 harness/measure.py --bench                           # every recorded run
python3 harness/analyze.py                                   # summary.md and scorecard.json
```

`run.py` options: `--models`, `--tasks`, `--conditions`, `--runs N` (per task, model and condition; default A=3, B=4), `--only <run-id>` (repeatable), `--cap` (default 84 counted attempts from the ledger), `--max-new N`, `--parallel N` (concurrent agents, launched in the shuffled order), `--probe-interval`, `--max-wait-hours`, `--no-measure`, `--dry-run`. With the default, each finished run is classified and conformance-tested immediately, and `analyze.py` regenerates `summary.md` and `scorecard.json` after every N completed runs. Benchmarks are never run here: `measure.py --bench` adds them afterwards, sequentially, reusing the earlier results. A run is skipped when `results/raw/<run-id>/meta.json` exists. An interrupted run is retried; the old attempt is renamed `<run-id>.aborted-<time>` (both in `raw/` and in the work directory) and still counts toward the cap.

`run.py` stops when the cap is reached, when `claude auth status` stops reporting `claude.ai`, or when a run reports an `apiKeySource` other than `none`. After two runs in a row (completion order) hit a rate or usage limit it pauses, probes the limited models every `--probe-interval` seconds and resumes (decision D9). Limit-hit runs are retried; the limited attempt is kept as `raw/<run-id>.limit-<time>` and does not count toward the cap. Superseded attempts (`"event": "superseded"` in the ledger) never count.

## What a run does

1. Creates `~/.cache/intro-scorecard/work/<run-id>/project/` with only `SPEC.md`, and `caches/` for package managers (override the base with `INTRO_SCORECARD_WORK`).
2. Runs `claude -p "<prompt>" --model <alias> --output-format stream-json --verbose --setting-sources project --strict-mcp-config --permission-mode acceptEdits --allowedTools Read,Write,Edit,Glob,Grep,Bash --max-budget-usd 5.00` in that directory with a 15-minute wall-clock limit (process group killed on timeout). `ANTHROPIC_*` and `CLAUDE*` variables are removed; package caches point into `caches/`.
3. Resumes the session once with "Reply with only the shell command, run from this directory, that runs the tool." Its cost is recorded under `followup` and excluded from generation cost.
4. Records the sizes of dependency and build-output directories, then copies the remaining files to `results/raw/<run-id>/files/`.

The work directories are kept: measurements run the program where the agent built it, with its own caches. If a work directory is lost, `measure.py` falls back to the recorded copy and notes it.

## Measurements (`measure.py`)

| Measurement | Method |
|---|---|
| Run command | The follow-up reply, first line, fences and backticks removed; trailing placeholders such as `[FILE]` dropped. A manual correction can be placed in `raw/<run-id>/run_command.override.json` (`{"run_command": ..., "reason": ...}`) |
| Language | `classify.py`: language of the entry point from the run command, following wrapper scripts (up to two levels) and shebangs; native executables are attributed to the sources that built them. Disagreement with the dominant source language, or an unresolved command, sets a review flag. Manual decisions go in `raw/<run-id>/review.json` |
| Conformance | Every case in `conformance/cases.json` for the task, run as `bash -c '<run command> "$@"'` from the project directory, compared with the golden files using the rules of `run_conformance.py` (byte-exact stdout, stderr and exit code; `--help` and the bad-JSON case compare exit code only). One `--help` warm-up (10-minute limit) precedes the cases so a first-run compile or download is not a failure; each case has 60 s |
| Java style | `classify.py` static checks; evidence for each value is stored in `measure.json` |
| Startup | `hyperfine --warmup 3 --min-runs 20 --shell bash '<run command> --help'` from the project directory, with the run's environment. Median reported |
| Optimized startup | Passing Java programs only. Copy of the project packaged as a jar with the project's build (Maven `package` + `dependency:copy-dependencies`, Gradle `installDist`/`jar`, `jbang export fatjar`), else `javac` + `jar`. AOT cache from one training run (`-XX:AOTCacheOutput`, the task's basic conformance case), then `hyperfine` on `java -XX:AOTCache=... -cp <jar> <Main> --help`. The same jar without the cache is also measured |
| Source size | tiktoken `o200k_base` tokens (a proxy, not Claude's tokenizer) of source files: code files excluding tests, examples, build files, docs and build output. Build-file and test tokens reported separately |
| Disk | Source and build files + build output in the project + the run's caches, excluding runtime downloads (JBang JDKs, Go toolchains) and the Go compiler cache (`GOCACHE`, reported separately) |

Classifier file categories: **source** (code by extension, or extensionless with a shebang), **test** (`test*/`, `examples/`, `*_test.*`, `*Test.java`, ...), **build** (`pom.xml`, `build.gradle*`, wrappers, `go.mod`, `package.json`, ...), **output** (binaries, `.class`, `.jar`, and directories such as `build/`, `target/`, `node_modules/`, `.venv/`), **doc**, **data**.

Launch values follow the notes; two additions are flagged: `build tool run` (for example `./gradlew run`) and `native binary`. `jbang Tool.java` counts as source launch with build JBang. A wrapper script is followed to the launch it performs and recorded as evidence.

## Analysis (`analyze.py`)

Every expectation in the notes, pooled across models, with 95% Wilson intervals and the notes' verdict rule. Interpretations applied where an expectation is not a plain proportion:

| Expectation | Evaluated as |
|---|---|
| Hypothesis 1 shares | Denominator: condition A runs with a classified program |
| Hypothesis 2, Java in every run | Exceptions; supported only with zero |
| Hypothesis 2, 85% pass | Denominator: condition B runs that produced Java |
| Hypothesis 3, four combinations | Count of distinct (build, launch) pairs; no interval |
| Hypothesis 3, no combination over two-thirds | Wilson interval of the most common combination's share |
| Hypothesis 4, startup | Exceptions among passing Java programs (both conditions): not slower than every one of the task's Go, Python and Node medians |
| Hypothesis 4, source size (median) | Share of Java programs above 1.25 x the task's smallest per-language median, against 50% (the median exceeds the bar exactly when more than half the programs do) |
| Hypothesis 4, disk | Exceptions among Java programs with dependencies: not larger than the median competing program for the task |
| Hypothesis 4, optimization (median) | Share of AOT-optimized Java programs within 2 x both the Python and Node medians, against 50% |

Hypothesis 4 is evaluated against two competitor sets: the bake-off implementations and the passing non-Java programs from condition A.
