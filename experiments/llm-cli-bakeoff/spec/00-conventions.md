# Cross-CLI conventions

These rules apply to every CLI in this suite (`noop`, `jarsrc`, `logsum`, `treesum`, `jsonpick`) unless a CLI's own spec overrides them.

## General

- All text output is UTF-8, LF line endings (no CRLF, even on any platform), no trailing whitespace on any line.
- No implementation may print anything to stdout or stderr beyond what its spec defines as output. In particular: no JVM/Node/Go/Python banners, no "Picked up JAVA_TOOL_OPTIONS" notices, no deprecation warnings on the success path. Any unavoidable noise from a given execution mode is a measured finding (Phase 3), not something to suppress by redirecting streams inside the program.
- Exit code `0` = success. Exit code `1` = a well-formed "not found" / "no results" condition described in the CLI's own spec. Exit code `2` = usage error (bad flags, missing required argument, unparsable input). No other exit codes are used.
- `--help` on any CLI prints a usage message to stdout and exits 0. Exact wording is not byte-compared across languages (natural per-language help text is fine) but must list every flag the CLI supports.
- Argument parsing is hand-rolled (no argparse/commander/picocli/etc.) so that dependency footprint stays comparable across languages. Unknown flags are a usage error (exit 2) with message `unknown flag: <flag>` on stderr.
- All implementations are single-threaded by default. Any parallel variant is a separate, clearly labeled extra (e.g. `treesum-parallel`), never the default benchmarked path.

## Numeric-segment version comparison (used by `jarsrc`)

Given two version strings, split each on `.` and `-` (both are segment separators) into segments. Compare segments left to right:
- If both segments parse as non-negative base-10 integers (no leading `+`, optional leading zeros allowed and ignored numerically), compare numerically.
- Otherwise compare the two segments as plain byte strings (ordinal comparison).
- If all compared segments are equal and one version has more segments than the other, the version with more segments is greater (e.g. `1.2` < `1.2.0`... note: `1.2.0` has an extra numeric `0` segment, so `1.2` < `1.2.0`).
- `1.2.0` > `1.2` ; `1.10.0` > `1.9.0` ; `1.0.0-beta` < `1.0.0` (`"beta"` is not a valid non-negative integer so falls back to string compare only if the other side is also non-numeric at that position — but here segment 4 exists on the left only after equal 1/0/0 prefix, so shorter-wins... wait `1.0.0-beta` splits to [1,0,0,beta], `1.0.0` splits to [1,0,0]; first 3 segments equal, left has an extra segment, so `1.0.0-beta` > `1.0.0` by the "more segments" rule). This is intentionally the documented, if slightly surprising, behavior — implementations must match it exactly, not attempt semver.

"Highest version" = the maximum element of the version set under this ordering. Ties (two identical version strings for the same group:artifact) are not expected in fixtures; if they occur, either is acceptable and this is noted as an untested edge case.

## Timestamps

Where a timestamp is printed (`logsum`), format is `YYYY-MM-DDTHH:MM:SS` (no fractional seconds, no timezone suffix — inputs are assumed to already be in one consistent zone and are passed through verbatim after parsing, not converted).

## Error message shape

Every "not found" style error is exactly one line to stderr, of the form:

```
error: <what was not found>
```

optionally followed on the same line by suggestions in the form:

```
error: <what was not found> (did you mean: <s1>, <s2>, <s3>?)
```

Suggestions are prefix matches against known names, case-sensitive, sorted lexicographically, capped at 3. If there are zero prefix matches, the `(did you mean...)` clause is omitted entirely (no empty parens).

## Golden-file testing

Every case in `tests/cases/` specifies: CLI, args, stdin (optional), and expects stdout, stderr, and exit code to match golden files byte-for-byte across every implementation of that CLI.
