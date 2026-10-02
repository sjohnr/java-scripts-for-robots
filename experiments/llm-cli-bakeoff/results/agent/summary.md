# Phase 5 (replacement): agent experiment pilot results

**Label: pilot, low N.** Every number below is median and min-max range across 2-3 trials per cell, not a statistically powered result. No standard deviations, no significance claims, no percentages are reported anywhere in this file, per the pilot's own reporting rules.

Model: **claude-haiku-4-5-20251001** (`--model haiku`, exact ID confirmed from run JSON's `modelUsage` key), every run. Auth: subscription login (`claude auth status` -> `authMethod: claude.ai`, `subscriptionType: pro`), `ANTHROPIC_API_KEY` was not set in the shell and was explicitly unset in the harness for every trial subprocess as a second safeguard. `total_cost_usd` in the raw JSON is a **list-price-equivalent notional figure** (`costBasis: "list"` in the raw output) used for comparison between conditions -- it is not a claim about what was actually billed, since billing here is via Pro subscription quota, not per-token API charges.

**Totals: 30/30 planned runs completed (2 pilot + 28 matrix), $2.45 list-price-equivalent, 4,887,154 tokens (input+output+cache-write+cache-read summed across all runs).** No rate-limit or usage-limit errors were hit at any point (checked directly in every run's raw JSON and stderr). No stop condition (run cap, two consecutive rate-limit hits, billing-mode change) was triggered.

## Step 2 pilot projection vs. actual

Pilot (2 runs) projected ~$1.87 / ~2.0M tokens for all 30. Actual was $2.45 / 4.9M tokens -- the pilot underestimated because it sampled only the cheapest Experiment B condition (B-b); B-c and B-d turned out to run many more turns (up to 23) and cost substantially more per run. Reported here for calibration honesty, not adjusted after the fact.

## Experiment A: does the CLI pay off?

3 tasks (A1=jarsrc "show method signature", A2=logsum "summarize errors", A3=treesum "find symbol + largest file") x 3 conditions x 2 trials = 18 runs, interleaved order, java-jar-tuned mode used for the CLI (fastest/most robust measured mode from Phase 3 that doesn't require an AOT cache the agent's own actions could invalidate).

| Task | Condition | Success | Cost (median, range) | Turns (median, range) | Tokens (median, range) |
|---|---|---:|---|---|---|
| A1 (jarsrc) | C1 no tool | 2/2 | $0.0334 [0.0285, 0.0383] | 5.5 [4, 7] | 120226 [86834, 153618] |
| A1 (jarsrc) | C2 tool+snippet | 2/2 | $0.0213 [0.0210, 0.0216] | 2.0 [2, 2] | 42762 [42682, 42841] |
| A1 (jarsrc) | C3 tool, no snippet | 2/2 | $0.0387 [0.0376, 0.0399] | 6.5 [6, 7] | 143214 [132103, 154326] |
| A2 (logsum) | C1 no tool | 2/2 | $0.0874 [0.0872, 0.0877] | 4.0 [4, 4] | 159630 [159561, 159700] |
| A2 (logsum) | C2 tool+snippet | 2/2 | $0.0214 [0.0210, 0.0219] | 2.0 [2, 2] | 42766 [42674, 42858] |
| A2 (logsum) | C3 tool, no snippet | **0/2** | $0.1166 [0.0790, 0.1542] | 7.0 [3, 11] | 334186 [112126, 556247] |
| A3 (treesum) | C1 no tool | 2/2 | $0.0229 [0.0219, 0.0239] | 3.0 [3, 3] | 43012 [42682, 43341] |
| A3 (treesum) | C2 tool+snippet | **0/2** | $0.0220 [0.0214, 0.0225] | 3.0 [3, 3] | 43000 [42849, 43150] |
| A3 (treesum) | C3 tool, no snippet | 2/2 | $0.0220 [0.0217, 0.0223] | 3.0 [3, 3] | 42714 [42617, 42812] |

### What actually happened in the 4 "failures" (all read individually, not just scored)

- **A2/C3 (logsum discoverable only via --help), both trials "failed" the strict check, but were close.** The model did not discover/use `logsum` in either trial; instead it read/grepped the raw log directly. It got the top-3 error messages and their counts right (the check's 3 message-text substrings matched) but missed the exact last-timestamp string in both trials (one trial got the range only approximately: "00:26:01" vs. the true "00:26:11", i.e. the model summarized rather than computed the true last line). This is the clearest concrete illustration of H3 available in the pilot: manual scanning produces answers that are right in substance and subtly wrong in the precise, aggregatable numbers a purpose-built tool would get exactly right, and it costs far more (median 334186 tokens vs. 42766 for C2 with the tool) to get there. Cost variance was the largest of any cell (112126 to 556247 tokens across the 2 trials) -- when the model doesn't find the tool, how much it flails is itself high-variance.
- **A3/C2 (treesum available with a documented snippet), both trials "failed" -- and this is a genuine tool-non-use finding, not a harness bug.** Both trials correctly found `VersionCompare` (grep-based, straightforward). Both trials answered the "largest file" half of the question WRONG in a specific, verifiable way: both said `jarsrc.py` (**10,573 lines**) was the largest, but `jarsrc.py` is actually 361 lines (10,573 is its **byte size**, confirmed directly in the trial's own working directory with `wc -l`/`wc -c` after the fact) -- the model conflated file size in bytes with line count, despite `treesum` (which reports LOC correctly: `main.go` at 470 lines is the true largest-by-lines file) being on PATH and documented in that trial's own CLAUDE.md. The model did not invoke `treesum` in either trial. This is a genuine negative result for "having the tool documented guarantees it gets used," and a concrete example of the kind of silently-plausible-but-wrong answer a purpose-built LOC counter exists specifically to prevent.

## Experiment B: language steering (logsum only)

4 conditions x 3 trials = 12 runs. Success = the 8 core logsum conformance cases pass against whatever the model wrote, checked by this harness (not the model's own claim).

| Condition | Success | Language(s) chosen | Cost (median, range) | Turns (median) |
|---|---:|---|---|---:|
| B-a: no language stated | 3/3 | Python, Python, Python | $0.0832 [0.0754, 0.1233] | 5 |
| B-b: "write it in Java" | 3/3 | Java, Java, Java | $0.0769 [0.0516, 0.1032] | 4 |
| B-c: B-b + steering snippet | 2/3 | Java, Java, Java | $0.1895 [0.1082, 0.2543] | 23 |
| B-d: B-a, then "rewrite in Java" | 3/3 | (Python then) Java, Java, Java | $0.2275 [0.1465, 0.2437] | 10 |

Unsteered, Haiku chose Python in all 3/3 trials for this spec -- a clean, if low-N, confirmation of "an unsteered model defaults away from Java" for this kind of task. B-b (bare "write it in Java", no other steering) passed 3/3 with the fewest median turns (4) and lowest median cost of the three Java conditions. **B-c, the condition with the most detailed steering snippet, did worse (2/3) and cost far more (23 median turns, the highest of any condition)** -- the one B-c failure was a real, catalogable compile error (below), and the extra steering text appears to have cost more turns without preventing that failure. This is a genuine, non-obvious negative result and is reported as such, not smoothed over: **more steering did not mean fewer mistakes in this pilot.** B-d (start unsteered, then "rewrite in Java" as a follow-up turn) passed 3/3 but at the highest median cost (10 turns to redo the whole thing in a new language after already writing it once in Python).

### Recurring Java mistakes catalog (counts, not percentages -- only 9 Java runs total: B-b x3 + B-c x3 + B-d x3)

| # | Mistake | Count (out of 9 Java runs) | Example |
|---|---|---:|---|
| 1 | Unhandled checked exception in a compact-source `void main(String[] args)` (missing `throws IOException`) | 1/9 | B-c trial 1: `Tool.java:8: error: unreported exception IOException; must be caught or declared to be thrown` -- `run(args)` was called from `main` without declaring `throws IOException` on `main` itself, a compact-source-specific pitfall since the classic `public static void main(String[] args) throws IOException` signature makes this obvious but the shorter compact form does not carry the same visual cue. |
| 2 | (none of the other 8 Java runs hit a compile error or a conformance mismatch) | 0/9 for other categories | -- |

Given only 1 failure across 9 Java runs, this table is intentionally short -- it would be dishonest to extrapolate a broader mistake taxonomy from a single data point. The one mistake found is real and specific, not a guess.

## Threats to validity

| Threat | Detail |
|---|---|
| Tiny N | 2-3 trials per cell. Every range above should be read as "what happened in these few runs," not a distribution estimate. |
| Single, cheap model | All 30 runs used Haiku (claude-haiku-4-5-20251001). Haiku may under-perform at idiomatic modern Java specifically (compact source files are a fairly recent, less-represented-in-training pattern) relative to a stronger model; these results should not be assumed to transfer to Sonnet or Opus. |
| Fixture tasks are simpler than real repos | A1-A3 use the small, seeded fixture set (10 jars, 1k-line log, 200-file tree) and a hand-picked 5-file "repo" for A3 -- much smaller and cleaner than a real agent's actual working directory. |
| Prompt-caching effects | `cache_creation_input_tokens`/`cache_read_input_tokens` dominate the token totals for nearly every run (tens of thousands of tokens of fixed Claude Code system-prompt/tool-definition overhead per call, visible in every raw JSON) -- absolute token/cost numbers are dominated by this fixed cost more than by the task itself, especially for the cheap, few-turn conditions (e.g. A1/A3 C2 and C3 all cluster near ~42-43k tokens regardless of task, which is almost entirely this fixed overhead, not task-specific work). |
| Rate-limit/plan-limit interference | None observed in this run (checked directly), but a single sequential run at this scale is not a strong test for this. |
| Non-isolated config leaks | `--setting-sources project` excludes user-level (global `~/.claude`) settings/CLAUDE.md/memory and `--strict-mcp-config` excludes inherited MCP servers; each trial ran in a fresh `mkdtemp` directory. Not independently verified beyond reading the flags' documented behavior in `claude --help` -- a stronger isolation check (e.g. diffing session transcripts for any sign of leaked global memory content) was not done in this pilot. |
| No `--max-turns` flag exists in this Claude Code CLI version | The original Phase 5 instructions called for `--max-turns 25`; `claude --help` in this environment has no such flag. Substituted with `--max-budget-usd` (0.75 for Experiment A, 1.00 for Experiment B) plus a 600s wall-clock timeout enforced by the harness (`subprocess.run(..., timeout=600)`). No run hit either limit. |

## Subscription-limit impact

Total tokens consumed across all 30 runs: 4,887,154 (sum of input + output + cache-creation + cache-read tokens from each run's `usage` block). No rate-limit or usage-limit messages appeared in any run's stderr or JSON output. No visible degradation (retries, slowdowns) was observed across the sequential run. This was run on a Pro subscription; if reproducing this at a larger N, note that per-run token cost is dominated by fixed system-prompt/tool-definition overhead (see "prompt-caching effects" above), so scaling N scales cost roughly linearly with a large fixed offset per run, not purely with task complexity.

## How to extend

Both harness scripts take `--trials N`, `--model M`, and task/condition selectors, and support `--dry-run` to print the planned run list and count without executing anything:

```
python3 bench/agent/run.py --dry-run --experiment A --trials N --tasks A1,A2,A3 --model <alias-or-full-id>
python3 bench/agent/run_experiment_b.py --dry-run --trials N --conditions B-a,B-b,B-c,B-d --model <alias-or-full-id>
```

Both scripts skip any run whose output JSON already exists (`results/agent/raw/<run-id>.json`), so a larger run can be resumed/extended incrementally without re-spending on already-completed trials -- this is how the pilot's 2 runs were folded into the full 30-run matrix without re-running them.
