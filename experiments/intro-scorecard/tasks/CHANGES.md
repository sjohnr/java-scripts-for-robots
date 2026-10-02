# Spec changes

Each `tasks/<cli>/SPEC.md` is the bake-off spec (`experiments/llm-cli-bakeoff/spec/0N-<cli>.md`) with the applicable rules from `00-conventions.md` merged into a "General rules" section (jarsrc also gets "Version comparison" and "Error message shape"). Everything not listed here is copied verbatim.

Reasons: **lang** = names a language, runtime or library; **impl** = constrains implementation approach; **bakeoff** = refers to the bake-off, its report or its test suite; **scope** = rule does not apply to this task; **clarity** = editorial fix with no change in required behavior.

## Conventions (00-conventions.md), applied to every task

| # | Original text | New text | Reason |
|---|---|---|---|
| C1 | `# Cross-CLI conventions` / "These rules apply to every CLI in this suite (`noop`, `jarsrc`, `logsum`, `treesum`, `jsonpick`) unless a CLI's own spec overrides them." | `## General rules` section inside each spec, no preamble | bakeoff |
| C2 | "All text output is UTF-8, LF line endings (no CRLF, even on any platform), no trailing whitespace on any line." | "... (no CRLF, on any platform) ..." | clarity |
| C3 | "No implementation may print anything to stdout or stderr beyond what its spec defines as output. In particular: no JVM/Node/Go/Python banners, no "Picked up JAVA_TOOL_OPTIONS" notices, no deprecation warnings on the success path. Any unavoidable noise from a given execution mode is a measured finding (Phase 3), not something to suppress by redirecting streams inside the program." | "Nothing may be printed to stdout or stderr beyond what this spec defines as output. In particular: no banners, notices or warnings on the success path." | lang, bakeoff |
| C4 | "Exit code `0` = success. Exit code `1` = a well-formed "not found" / "no results" condition described in the CLI's own spec. Exit code `2` = usage error ..." | Kept for `jarsrc`; `jsonpick` says "not found" only; for `logsum` and `treesum` (no exit-1 condition in their specs) the exit-1 sentence is dropped | scope |
| C5 | "`--help` on any CLI prints a usage message to stdout and exits 0. Exact wording is not byte-compared across languages (natural per-language help text is fine) but must list every flag the CLI supports." | "`--help` prints a usage message to stdout and exits 0. Exact wording is free, but it must list every flag the CLI supports." | lang, bakeoff |
| C6 | "Argument parsing is hand-rolled (no argparse/commander/picocli/etc.) so that dependency footprint stays comparable across languages. Unknown flags are a usage error (exit 2) with message `unknown flag: <flag>` on stderr." | "Unknown flags are a usage error (exit 2) with message `unknown flag: <flag>` on stderr." | lang, impl, bakeoff |
| C7 | "All implementations are single-threaded by default. Any parallel variant is a separate, clearly labeled extra (e.g. `treesum-parallel`), never the default benchmarked path." | Removed | impl, bakeoff |
| C8 | "## Numeric-segment version comparison (used by `jarsrc`)" | Moved into `jarsrc` only, as "## Version comparison" (see J4) | scope |
| C9 | "## Timestamps" section | Moved into `logsum` only, unchanged except "Where a timestamp is printed (`logsum`)" -> "Where a timestamp is printed" | scope |
| C10 | "## Error message shape" section (not-found errors with `did you mean` suggestions) | Moved into `jarsrc` only, unchanged. The other three specs define their exact error lines and never emit suggestions. | scope |
| C11 | "## Golden-file testing" / "Every case in `tests/cases/` specifies: CLI, args, stdin (optional), and expects stdout, stderr, and exit code to match golden files byte-for-byte across every implementation of that CLI." | "stdout, stderr and the exit code must match this spec exactly (except the wording of `--help`)." (`jsonpick` adds "and the reason text of a JSON parse error") | bakeoff |

## logsum (03-logsum.md)

| # | Original text | New text | Reason |
|---|---|---|---|
| L1 | "(implementations should stream but this is not separately conformance-tested beyond correctness on large fixtures completing in reasonable memory)" | "(implementations should stream but must complete on large inputs in reasonable memory)" | bakeoff |

