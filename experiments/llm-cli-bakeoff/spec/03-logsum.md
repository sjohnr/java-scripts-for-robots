# logsum

Purpose: streaming summary of a log file (or stdin), no full-file buffering required for correctness (implementations should stream but this is not separately conformance-tested beyond correctness on large fixtures completing in reasonable memory).

## Usage

```
logsum [FILE] [--since TIMESTAMP] [--level LEVEL] [--top N]
```

`FILE` omitted or `-` means read stdin. `--top N` defaults to 5.

## Input line format

Each line: `TIMESTAMP LEVEL MESSAGE` where TIMESTAMP is `YYYY-MM-DDTHH:MM:SS`, LEVEL is one of `DEBUG`, `INFO`, `WARN`, `ERROR` (case-sensitive, exactly these tokens), and MESSAGE is the remainder of the line (may contain spaces). Fields are separated by single spaces (TIMESTAMP, then one space, then LEVEL, then one space, then MESSAGE — MESSAGE is everything after that third token starts, verbatim including any internal multiple spaces).

Lines that do not match this format (fewer than 3 whitespace-separated tokens, bad timestamp, or unrecognized level) are silently skipped and do not count toward any statistic. This is intentional — logsum is best-effort against messy real logs, not a strict parser.

## Filters

`--since TIMESTAMP`: only consider lines with TIMESTAMP >= the given value (plain string comparison is sufficient since the format is fixed-width and lexicographically ordered). `--level LEVEL`: only consider lines at exactly that level. Filters apply before all statistics below, including first/last timestamp.

## Message normalization (for top-N grouping only, not for display)

To group similar ERROR-class... (see below) messages, normalize by applying these regex replacements in order to MESSAGE before grouping (display uses the ORIGINAL first-seen message for that group, not the normalized form):

1. UUIDs: `[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}` -> `<uuid>`
2. Hex blobs of 6+ hex digits (word-boundary delimited, must contain at least one letter a-f/A-F to avoid eating plain decimal numbers): `\b(?=[0-9a-fA-F]*[a-fA-F])[0-9a-fA-F]{6,}\b` -> `<hex>`
3. Any remaining run of digits, 1 or more: `[0-9]+` -> `<num>`

Apply in that exact order (UUID pattern first, so a UUID is not partially eaten by the hex/digit rules first).

## Output (to stdout, no output to stderr on success)

```
total: <N>
by level: DEBUG=<a> INFO=<b> WARN=<c> ERROR=<d>
first: <timestamp or "n/a">
last: <timestamp or "n/a">
top errors:
  <count> <message>
  <count> <message>
  ...
```

- `total` = count of all lines that passed parsing and filters (any level).
- `by level` always lists all four levels in that fixed order, `=0` for levels with no lines.
- `first`/`last` = the timestamp of the first and last (by input order, not sorted) line that passed filters; `n/a` if total is 0.
- `top errors`: only lines at level ERROR are eligible. Group by normalized message, count occurrences, sort by count descending then by the displayed (original, first-seen) message ascending for ties. Print top N groups (default 5). If fewer than N distinct groups exist, print all of them. If there are zero ERROR lines, print `top errors:` with no lines after it (not omitted).
- Each top-errors line is two-space indented: `  <count> <message>`.

## Errors

Bad `--since` (unparsable timestamp) or bad `--level` (not one of the four): exit 2, stderr `error: invalid value for --since: <value>` (or `--level`). File given but not found: exit 2, stderr `error: cannot read <path>`.

## `--help`

Lists FILE, `--since`, `--level`, `--top`. Exit 0.
