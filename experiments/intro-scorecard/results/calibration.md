# Calibration (JDK 25)

Rerun of the 6 `logsum` calibration runs after switching to JDK 25 GA (decision D4), 2026-10-11 00:36 to 00:46 UTC. The JDK 28 runs are in `superseded/jdk28-ea/` with their own `calibration.md`. These 6 runs count toward the 84.

| Run | Model ID | Turns | Output tokens | Cost (USD, notional) | Wall (s) | Language | Pass | Reported run command | Command used | Classifier | Startup (ms) | AOT (ms) |
|---|---|---|---|---|---|---|---|---|---|---|---|---|
| A-logsum-haiku-1 | claude-haiku-5-5 | 10 | 22,473 | 0.020 | 105.9 | python | 9/9 | `./logsum` | `./logsum` | Python shebang script | 14.6 |  |
| A-logsum-opus-1 | claude-opus-5-5 | 6 | 6,493 | 0.224 | 78.5 | python | 9/9 | `./logsum` | `./logsum` | Python shebang script | 14.7 |  |
| A-logsum-sonnet-1 | claude-sonnet-5-5 | 4 | 3,597 | 0.063 | 25.7 | python | 9/9 | `./logsum` | `./logsum` | Python shebang script | 14.5 |  |
| B-logsum-haiku-1 | claude-haiku-5-5 | 18 | 46,501 | 0.039 | 185.7 | java | 9/9 | `java -jar build/logsum.jar --help` | `java -jar build/logsum.jar` | Make / jar, wrapper: none, classic, multiple files | 23.6 | 20.2 |
| B-logsum-opus-1 | claude-opus-5-5 | 9 | 10,141 | 0.335 | 123.3 | java | 9/9 | `./logsum` | `./logsum` | build script / jar, wrapper: wrapper script, classic, single file | 29.5 | 20.7 |
| B-logsum-sonnet-1 | claude-sonnet-5-5 | 4 | 5,339 | 0.087 | 33.7 | java | 9/9 | `./logsum [FILE] [--since TIMESTAMP] [--level LEVEL] [--top N]` | `./logsum` | none / javac + java, wrapper: wrapper script, classic, single file | 27.5 | 20.2 |

No limits hit; every run reported `apiKeySource: none`. Total generation cost $0.77 notional.

## Measurement fix after this calibration

Two follow-up replies were not bare commands: `java -jar build/logsum.jar --help` (Haiku) and `./logsum [FILE] [--since TIMESTAMP] [--level LEVEL] [--top N]` (Sonnet). Both programs first failed every case for that reason only. `measure.py` now drops usage-style placeholder groups (`[...]`, `<...>`, `...`), a trailing `--help`/`-h`, and for `jarsrc` a trailing subcommand, and records each change under `run_command_notes` in `measure.json`. After the fix all 6 runs pass 9/9. The follow-up prompt itself is unchanged (fixed by the brief).