## treesum (04-treesum.md)

| # | Original text | New text | Reason |
|---|---|---|---|
| T1 | "Purpose: compact repo overview without shelling out to `find \| wc -l` chains." | "Purpose: compact repo overview." | impl (steers away from a shell script) |
| T2 | "this is a known simplification, not a bug, and is stated in the report." | "this is a known simplification, not a bug." | bakeoff |
| T3 | Ignore list (`node_modules`, `.gradle`, `target`, `.venv`, `__pycache__`, ...) | Kept | Required behavior; checked by the fixtures. Names are ecosystem directories, not an instruction to use any language. |

## jsonpick (05-jsonpick.md)

| # | Original text | New text | Reason |
|---|---|---|---|
| P1 | "Purpose: extract a value by path from JSON without a full jq dependency; also the "Java has no JSON in the JDK" data point (hand-rolled parser vs JBang+library variant)." | "Purpose: extract a value by path from JSON." | lang, impl, bakeoff |
| P2 | "that is an explicit unsupported case (not a fixture case)." | "that is an explicit unsupported case." | bakeoff |
| P3 | "(exact reason text is not cross-language compared, but must name line/col or byte offset of the failure)" | "(exact reason text is free, but must name line/col or byte offset of the failure)" | lang, bakeoff |
| P4 | "## Java variants" section: "Two Java implementations exist for this CLI only: (a) hand-rolled minimal recursive-descent JSON parser, stdlib only. (b) JBang script with `//DEPS` bringing in Jackson (or Gson), if JBang is available. Both must pass the same conformance suite. Effort, size, and startup-cost differences between (a) and (b) are a named finding in the report, not hidden." | Removed | lang, impl, bakeoff |

## jarsrc (02-jarsrc.md)

| # | Original text | New text | Reason |
|---|---|---|---|
| J1 | "Purpose: query a Gradle-cache-like tree of `*-sources.jar` files without shelling out to `find`/`unzip`/`grep`." | "Purpose: query a Gradle-cache-like tree of `*-sources.jar` files." | impl (steers away from a shell script) |
| J2 | "from the **highest version** (per spec/00-conventions.md ordering)" | "(per the version comparison below)" | bakeoff (file not present) |
| J3 | "sorted by the version ordering in spec/00-conventions.md, ascending." | "sorted by the version comparison below, ascending." | bakeoff |
| J4 | Version comparison, third bullet and examples: "... the version with more segments is greater (e.g. `1.2` < `1.2.0`... note: `1.2.0` has an extra numeric `0` segment, so `1.2` < `1.2.0`)." followed by "`1.2.0` > `1.2` ; `1.10.0` > `1.9.0` ; `1.0.0-beta` < `1.0.0` (`"beta"` is not a valid non-negative integer so falls back to string compare only if the other side is also non-numeric at that position — but here segment 4 exists on the left only after equal 1/0/0 prefix, so shorter-wins... wait `1.0.0-beta` splits to [1,0,0,beta], `1.0.0` splits to [1,0,0]; first 3 segments equal, left has an extra segment, so `1.0.0-beta` > `1.0.0` by the "more segments" rule)." | "... the version with more segments is greater." then "Examples: `1.2.0` > `1.2` ; `1.10.0` > `1.9.0` ; `1.0.0-beta` > `1.0.0` (`1.0.0-beta` splits to [1,0,0,beta] and `1.0.0` to [1,0,0]; the first three segments are equal and the left side has an extra segment)." | clarity (the original states `1.0.0-beta` < `1.0.0` and then corrects itself; the rule and its outcome are unchanged) |
| J5 | "Ties ... are not expected in fixtures; if they occur, either is acceptable and this is noted as an untested edge case." | "Ties ... are not expected; if they occur, either is acceptable." | bakeoff |
| J6 | References to jars, `.java` files and Gradle's cache layout | Kept | Inherent to the task (Java-flavored control, per the brief) |

## Not changed

- No behavior checked by `tests/cases.json` was altered: output formats, exit codes, error messages, `--help` flag lists and the `unknown flag: <flag>` message are verbatim.
- The `noop` spec is not used.
