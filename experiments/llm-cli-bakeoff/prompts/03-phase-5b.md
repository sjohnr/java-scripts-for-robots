# Phase 5b: Sonnet instruction-following pilot (Java)

Continue in the existing repo llm-cli-bakeoff/. Do not use emoji in any markdown file. Prefer tables over long prose.

## Read first (reuse everything, rebuild nothing)

Read, in order: results/agent/summary.md, prompts/02-phase-5-replacement.md, bench/agent/run.sh (and whatever it calls), steering/AGENTS-java-scripts.md, the logsum and jsonpick specs, and the conformance runner. Reuse the existing harness, isolation flags, auth handling, timeouts, and JSON capture. Extend the harness minimally if a condition or check is missing; do not rewrite it. Keep all Haiku results untouched: write new output to results/agent-sonnet/ (raw JSON per run under results/agent-sonnet/raw/).

## Goal

Learn whether a stronger model follows the "write it in Java" instructions that Haiku did not follow reliably, and which specific instructions it follows or breaks. This is a pilot, not a benchmark. Label all results "pilot, low N" everywhere.

## Budget and guardrails

| Parameter | Value |
|---|---|
| Model | Sonnet via `--model sonnet` (or the current alias; record the exact model ID from the JSON output). Same model for every run. |
| Hard cap | 16 headless runs total, including pilot, retries, and failed runs. |
| Limits per run | Same --max-turns and 10-minute timeout as the Haiku pilot. |
| Execution | Sequential, interleaved by condition. Fresh temp dir per trial. Same isolation flags as before. |
| Auth | Re-check auth. Run with ANTHROPIC_API_KEY unset (env -u ANTHROPIC_API_KEY claude ...). If you cannot confirm subscription auth, STOP and ask. |

## Conditions

| ID | Tool | Condition | Trials |
|---|---|---|---|
| B-a | logsum | Spec only, no language stated (record language chosen) | 2 |
| B-b | logsum | Spec plus "write it in Java" | 3 |
| B-c | logsum | B-b plus the steering snippet exactly as used in the Haiku pilot (record its SHA-256; do not edit it) | 3 |
| B-e | logsum | B-b plus a conservative snippet: JDK 25, single file Tool.java, run with `java Tool.java`, classic `public static void main(String[] args)`, stdlib only, no build files, no preview flags, no compact source or module imports | 3 |
| B-c | jsonpick | B-c snippet; spec says stdlib only (no JSON library) | 2 |

Give each agent the spec and conformance suite for that tool only; success is judged by the harness running the conformance suite, not by the agent's claim.

## Snippet audit (before any run)

Read the steering snippet used in the Haiku pilot and check it against JDK 25 behavior. List any statement that is wrong, ambiguous, or self-contradictory (for example, instructions that conflict with each other). Report these in the summary but do NOT change the snippet for B-c; it must stay identical for comparability. B-e is the alternative.

## Instruction-compliance checker (new, scripted)

Write bench/agent/check_compliance.py (or similar) that inspects each trial's final directory and the transcript, with no reliance on the agent's own report. Score every rule as pass/fail per run:

| Rule | Check |
|---|---|
| R1 | Implementation is Java |
| R2 | Single Tool.java at the path the spec requires |
| R3 | No build files (pom.xml, build.gradle*, settings.gradle*, etc.) |
| R4 | Stdlib only: no imports outside java.*, javax.*, jdk.*; no //DEPS; no third-party jars on any classpath |
| R5 | No --enable-preview or preview flags in files or in the commands the agent ran |
| R6 | Runs with plain `java Tool.java` |
| R7 | Snippet-specific style: B-c requires compact source with instance main and IO.println as the snippet says; B-e requires classic main and forbids compact source |
| R8 | Conformance suite passes (harness-verified) |
| R9 | Agent ran the conformance suite itself before finishing (from the transcript) |
| R10 | No unrequested extra files (README, tests dirs, scripts) |

Also record per run: turns, tool calls, tokens, cost as reported, wall time, and turns to first passing conformance run. Catalog every Java mistake (compile errors, wrong-version APIs, old-style boilerplate, missing throws, etc.) in a table with counts.

## Reporting rules

- Per-run table with every rule result; do not hide outliers. Per-condition summary: counts of rules passed and median/min-max for turns and tokens. No standard deviations, significance claims, or percentages at this N.
- Side-by-side comparison with the Haiku pilot by condition, labeled as different models at low N.
- Threats to validity table: tiny N, one model, fixture tasks simpler than real repos, snippet possibly flawed (from the audit), caching effects, plan-limit interference.
- Report total tokens consumed and any rate-limit or usage-limit messages.

## Pilot gate

Run one trial first (B-c, logsum). Report tokens, cost, turns, and compliance results, then project the total for all 16 runs. If the projection is under about 2.5 million tokens and no single run used over 400k, continue without asking. Otherwise STOP and show me the numbers and a proposed cut.

## Stop conditions

Stop and report if the run cap is reached, two consecutive runs hit a rate or usage limit, or the auth mode changes to API-key billing. Partial results are fine.

## Opus (optional; do not start without my confirmation)

After the Sonnet runs and write-up are done, propose an Opus pass: 1 trial each of B-b and B-c on logsum, and 1 of B-c on jsonpick (3 runs), with projected tokens based on the Sonnet numbers. STOP and wait for my go-ahead. If approved, use the same checker and write to results/agent-opus/.

## Deliverables

results/agent-sonnet/summary.md, raw JSON per run, an "Phase 5b" section added to REPORT.md, and a short "model matters" table added to TALK_NOTES.md. Update steering/AGENTS-java-scripts.md only if one condition clearly beats the others, and keep it marked provisional. When finished, reply with the top findings, which rules were broken most, and total tokens consumed.