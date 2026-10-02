# Calibration

6 runs of `logsum` (conditions A and B, once per model), 2026-10-11 00:08 to 00:16 UTC. Prompts and agent-facing harness settings unchanged afterwards, so these runs count toward the totals (attempts 1-6 of 84).

## Per run

| Run | Model ID | Turns | Output tokens | Cache read / create | Cost (USD, notional) | Wall (s) | Language | Pass | Run command | Classifier |
|---|---|---|---|---|---|---|---|---|---|---|
| A-logsum-haiku-1 | claude-haiku-5-5 | 13 | 20,370 | 249,707 / 28,804 | 0.018 | 88.5 | python | 9/9 | `./logsum` | Python shebang script; tests in `tests/` excluded |
| A-logsum-sonnet-1 | claude-sonnet-5-5 | 4 | 3,284 | 43,846 / 8,909 | 0.073 | 22.3 | python | 9/9 | `./logsum` | Python shebang script |
| A-logsum-opus-1 | claude-opus-5-5 | 5 | 5,745 | 83,346 / 11,919 | 0.227 | 51.6 | python | 9/9 | `./logsum` | Python shebang script |
| B-logsum-haiku-1 | claude-haiku-5-5 | 24 | 30,410 | 874,849 / 41,370 | 0.032 | 141.9 | java | 9/9 | `bin/logsum` | Make / jar (wrapper `bin/logsum` runs `java -jar build/logsum.jar`); classic; classic imports; 5 files; no deps; no optimization |
| B-logsum-sonnet-1 | claude-sonnet-5-5 | 5 | 5,980 | 66,174 / 13,876 | 0.122 | 39.6 | java | 9/9 | `./logsum` | none / javac + java (wrapper compiles `Logsum.java` to `out/` on first run, then `java -cp out Logsum`); classic; single file |
| B-logsum-opus-1 | claude-opus-5-5 | 10 | 11,000 | 168,029 / 19,272 | 0.408 | 109.3 | java | 9/9 | `./logsum` | build script / jar (`build.sh` runs `javac --release 17` + `jar`; wrapper runs `java -jar logsum.jar`); classic; single file |

Follow-up turn cost (excluded): $0.0007 to $0.022 per run. No run hit a limit; every run reported `apiKeySource: none`.

## Measurements

| Run | Startup median (ms) | Jar, no AOT (ms) | Jar + AOT cache (ms) | Source tokens | Build-file tokens | Disk |
|---|---|---|---|---|---|---|
| A-logsum-haiku-1 | 14.8 | | | 1,457 | 0 | 18.0 KB |
| A-logsum-sonnet-1 | 14.7 | | | 1,229 | 0 | 4.4 KB |
| A-logsum-opus-1 | 14.8 | | | 1,500 | 0 | 5.7 KB |
| B-logsum-haiku-1 | 34.2 | 30.1 | 26.2 | 2,727 | 144 | 41.1 KB |
| B-logsum-sonnet-1 | 34.0 | 30.7 | 26.2 | 2,191 | 0 | 18.6 KB |
| B-logsum-opus-1 | 35.7 | 31.5 | 26.4 | 2,684 | 85 | 20.3 KB |
| Bake-off baselines (logsum) | Go 1.6, Python 13.2, Node 20.7 | | | Go 2,052, Python 1,485, Node 1,341 | | Go 3.0 MB, Python 5.6 KB, Node 4.5 KB |

## Projection for 84 runs

| Item | Value |
|---|---|
| Calibration actual | $0.88 notional, 7.6 min agent wall time |
| 84 runs at logsum rates | $12.8 notional, about 110 min agent time |
| 84 runs at 1.5x (jsonpick and jarsrc specs are longer) | about $19 notional, about 165 min agent time |
| Follow-up turns | about $0.6 |
| Measurement | about 1 min per passing run (warm-up, cases, hyperfine), plus AOT packaging for Java |
| Highest single run so far | $0.41 (Opus, B), well under the $5.00 per-run cap |

Notional cost is not billed on the subscription, but usage limits apply (Pro plan). Opus carries about 75% of the projected usage, so a limit stop is plausible; the harness resumes where it stopped.

## Changes made after calibration (no effect on what the agents see)

| Change | Reason |
|---|---|
| Follow-up cost = reported cost minus generation cost | A resumed session reports cumulative `total_cost_usd` (its token counts are per turn). The 6 records were patched and marked |
| Classifier: wrapper paths that use the script's directory (`$(dirname "$0")/...`) resolve relative to the script | B-logsum-haiku-1 wrapper |
| Classifier: `Makefile` with `javac`/`jar` -> build "Make"; a shell build file with `javac` -> build "build script" | Seen in 2 of 3 B runs; not in the notes' build values |
| Analysis: skip non-directories in `results/raw/` | Crash on `.gitignore` |
| `results/raw/.gitignore` re-includes `bin/` and `out/` | The repository ignores them; B-logsum-haiku-1 put its launcher in `bin/` |

## Decisions needed before the main runs

| # | Issue | Options |
|---|---|---|
| 1 | Build values outside the notes' table (Make, build script) appeared in 2 of 3 Java runs | (a) Keep them as separate values (recommended; they are real, distinct styles and the notes can add them). (b) Fold them into "none" |
| 2 | All 3 Java runs launch through a wrapper script, which the classifier follows to the inner launch (jar, javac + java) | (a) Keep: launch = what the wrapper does, wrapper recorded as evidence (recommended). (b) Add "wrapper script" as its own launch value |
| 3 | `java` on PATH is JDK 28 early access | Keep (it is the machine's setup and is recorded), or switch PATH to a GA JDK (25 or 27) before the main runs |
| 4 | Hypothesis 4 expectations that are medians or "every" claims are not proportions. Current rules: startup and disk evaluated as "every" (exceptions); source size and optimization evaluated as the share of Java programs on the expected side, against 50% | Confirm, or say otherwise. Disk is the judgment call: the brief lists only Hypothesis 2 and startup as "every" expectations, but the notes' wording ("Java programs that use dependencies occupy more...") reads as universal |
| 5 | Go baseline disk excludes the Go compiler cache (`GOCACHE`, about 40 MB, mostly the compiled standard library); it is reported separately | Confirm |

No isolation leaks found: the agents used only Read, Write, Edit, Glob, Grep and Bash, every Bash command stayed inside the work directory or a `mktemp` directory, and nothing was installed.
