# jarsrc

Purpose: query a Gradle-cache-like tree of `*-sources.jar` files.

## General rules

- All text output is UTF-8, LF line endings (no CRLF, on any platform), no trailing whitespace on any line.
- Nothing may be printed to stdout or stderr beyond what this spec defines as output. In particular: no banners, notices or warnings on the success path.
- Exit code `0` = success. Exit code `1` = a well-formed "not found" / "no results" condition described below. Exit code `2` = usage error (bad flags, missing required argument, unparsable input). No other exit codes are used.
- `--help` prints a usage message to stdout and exits 0. Exact wording is free, but it must list every flag the CLI supports.
- Unknown flags are a usage error (exit 2) with message `unknown flag: <flag>` on stderr.
- stdout, stderr and the exit code must match this spec exactly (except the wording of `--help`).

## Fixture tree layout

Root is a directory (path given via `--root DIR`, default `.`) shaped like a Gradle module cache:

```
<root>/<group>/<artifact>/<version>/<artifact>-<version>-sources.jar
```

`group` may itself contain path separators representing dots-as-directories (e.g. `com/example/lib`) OR a single segment with dots — fixtures use the dots-as-directories form, matching Gradle's actual cache layout. A `group:artifact:version` string is always rendered with `:` joining the three parts, dots preserved in `group` (i.e. `com/example/lib` -> printed as `com.example.lib`).

Each sources jar contains `.java` files at paths that mirror fully-qualified class names, e.g. `com/example/lib/Widget.java` for FQCN `com.example.lib.Widget`.

## Subcommands

```
jarsrc find <fqcn-or-glob> [--root DIR]
jarsrc cat <fqcn> [--lines A:B] [--root DIR]
jarsrc ls <group:artifact[:version]> [--root DIR]
jarsrc --help
```

### find

Searches every sources jar under root for `.java` files whose FQCN (derived from its path inside the jar, `/` -> `.`, `.java` stripped) matches `<fqcn-or-glob>`. Glob syntax: `*` matches any run of characters except `.`; `**` matches any run of characters including `.`; no other wildcard syntax is supported. A plain FQCN with no `*` must match exactly.

Output: one line per match, sorted lexicographically by FQCN then by group:artifact:version, format:

```
<fqcn> <group>:<artifact>:<version>
```

If a FQCN exists in multiple versions of the same artifact, each version that contains it is listed as a separate line (find does not collapse to highest version — cat does).

If no matches: exit 1, stderr `error: no matches for <fqcn-or-glob>` with prefix-match suggestions (suggestions computed against all known simple class names, i.e. the FQCN's last segment).

### cat

Prints the source of `<fqcn>` from the **highest version** (per the version comparison below) among all artifacts under root that contain that FQCN. If the FQCN appears in more than one distinct group:artifact (not just more than one version of the same artifact), that is an ambiguity error: exit 2, stderr:
```
error: <fqcn> is ambiguous across: <ga1>, <ga2>, ...
```
(group:artifact pairs, no version, sorted lexicographically, all of them listed, no cap).

`--lines A:B` (1-indexed, inclusive) prints only that line range; omitted lines are just not printed (no elision marker). If A or B is out of range, clamp silently to the available range. If A > total lines, print nothing (stdout empty, exit 0 still, this is not an error).

Not found (FQCN not present anywhere under root): exit 1, stderr `error: no source for <fqcn>` with prefix suggestions against all known FQCNs' simple names.

### ls

`<group:artifact>` (version omitted) lists every version directory found for that group:artifact, one per line, sorted by the version comparison below, ascending.

`<group:artifact:version>` lists every `.java` entry's FQCN inside that exact version's sources jar, one per line, sorted lexicographically.

Not found: exit 1, stderr `error: no artifact <group:artifact>` (or `...:version` if version was given and only the version is missing but group:artifact exists) with suggestions computed against known `group:artifact` strings.

## Version comparison

Given two version strings, split each on `.` and `-` (both are segment separators) into segments. Compare segments left to right:
- If both segments parse as non-negative base-10 integers (no leading `+`, optional leading zeros allowed and ignored numerically), compare numerically.
- Otherwise compare the two segments as plain byte strings (ordinal comparison).
- If all compared segments are equal and one version has more segments than the other, the version with more segments is greater.

Examples: `1.2.0` > `1.2` ; `1.10.0` > `1.9.0` ; `1.0.0-beta` > `1.0.0` (`1.0.0-beta` splits to [1,0,0,beta] and `1.0.0` to [1,0,0]; the first three segments are equal and the left side has an extra segment). This is intentionally the documented, if slightly surprising, behavior — implementations must match it exactly, not attempt semver.

"Highest version" = the maximum element of the version set under this ordering. Ties (two identical version strings for the same group:artifact) are not expected; if they occur, either is acceptable.

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

## `--help`

Lists all three subcommands and both flags (`--root`, `--lines`). Exit 0.
