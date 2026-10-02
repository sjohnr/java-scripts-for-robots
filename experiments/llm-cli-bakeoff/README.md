# llm-cli-bakeoff

Reproducible bake-off: identical small CLIs (`noop`, `jarsrc`, `logsum`, `treesum`, `jsonpick`) implemented in Java (several execution modes), Go, Python, and Node, to test whether modern Java (source-file launch, compact source files, AOT caching) is a practical choice for small CLI tools an AI agent calls itself. Full writeup: `REPORT.md`. Slide-ready numbers: `TALK_NOTES.md`.

## Quick start

```bash
# 1. Environment (see results/environment.md for exact versions used)
export JAVA_HOME="$HOME/.sdkman/candidates/java/25.0.4-tem"   # JDK 25, primary
export PATH="$JAVA_HOME/bin:$PATH"

# 2. Generate the small fixture set (deterministic, seeded -- large fixtures
#    are not committed, only the generator and checksums are)
python3 scripts/gen_fixtures.py small fixtures/small

# 3. Build the Go binaries and Java packaged artifacts
for cli in noop jarsrc logsum treesum jsonpick; do
  (cd tools/$cli/go && go build -o $cli .)
  bash bench/build-java-modes.sh $cli Tool tools/$cli/java/classic
done

# 4. Conformance -- must pass before any benchmark is meaningful
python3 tests/run_conformance.py

# 5. Compare implementations side by side for any CLI
./compare.sh jarsrc find "**.Main" --root fixtures/small/jarsrc

# 6. Quick benchmark (~2 minutes)
bash bench/quick.sh

# 7. Full benchmark sweep (reproduces every number in REPORT.md)
bash bench/full.sh
```

## Layout

| Path | Contents |
|---|---|
| `REPORT.md` | Full writeup: hypothesis verdicts, methodology, results, Java feature deep-dive, gotchas, reproduction steps |
| `TALK_NOTES.md` | Headline numbers, surprising findings, slide tables, live-demo scripts |
| `spec/` | Precise behavior specs for each CLI (arguments, exact output format, exit codes, error text) |
| `tools/<cli>/{java,go,python,node}/` | Implementations, including every Java execution mode (`java/classic`, `java/compact`, `java/multi`, `java/jbang`, `java/build/` for packaged artifacts) |
| `tools/java-launcher/jlaunch` | Custom launcher shim: compile-once, AOT-cache, near-binary-startup wrapper for Java source files |
| `tests/` | Conformance runner (`run_conformance.py`), case list (`cases.json`), golden files (`golden/`) |
| `bench/` | `quick.sh` (~2 min), `full.sh` (full sweep), `build-java-modes.sh` (packages one CLI's Java artifacts), raw hyperfine JSON in `results/raw/` |
| `results/` | `environment.md`, `token_cost.{md,json}`, `stderr_hygiene.md` |
| `compare.sh` | `./compare.sh <cli> <args...>` -- every implementation side by side: output equality, timing, token counts |
| `scripts/gen_fixtures.py` | Deterministic seeded fixture generator (`small` or `large`) |
| `scripts/token_cost.py` | Phase 4 static token-cost analysis |

## Status

All phases have run at least once. Phases 0-4 and 6 are done in full, with explicit caveats in REPORT.md (large-scenario fixtures and CPU-constrained runs were not benchmarked; not every Java mode was benchmarked for every CLI). Phase 5 ran as a lighter, user-approved pilot (30 headless Haiku runs, not the original 60-run spec) -- see `results/agent/summary.md` and the Phase 5 section of REPORT.md; everything there is explicitly labeled "pilot, low N," not a statistically powered result.
