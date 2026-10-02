# Phase 5 (REPLACEMENT): Lighter agent experiments

This replaces the original Phase 5 entirely. Phases 0-4 and 6 are unchanged. Do not use emoji in any markdown file. Prefer tables over long prose.

## Goal

Get directional evidence for H3 (do purpose-built CLIs cut agent tokens and tool calls?) and H4 (can an LLM be steered to write working modern Java scripts?) at the smallest cost that still tells a story. This is a pilot, not a benchmark. Label all Phase 5 results "pilot, low N" everywhere they appear.

## Budget and guardrails

| Parameter | Value |
|---|---|
| Model | Haiku via `--model haiku` (or the current Haiku alias; record the exact model ID from the JSON output). Same model for every run. |
| Hard cap | 30 headless runs total, including the pilot runs and any retries. Count failed and aborted runs. |
| Per-run limits | `--max-turns 25` (verify the flag name in `claude --help`), plus a 10-minute wall-clock timeout enforced by the harness. |
| Execution | Strictly sequential. No parallel agent runs. |
| Isolation | Fresh temp directory per trial with only that trial's fixtures. Isolate from my global CLAUDE.md, memory, and settings as far as the CLI allows (check for flags like `--setting-sources`, `--strict-mcp-config`, or a separate config dir via CLAUDE_CONFIG_DIR). Document exactly what you did and what could not be isolated. |
| Permissions | Run with the minimum permissions the tasks need (shell, file read/write in the temp dir). No network tasks. Never point a trial at my real home directory or real Gradle cache. |

## Step 1: Auth check (mandatory)

I use a Pro subscription. I do not want per-token API billing.
1. Check how `claude` is authenticated (`claude auth status` or equivalent; check `claude --help`).
2. If ANTHROPIC_API_KEY is set in the environment, run all trials with it unset (`env -u ANTHROPIC_API_KEY claude ...`) so they use my subscription login. Phase 4 may still use the key for token counting; keep that separate.
3. If you cannot confirm the runs will use the subscription rather than an API key, STOP and ask me.
   Record the auth mode (not any secrets) in results/environment.md.

## Step 2: Pilot (2 runs)

Run one Experiment A trial (condition 2, task 1) and one Experiment B trial (condition b). Report per run: input/output/cache tokens, reported cost, turns, tool calls, wall time, pass/fail. Then project the total for all 30 runs.
- If the projected total is under about 3 million tokens, proceed without asking.
- If it is over that, or any single pilot run used over 400k tokens, STOP and show me the numbers and a proposed cut before continuing.

## Step 3: Experiment A (CLI payoff): 3 tasks x 3 conditions x 2 trials = 18 runs

Tasks (each verifiable programmatically by checking the final answer against a known value from the fixtures):
| ID | Task |
|---|---|
| A1 | "Show the signature and Javadoc of method M in library L using the local Gradle-style cache at <fixture path>." (jarsrc) |
| A2 | "Summarize the errors in this log: top 3 distinct error messages and the time range." (logsum) |
| A3 | "Where is symbol X defined in this repo and which file has the most lines?" (treesum) |

Conditions:
| ID | Condition |
|---|---|
| C1 | No custom tool. Agent uses whatever shell tools it likes. |
| C2 | The Java CLI is on PATH, plus a short instruction snippet in CLAUDE.md inside the temp dir naming the tool and its usage. |
| C3 | The Java CLI is on PATH with no snippet; discoverable only by the agent noticing it and running --help. |

Use the java-launcher or java-jar-tuned mode for the CLI, whichever is fastest and most robust in the measured results; record which.
Order runs interleaved (A1-C1, A1-C2, A1-C3, A2-C1, ...) with trial 2 as a second pass, so any drift in load or rate limits does not bias one condition.

## Step 4: Experiment B (language steering): 1 CLI x 4 conditions x 3 trials = 12 runs

Use logsum (smallest spec). Give the agent the spec file and the conformance suite for logsum only. Success means the conformance suite passes, checked by the harness, not by the agent's claim.
| ID | Condition |
|---|---|
| B-a | Spec only, no language stated. Record the language chosen. |
| B-b | Spec plus "write it in Java". |
| B-c | B-b plus the steering snippet (Java 25, single-file `java Tool.java`, compact source with instance main, no Maven or Gradle scaffold, stdlib only, no preview flags unless the JDK requires them, plus any rules you discovered in earlier phases). |
| B-d | Start as B-a; after the first attempt, send one follow-up turn "rewrite it in Java" (use --resume or --continue; verify the flags). |

Record per run: language chosen, turns to first passing conformance run (or "never" within the turn cap), tokens, cost, wall time, source lines and tokens.
Catalog every recurring LLM-Java mistake in a table (mistake, example, count out of the Java runs). With only 9 Java runs, report counts, not percentages.

## Step 5: Reporting rules

- Publish every run's raw numbers in results/agent/raw/ (JSON per run) and a per-run table in the report. Do not hide outliers.
- Summarize per condition as median and min-max range only. No standard deviations, no significance claims, no percentages with N this small.
- Include a Threats to Validity table covering at least: tiny N, single cheap model (results may not transfer to stronger models, and Haiku may under-perform at idiomatic modern Java), fixture tasks being simpler than real repos, prompt-caching effects, plan-limit or rate-limit interference, non-isolated config leaks.
- Report Subscription-limit impact: total tokens consumed across all runs, and anything I should know about limits (rate limit messages, throttling).
- Deliver steering/AGENTS-java-scripts.md marked "provisional, pilot evidence", stating which condition it came from.
- Add a "How to extend" section: the harness must take `--trials N`, `--model M`, and `--tasks` arguments so I can rerun a bigger or stronger-model version later without code changes. Put it at bench/agent/run.sh (or equivalent) with a dry-run mode that prints the planned runs and counts without executing anything.

## Step 6: Stop conditions

Stop and report immediately if: the run cap is reached; two consecutive runs hit a rate-limit or usage-limit error; or auth mode changes to API-key billing. Partial results are fine; write them up.