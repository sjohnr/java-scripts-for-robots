# jsonpick

Purpose: extract a value by path from JSON without a full jq dependency; also the "Java has no JSON in the JDK" data point (hand-rolled parser vs JBang+library variant).

## Usage

```
jsonpick PATH [FILE] [--shape] [--depth N]
```

`FILE` omitted or `-` means stdin. `PATH` is required (even with `--shape`, where it selects the subtree to summarize; `PATH` of `.` or empty string `""` means the whole document).

## Path syntax

`a.b[0].c` — dot-separated object keys, `[N]` for zero-based array index, may be chained arbitrarily, e.g. `a.b[0][1].c`. A leading `.` or empty path selects the document root. No quoting/escaping support for keys containing `.` or `[` — that is an explicit unsupported case (not a fixture case).

## Default mode (no `--shape`)

Navigate PATH from the root. If the path resolves:
- to a JSON string: print the string's raw characters (no quotes) followed by `\n`.
- to a JSON number, boolean, or null: print its canonical JSON literal form followed by `\n` (numbers: no unnecessary trailing zeros beyond what's needed to round-trip; integers print without a decimal point).
- to a JSON object or array: print it as compact JSON (no extra whitespace, keys in original document order, no trailing newline added beyond one final `\n`).

Path does not resolve (missing key, index out of range, indexing into a non-array, keying into a non-object): exit 1, stderr `error: path not found: <path>`.

Malformed JSON input: exit 2, stderr `error: invalid JSON: <one-line reason>` (exact reason text is not cross-language compared, but must name line/col or byte offset of the failure).

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

## Java variants

Two Java implementations exist for this CLI only:
- (a) hand-rolled minimal recursive-descent JSON parser, stdlib only.
- (b) JBang script with `//DEPS` bringing in Jackson (or Gson), if JBang is available.
Both must pass the same conformance suite. Effort, size, and startup-cost differences between (a) and (b) are a named finding in the report, not hidden.
