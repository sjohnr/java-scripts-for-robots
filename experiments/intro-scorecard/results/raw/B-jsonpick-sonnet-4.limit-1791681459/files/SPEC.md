# jsonpick

Purpose: extract a value by path from JSON.

## General rules

- All text output is UTF-8, LF line endings (no CRLF, on any platform), no trailing whitespace on any line.
- Nothing may be printed to stdout or stderr beyond what this spec defines as output. In particular: no banners, notices or warnings on the success path.
- Exit code `0` = success. Exit code `1` = a well-formed "not found" condition described below. Exit code `2` = usage error (bad flags, missing required argument, unparsable input). No other exit codes are used.
- `--help` prints a usage message to stdout and exits 0. Exact wording is free, but it must list every flag the CLI supports.
- Unknown flags are a usage error (exit 2) with message `unknown flag: <flag>` on stderr.
- stdout, stderr and the exit code must match this spec exactly (except the wording of `--help` and the reason text of a JSON parse error).

## Usage

```
jsonpick PATH [FILE] [--shape] [--depth N]
```

`FILE` omitted or `-` means stdin. `PATH` is required (even with `--shape`, where it selects the subtree to summarize; `PATH` of `.` or empty string `""` means the whole document).

## Path syntax

`a.b[0].c` — dot-separated object keys, `[N]` for zero-based array index, may be chained arbitrarily, e.g. `a.b[0][1].c`. A leading `.` or empty path selects the document root. No quoting/escaping support for keys containing `.` or `[` — that is an explicit unsupported case.

## Default mode (no `--shape`)

Navigate PATH from the root. If the path resolves:
- to a JSON string: print the string's raw characters (no quotes) followed by `\n`.
- to a JSON number, boolean, or null: print its canonical JSON literal form followed by `\n` (numbers: no unnecessary trailing zeros beyond what's needed to round-trip; integers print without a decimal point).
- to a JSON object or array: print it as compact JSON (no extra whitespace, keys in original document order, no trailing newline added beyond one final `\n`).

Path does not resolve (missing key, index out of range, indexing into a non-array, keying into a non-object): exit 1, stderr `error: path not found: <path>`.

Malformed JSON input: exit 2, stderr `error: invalid JSON: <one-line reason>` (exact reason text is free, but must name line/col or byte offset of the failure).

## `--shape` mode

Ignores the "print value" behavior above; instead prints a depth-limited type/array-length summary of the subtree at PATH. `--depth N` (default 2) controls how many levels deep to describe.

Grammar (recursive, stops descending past depth N):
- string -> `string`
- number -> `number`
- boolean -> `boolean`
- null -> `null`
- array of length L -> `array[<L>]` if depth budget is 0, else `array[<L>] of <shape of first element, budget-1>` — if the array is empty, just `array[0]`.
- object -> if depth budget is 0: `object{<N keys>}` where N is the key count. Else: `object{` followed by `key: <shape>` pairs joined by `, `, keys in original document order, budget-1 for each value, closed by `}`. Empty object: `object{}`.

Output is this shape description followed by `\n`, single line, no trailing whitespace.

## Errors

Same malformed-JSON and path-not-found errors as default mode apply identically under `--shape`.

## `--help`

Lists PATH, FILE, `--shape`, `--depth`. Exit 0.
