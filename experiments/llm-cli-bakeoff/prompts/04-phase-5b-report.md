# Phase 5b: write up the Sonnet pilot (analysis only, no new agent runs)

Continue in llm-cli-bakeoff/. Do NOT invoke `claude` or start any new agent trials. This task is reading existing results, scoring them, and writing reports. Do not use emoji in any markdown file. Prefer tables over long prose. Label every Phase 5b result "pilot, low N".

## Step 1: Inventory what exists

Find all Sonnet-run output (start with results/agent-sonnet/, then Glob for any raw JSON, transcripts, or trial directories from this experiment elsewhere in the repo or temp dirs). Produce a table of every run found: run ID, tool (logsum or jsonpick), condition (B-a, B-b, B-c, B-e), trial number, model ID as recorded, and whether transcript, final files, and usage JSON are all present. State N per condition explicitly. If any run's data is missing or incomplete, say so; do not reconstruct or estimate it. The very first pilot run (B-c, logsum) counts as B-c logsum trial 1.

## Step 2: Score compliance

Locate bench/agent/check_compliance.py (or whatever it was named). If it exists, run it on every run found. If it does not exist or is incomplete, write it now per this spec, then run it. It inspects each trial's final directory and transcript, never the agent's own claims, and scores pass/fail per run:

| Rule | Check |
|---|---|
| R1 | Implementation is Java |
| R2 | Single Tool.java at the path the spec requires |
| R3 | No build files (pom.xml, build.gradle*, settings.gradle*, etc.) |
| R4 | Stdlib only: no imports outside java.*, javax.*, jdk.*; no //DEPS; no third-party jars |
| R5 | No --enable-preview or preview flags in files or in commands the agent ran |
| R6 | Runs with plain `java Tool.java` |
| R7 | Snippet-specific style: B-c requires compact source with instance main and IO.println as the snippet says; B-e requires classic main and forbids compact source; B-a and B-b not applicable |
| R8 | Conformance suite passes (re-run it now against each final Tool.java; do not trust earlier results) |
| R9 | Agent ran the conformance suite itself before finishing (from the transcript) |
| R10 | No unrequested extra files (README, tests dirs, scripts) |

For B-a runs, R1 records the language chosen; score the remaining rules only if the language is Java.

## Step 3: Extract per-run metrics

From the usage JSON and transcripts, for every run: input, output, and cache tokens; reported cost; turns; tool calls; wall time; whether the turn cap or timeout was hit; turns to first passing conformance run (or "never"); source lines and tokens of Tool.java. Then catalog every Java mistake seen (compile errors, wrong-version APIs, old-style boilerplate, missing throws, compact-source pitfalls, preview flags, extra files) as a table: mistake, example, run IDs, count.

## Step 4: Snippet audit

Read the steering snippet used for B-c (record its SHA-256) and check it against JDK 25 behavior. List any statement that is wrong, ambiguous, or contradicts another statement, with a one-line explanation each. Do not modify the snippet. Then, using the transcripts, state whether any rule the model broke can be traced to a flaw in the snippet.

## Step 5: Write the deliverables

| File | Contents |
|---|---|
| results/agent-sonnet/summary.md | Inventory table; per-run table with every R1-R10 result and all metrics; per-condition summary (rules passed, median and min-max for turns and tokens, N stated; no standard deviations, significance claims, or percentages at this N); mistake catalog; snippet audit; side-by-side with the Haiku pilot (from results/agent/summary.md) by condition, labeled as different models at low N; threats to validity; total tokens consumed; any rate-limit or usage-limit messages; open questions for a follow-up |
| REPORT.md | Add a "Phase 5b: Sonnet instruction-following pilot" section (key table plus 5-8 lines of findings, linking to summary.md). Do not edit existing Phase 5 text, except append one sentence to the H4 verdict pointing to Phase 5b. |
| TALK_NOTES.md | Add a "Model matters" table (Haiku vs Sonnet by condition: language chosen, conformance pass, rules broken, turns, tokens) and up to 3 headline findings, each flagged pilot, low N |

Threats to validity must include at least: tiny N, one stronger model versus one cheaper model, fixture tasks simpler than real repos, a possibly flawed snippet (per the audit), prompt caching effects, uneven trial counts across conditions, and stopping the runs in stages.

## Rules

- Every number must trace to a file under results/agent-sonnet/; cite the path in the tables.
- Separate measured from inferred. Report negative and surprising results plainly.
- Do not change steering/AGENTS-java-scripts.md unless one condition clearly beat the others, and then only mark it provisional.
- Commit the results and reports at the end.

When finished, reply with: the run inventory (N per condition), which rules were broken most often, whether Sonnet followed "write it in Java" without and with the snippet, how B-e compared to B-c, total tokens consumed, and the paths to the deliverables.