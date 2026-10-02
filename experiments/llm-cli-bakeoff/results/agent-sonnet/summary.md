# Phase 5b (pilot, low N): Sonnet instruction-following pilot

**Label: pilot, low N.** All numbers below are counts, medians, and min-max ranges across 2-3 trials per condition, not a statistically powered result. No standard deviations, no significance claims, no percentages are reported anywhere in this file.

Model: **`claude-sonnet-5`** (`--model sonnet`, confirmed from every run's `modelUsage` key), same model for every run. Auth: subscription login (`claude auth status` -> `authMethod: claude.ai`, `subscriptionType: pro`), `ANTHROPIC_API_KEY` was not set in the shell and was unset a second time in the harness subprocess for every trial (`bench/agent/run_phase5b.py`). Cost figures are the raw JSON's `total_cost_usd`, a list-price-equivalent notional figure -- not a claim about what was actually billed (billing here is Pro subscription quota, not per-token).

Tool used: **logsum only**. The jsonpick B-c condition (2 planned trials) was **dropped** at the pilot gate (see Step 3 in the run and the Threats to Validity table below) and never run -- no jsonpick data exists anywhere in this repo for Phase 5b.

## Step 1: Run inventory

All 11 runs live under `results/agent-sonnet/raw/`. Each file has the full stream-json transcript (`_events`), final trial-directory path, and usage/cost summary. The trial directories themselves (under `/private/var/folders/.../T/agent5b-logsum-*`) still exist on disk and were re-inspected fresh for this report, not read from the raw JSON's cached fields.

| Run ID | File | Tool | Condition | Trial | Model recorded | Transcript present | Final files present | Usage JSON present |
|---|---|---|---|---:|---|---|---|---|
| B-logsum-B-a-t1 | `results/agent-sonnet/raw/B-logsum-B-a-t1.json` | logsum | B-a | 1 | claude-sonnet-5 | yes | yes | yes |
| B-logsum-B-a-t2 | `results/agent-sonnet/raw/B-logsum-B-a-t2.json` | logsum | B-a | 2 | claude-sonnet-5 | yes | yes | yes |
| B-logsum-B-b-t1 | `results/agent-sonnet/raw/B-logsum-B-b-t1.json` | logsum | B-b | 1 | claude-sonnet-5 | yes | yes | yes |
| B-logsum-B-b-t2 | `results/agent-sonnet/raw/B-logsum-B-b-t2.json` | logsum | B-b | 2 | claude-sonnet-5 | yes | yes | yes |
| B-logsum-B-b-t3 | `results/agent-sonnet/raw/B-logsum-B-b-t3.json` | logsum | B-b | 3 | claude-sonnet-5 | yes | yes | yes |
| B-logsum-B-c-t1 | `results/agent-sonnet/raw/B-logsum-B-c-t1.json` | logsum | B-c | 1 | claude-sonnet-5 | yes | yes | yes |
| B-logsum-B-c-t2 | `results/agent-sonnet/raw/B-logsum-B-c-t2.json` | logsum | B-c | 2 | claude-sonnet-5 | yes | yes | yes |
| B-logsum-B-c-t3 | `results/agent-sonnet/raw/B-logsum-B-c-t3.json` | logsum | B-c | 3 | claude-sonnet-5 | yes | yes | yes |
| B-logsum-B-e-t1 | `results/agent-sonnet/raw/B-logsum-B-e-t1.json` | logsum | B-e | 1 | claude-sonnet-5 | yes | yes | yes |
| B-logsum-B-e-t2 | `results/agent-sonnet/raw/B-logsum-B-e-t2.json` | logsum | B-e | 2 | claude-sonnet-5 | yes | yes | yes |
| B-logsum-B-e-t3 | `results/agent-sonnet/raw/B-logsum-B-e-t3.json` | logsum | B-e | 3 | claude-sonnet-5 | yes | yes | yes |

**N per condition: B-a=2, B-b=3, B-c=3, B-e=3, jsonpick B-c=0 (not run).** The very first pilot run (the one used for the budget gate) is B-logsum-B-c-t1 above. No run's data is missing or incomplete.

## Step 2 + 3: Compliance (R1-R10) and per-run metrics

`bench/agent/check_compliance.py` inspects each trial's final directory and its captured stream-json transcript -- never the agent's own claims. **R8 (conformance) was re-run fresh against the final source file for this report** (`harness.check_conformance`, called live from `check_compliance.py`, not read from the raw JSON's cached `success` field); the recorded and fresh results agreed on all 11 runs (`differs_from_recorded: false` in every run's `R8` note). R1/R2/R4/R7 also re-detect the source file fresh from the trial directory rather than trusting the recorded path.

For B-a, R1 simply records the language chosen (no language was mandated), and R4/R7 are scored `n/a` since they are Java-specific.

| Run | R1 | R2 | R3 | R4 | R5 | R6 | R7 | R8 | R9 | R10 | Rules passed |
|---|---|---|---|---|---|---|---|---|---|---|---|
| B-a t1 | fail (Python) | fail (no .java) | pass | n/a | pass | pass | n/a | pass | pass | pass | 6/8 |
| B-a t2 | fail (Python) | fail (no .java) | pass | n/a | pass | pass | n/a | pass | pass | pass | 6/8 |
| B-b t1 | pass | pass (`logsum.java`) | pass | pass | pass | pass | n/a | pass | pass | pass | 9/9 |
| B-b t2 | pass | pass (`Logsum.java`, self-corrected -- see mistake catalog) | pass | pass | pass | pass | n/a | pass | pass | pass | 9/9 |
| B-b t3 | pass | pass (`logsum.java`) | pass | pass | pass | pass | n/a | pass | pass | pass | 9/9 |
| B-c t1 | pass | **fail** (`logsum.java`, not `Tool.java`) | pass | pass | pass | pass | pass | pass | pass | pass | 9/10 |
| B-c t2 | pass | **fail** (`logsum.java`) | pass | pass | pass | pass | pass | pass | pass | pass | 9/10 |
| B-c t3 | pass | **fail** (`logsum.java`) | pass | pass | pass | pass | pass | pass | pass | **fail** (`test.log` left behind) | 8/10 |
| B-e t1 | pass | pass (`Tool.java`) | pass | pass | pass | pass | pass | pass | pass | pass | **10/10** |
| B-e t2 | pass | pass (`Tool.java`) | pass | pass | pass | pass | pass | pass | pass | pass | **10/10** |
| B-e t3 | pass | pass (`Tool.java`) | pass | pass | pass | pass | pass | pass | pass | pass | **10/10** |

Full machine-readable detail (every rule's boolean plus its supporting note) for all 11 runs is reproducible by running `python3 bench/agent/check_compliance.py --all` from `llm-cli-bakeoff/`.

### Per-run metrics

| Run | Turns | Tool calls | Input tok | Output tok | Cache-read tok | Cache-create tok | Total tok | Cost | Wall (s) | Budget/timeout hit | Turns to first clean self-test (proxy, see caveat) | Source LOC | Source tok (approx) |
|---|---:|---:|---:|---:|---:|---:|---:|---:|---:|---|---:|---:|---:|
| B-a t1 | 9 | 8 | 16 | 9,001 | 263,448 | 22,745 | 295,210 | $0.2337 | 84.6 | no | 9 | 162 | 1,281 |
| B-a t2 | 7 | 6 | 12 | 9,039 | 187,538 | 22,249 | 218,838 | $0.2169 | 81.4 | no | 9 | 181 | 1,309 |
| B-b t1 | 9 | 8 | 16 | 17,611 | 299,678 | 30,963 | 348,268 | $0.3599 | 152.1 | no | never (proxy miss -- ran via compiled classname `java logsum`, see caveat below) | 234 | 2,270 |
| B-b t2 | 11 | 10 | 20 | 11,663 | 350,921 | 25,492 | 388,096 | $0.2888 | 110.5 | no | 13 | 227 | 1,970 |
| B-b t3 | 7 | 6 | 12 | 8,362 | 188,864 | 21,762 | 219,000 | $0.2085 | 69.3 | no | never (proxy miss, same as t1) | 300 | 2,433 |
| B-c t1 | 17 | 16 | 32 | 16,159 | 636,906 | 33,967 | 687,064 | $0.4249 | 161.4 | no | 10 | 196 | 1,755 |
| B-c t2 | 12 | 11 | 22 | 13,425 | 428,175 | 27,717 | 469,339 | $0.3308 | 142.3 | no | 13 | 173 | 1,579 |
| B-c t3 | 15 | 14 | 26 | 20,680 | 566,918 | 35,785 | 623,409 | $0.4634 | 204.8 | no | 10 | 202 | 1,819 |
| B-e t1 | 11 | 10 | 20 | 11,087 | 365,617 | 25,255 | 401,979 | $0.2851 | 104.4 | no | 7 | 272 | 2,197 |
| B-e t2 | 8 | 7 | 14 | 10,643 | 241,083 | 23,973 | 275,713 | $0.2506 | 105.8 | no | 7 | 241 | 1,922 |
| B-e t3 | 7 | 6 | 12 | 7,652 | 191,368 | 20,795 | 219,827 | $0.1980 | 65.4 | no | 7 | 258 | 2,177 |

No run hit the `--max-budget-usd 1.00` cap or the 600s wall-clock timeout. **No `--max-turns` flag exists in this CLI version** (re-checked directly via `claude --help`, same finding as the Haiku pilot) -- `--max-budget-usd` + timeout is the substituted cap, per the original Phase 5 report's own note.

**Caveat on "turns to first passing conformance run":** no per-turn snapshots of the agent's source file were kept during the run (only the final trial-directory state and the transcript survive), so it is not possible to retroactively determine at which turn an *earlier* version of the file would have passed the full 8-case golden suite. The column above is a proxy: the 1-based turn index of the first Bash self-invocation of the final program that did not return a tool error. This is a lower bound on "genuinely working," not a verified conformance pass -- flagged explicitly in `check_compliance.py`'s own notes for every run (`turns_to_first_clean_self_test_caveat`). For B-b t1/t3 the proxy shows "never" because those two runs tested by compiling with `javac` and running `java logsum` (the compiled class name, not `java logsum.java` or a `./` direct exec) -- a self-test pattern the checker's exec-detection regex does recognize (fixed during this report), but by coincidence the improved regex still shows a hit count of 0 for t1/t3 specifically because the compile-and-run pair happened in a single multi-line Bash block that the transcript records as one command containing both `javac logsum.java` and `java logsum ...` -- the checker matched it correctly in the final compliance pass (R9 = pass for both) but the turn-index proxy undercounts multi-command blocks. Treat the turn-index numbers as directional only.

## Java mistake catalog

Across all 9 Java-requested runs (B-b x3, B-c x3, B-e x3; B-a is out of scope since no language was mandated), **exactly one Java mistake was observed and self-corrected within the same trial:**

| # | Mistake | Run | Count | Example |
|---|---|---|---:|---|
| 1 | Public class name doesn't match filename (`public class Logsum` in a file named `logsum.java`) | B-b t2 | 1/9 | `logsum.java:13: error: class Logsum is public, should be declared in a file named Logsum.java` -- the model then renamed the file to `Logsum.java`, which is why the final compliant file for that run is `Logsum.java`, not `logsum.java`. |

No other compile errors, preview-flag misuse, missing-`throws` issues (the Haiku pilot's one catalogued mistake), or non-stdlib imports were found in any of the 9 Java runs. This is a real, if small, contrast with the Haiku pilot, which also had exactly 1/9 Java runs with a compile error but of a different kind (missing `throws IOException` on a compact-source `main`).

## Snippet audit

**B-c snippet (`STEERING_SNIPPET` in `bench/agent/run_phase5b.py`), SHA-256: `ed5df3098228fff06fa7c498cc7bd1cde4a20372daa3db1e0533bb485caac672`** (893 bytes). This is the literal `STEERING_SNIPPET` constant from `bench/agent/run_experiment_b.py` (the Haiku pilot harness), reused verbatim here for comparability, not the paraphrased fenced block in `steering/AGENTS-java-scripts.md`. Not modified.

Checked directly against a live JDK 25.0.4 (Temurin) install this session:

| Claim in snippet | JDK 25 behavior verified | Verdict |
|---|---|---|
| Compact source file: top-level `void main(String[] args)`, no enclosing class | Compiles and runs (`java T1.java` with `void main(String[] args) { ... }`, no class) | correct |
| `IO.println` from `java.lang.IO` | Ran successfully | correct |
| `import module java.base;` instead of individual imports | Compiled and ran with module import | correct |
| Compact source files and module imports are FINAL, non-preview on JDK 25 | Confirmed -- no `--enable-preview` needed for either | correct |
| `ClassName::method` referencing a same-file helper does not resolve inside a compact source file; use a lambda instead | Reproduced directly: `Tool::show` from inside a compact source file fails with `cannot find symbol variable Tool` | correct, and this is a genuinely non-obvious pitfall worth keeping |
| "Write it in Java 25... run directly with `java Tool.java`" | **Ambiguous.** This sentence gives the filename `Tool.java` only as part of an example command, not as an explicit "name your file X" instruction. It is the only place in the snippet a filename is mentioned. | **ambiguous -- traced to a real observed rule violation (R2), see below** |
| No explicit mention that `void main(String[] args)` may need `throws` for checked exceptions | Confirmed live: `void main(String[] args) throws Exception { ... }` compiles fine, so the fix is trivial, but the snippet never warns about it | omission, not wrong (this was the Haiku pilot's one mistake; it was not repeated here, so this omission did not cause an observed failure in this pilot) |

**Traced violation:** the filename ambiguity above directly explains an observed rule failure. All 3 B-c trials wrote `logsum.java` (task-named) rather than the literal filename `Tool.java` the example command uses, failing R2 in all 3 trials (`results/agent-sonnet/raw/B-logsum-B-c-t{1,2,3}.json`, `notes.R2`). By contrast, B-e's snippet (`CONSERVATIVE_SNIPPET`, SHA-256 `b5d4a869656f7bbda2344da7b5eb9db1f266a55628f69733430df0048f670d59`) states explicitly "a single file named Tool.java," and all 3 B-e trials complied (0 failures). This is the clearest single piece of evidence in this pilot that **explicit filename instructions get followed; filenames only implied by an example command do not.**

No other rule violation in this pilot traces to a snippet flaw -- the `Tool::method` pitfall the snippet warns about was never triggered in any B-c trial (no method-reference errors appear in any transcript), and the one Java mistake found (B-b t2's classname/filename mismatch) occurred under the *unsteered* B-b condition, which has no snippet at all.

## Per-condition summary (pilot, low N -- Sonnet, logsum only)

| Condition | N | Conformance | Rules fully passed | Turns (median, range) | Tokens (median, range) | Cost (median, range) | Language(s) chosen |
|---|---:|---:|---:|---|---|---|---|
| B-a: no language stated | 2 | 2/2 | 0/2 runs at full score (both 6/8, expected -- Java-specific rules n/a) | 8 [7, 9] | 257,024 [218,838, 295,210] | $0.2253 [$0.2169, $0.2337] | Python, Python |
| B-b: "write it in Java" | 3 | 3/3 | 3/3 runs at full score (9/9 each) | 9 [7, 11] | 348,268 [219,000, 388,096] | $0.2888 [$0.2085, $0.3599] | Java, Java, Java |
| B-c: B-b + Haiku's exact snippet | 3 | 3/3 | 0/3 runs at full score (9/10, 9/10, 8/10 -- filename miss every time) | 15 [12, 17] | 623,409 [469,339, 687,064] | $0.4249 [$0.3308, $0.4634] | Java, Java, Java |
| B-e: conservative snippet (classic main) | 3 | 3/3 | **3/3 runs at full score (10/10 each)** | 8 [7, 11] | 275,713 [219,827, 401,979] | $0.2506 [$0.1980, $0.2851] | Java, Java, Java |

**Total across all 11 runs: 4,146,743 tokens, $3.2605 list-price-equivalent.**

## Side-by-side with the Haiku pilot (different models, both low N -- not a controlled comparison)

Haiku numbers from `results/agent/summary.md` (Experiment B, logsum, 3 trials/condition except this pilot has 2 for B-a; Haiku's B-d, "unsteered then rewrite," has no Sonnet equivalent in this pilot and is omitted).

| Condition | Haiku success | Haiku turns (median) | Haiku cost (median) | Sonnet success | Sonnet turns (median) | Sonnet cost (median) |
|---|---:|---:|---|---:|---:|---|
| B-a (no language) | 3/3 (Python 3/3) | 5 | $0.0832 | 2/2 (Python 2/2) | 8 | $0.2253 |
| B-b ("write it in Java") | 3/3 | 4 | $0.0769 | 3/3 | 9 | $0.2888 |
| B-c (steering snippet) | **2/3** | 23 | $0.1895 | **3/3** | 15 | $0.4249 |
| B-e (conservative snippet) | not run (Haiku pilot had no B-e) | -- | -- | 3/3 | 8 | $0.2506 |

**Direction of the effect:** Sonnet costs roughly 2.5-4x Haiku per run in both tokens and dollars (as expected for a larger model), and takes somewhat more turns on B-a/B-b -- but where Haiku's most-detailed condition (B-c) was also its least reliable one (2/3, 23 median turns, its slowest and most expensive Java condition), Sonnet's B-c is fully reliable (3/3) and, while still its most expensive Java condition, takes fewer turns than Haiku's B-c did (15 vs 23). The "more steering means more turns without more reliability" pattern the Haiku pilot found does not replicate on Sonnet for reliability, though B-c is still the most expensive condition for both models. B-e was never tested on Haiku, so there is no cross-model comparison for the conservative-snippet condition.

## Threats to validity

| Threat | Detail |
|---|---|
| Tiny N | 2-3 trials per condition. Every range above is "what happened in these few runs," not a distribution estimate. |
| One stronger model vs. one cheaper model | This pilot used only Sonnet (`claude-sonnet-5`); the Haiku comparison above is two different models at low N each, not a controlled ablation -- differences could reflect model capability, model-specific defaults, or noise, and cannot be attributed to any one cause. |
| Fixture tasks simpler than real repos | logsum's spec and 8-case golden suite are small and fully specified; a real Java task in a real repo has far more ambiguity and surrounding code context than "read two markdown files and write one file." |
| Possibly-flawed B-c snippet | Per the snippet audit above, the B-c snippet's filename instruction is ambiguous (implied via an example command, not stated directly) and this ambiguity has a directly observed effect (R2 failing 3/3). The snippet reused here is verbatim from the Haiku pilot for comparability, not fixed, so this flaw is present in every B-c result in both pilots. |
| Prompt-caching effects | `cache_read_input_tokens` and `cache_creation_input_tokens` dominate every run's token total (e.g. B-c t1: 636,906 + 33,967 out of 687,064 total, over 97%) -- absolute token/cost numbers are dominated by fixed per-call overhead more than by task-specific work, same finding as the Haiku pilot. |
| Uneven trial counts across conditions | B-a has N=2 (matching the original Phase 5 B replacement spec's asymmetric plan), all other conditions have N=3. Medians and ranges for B-a are correspondingly less informative. |
| Stopping the runs in stages | This pilot was executed in two stages across two conversation turns (1 pilot-gate run, then 3 more, then the remaining 7) per an explicit budget-gate stop-and-ask (the projected token/cost total for all 13 planned runs exceeded the pilot gate's own threshold after the first trial). All 11 completed runs are directly comparable to each other (same harness, same model, same trial-dir isolation each time), but the staged execution means the person running this pilot made per-stage judgment calls about whether to continue, which is itself a deviation from a single uninterrupted run. |
| jsonpick never run | The B-c jsonpick condition (2 planned trials) was dropped entirely at the pilot gate and is absent from every table in this file -- not "0 passed," genuinely not attempted. |
| Non-isolated config leaks | Same isolation approach as the Haiku pilot: `--setting-sources project` (excludes user-level CLAUDE.md/memory/settings), `--strict-mcp-config` (excludes inherited MCP servers), fresh `mkdtemp` per trial. Not independently verified beyond the flags' documented behavior. |
| Rate-limit/plan-limit interference | Rate-limit telemetry events appear in every transcript (`status: "allowed"`, five-hour window utilization climbing from 6% to 32% across the 11 runs) -- none reached a throttled or rejected state. A single sequential run at N=11 is not a strong test for this either way. |

## Subscription-limit impact

Total tokens consumed across all 11 Phase 5b Sonnet runs: **4,146,743** (sum of input + output + cache-creation + cache-read tokens from each run's `usage` block). No rate-limit or usage-limit *rejection* messages appeared in any run's stderr or JSON output -- every `rate_limit_event` in every transcript carries `"status": "allowed"`. Five-hour rolling-window utilization rose from 6% (first run) to 32% (last run) over the course of the 11 runs; seven-day window utilization stayed at 9-11% throughout. No stop condition (run cap, two consecutive rate-limit hits, billing-mode change) was triggered.

## Open questions for a follow-up

- Would an explicit-filename fix to the B-c snippet (state "name the file Tool.java" directly, as B-e does, rather than implying it via an example command) close the R2 gap while keeping the rest of B-c's compact-source guidance? This pilot did not test that hybrid condition.
- Does the Sonnet-vs-Haiku B-c reliability gap (3/3 vs 2/3) hold at higher N, or was Haiku's single B-c failure just noise at N=3?
- jsonpick was never run for Sonnet in this pilot -- the "Java has no stdlib JSON parser" finding from the spec (hand-rolled parser vs. JBang+library) has no Sonnet data point at all yet.
- B-e (conservative snippet) was never run on Haiku, so there is no same-model comparison of "does the conservative snippet also help a cheaper model," only the within-Sonnet B-c vs. B-e comparison above.
- The turns-to-first-clean-self-test proxy undercounts compile-then-run self-tests issued as a single multi-line Bash block (see caveat in Step 3) -- a harness that snapshots the source file at each turn would give a real answer instead of this proxy.
