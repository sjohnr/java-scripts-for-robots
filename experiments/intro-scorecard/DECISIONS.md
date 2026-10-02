# Decision log

Intro scorecard experiment. Calibration was rerun on JDK 25 (D4); the main runs start after it, with no open decisions.

## Decided

| # | Decision | Outcome |
|---|---|---|
| D1 | Start the remaining 78 runs | Yes, after D4 |
| D2 | Java build values Make and build script | Kept as their own values (now in the notes). Summary also reports a sensitivity check with both folded into "none" |
| D3 | Programs launched through a wrapper script | Launch = what the wrapper runs. "Wrapper" (none, wrapper script) recorded as its own dimension (now in the notes) |
| D4 | JDK on PATH | JDK 25 GA (Temurin 25.0.4) for agent runs and measurements, set by the harness (`JAVA_HOME`, first on `PATH`, other SDKMAN JDKs removed). The 6 calibration runs on JDK 28 early access are kept in `results/superseded/jdk28-ea/` and rerun |
| D5 | Hypothesis 4 disk | Every Java program with dependencies must be larger than the median competing program (zero exceptions) |
| D6 | Go compiler cache (about 40 MB) | Left out of disk totals, reported separately |
| D7 | Superseded JDK 28 calibration runs | Not counted toward the 84-run cap; kept in `results/superseded/jdk28-ea/` and left out of the analysis |
| D8 | Speed up generation | 4 agent runs in parallel, launched in the shuffled order (any prefix stays balanced). Classification and conformance run right after each run; startup and AOT benchmarks wait until generation is complete, then run sequentially on an otherwise idle machine. `results/scorecard.json` is regenerated after every 4 completed runs. Wall time per run is noisier as a result (not a hypothesis metric). Runs 1-12 (attempts) were sequential |
| D9 | Usage-limit stops | Generation pauses after two limit hits in a row (completion order), probes the limited models every 20 min and resumes when they answer (gives up after 24 h of pausing). A run that hit a limit is retried; the limited attempt is kept as `results/raw/<run-id>.limit-<time>` and, like D7, does not count toward the cap. If only the follow-up turn hit the limit, the follow-up is repeated and the generation kept |
| - | Hypothesis 4 source size and optimization ("median" expectations) | Judged as the share of Java programs on the expected side, against 50% |
| - | Follow-up turn cost | Recorded as its own cost (the CLI reports a cumulative figure) |
