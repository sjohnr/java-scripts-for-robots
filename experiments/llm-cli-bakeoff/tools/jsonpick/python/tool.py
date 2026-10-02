#!/usr/bin/env python3
"""jsonpick: extract a value by path from JSON without a jq dependency."""
import json
import sys

HELP = """usage: jsonpick PATH [FILE] [--shape] [--depth N]

  PATH      dot/bracket path into the document, e.g. a.b[0].c
            ("." or "" selects the whole document)
  FILE      JSON file to read (omit or use "-" for stdin)
  --shape   print a depth-limited type/shape summary instead of the value
  --depth N depth for --shape (default 2)
"""


# ---------- path parsing ----------

class PathError(Exception):
    pass


def parse_path(path: str):
    """Parse 'a.b[0].c' into a list of tokens: strings (object keys) or
    ints (array indices)."""
    if path in ("", "."):
        return []
    tokens = []
    i = 0
    n = len(path)
    # Allow an optional leading '.'
    if i < n and path[i] == ".":
        i += 1
    cur = []

    def flush():
        if cur:
            tokens.append("".join(cur))
            cur.clear()

    while i < n:
        c = path[i]
        if c == ".":
            flush()
            i += 1
        elif c == "[":
            flush()
            j = path.index("]", i)
            idx_str = path[i + 1:j]
            tokens.append(int(idx_str))
            i = j + 1
        else:
            cur.append(c)
            i += 1
    flush()
    return tokens


def navigate(doc, tokens):
    cur = doc
    for tok in tokens:
        if isinstance(tok, int):
            if not isinstance(cur, list):
                raise PathError()
            if tok < 0 or tok >= len(cur):
                raise PathError()
            cur = cur[tok]
        else:
            if not isinstance(cur, dict):
                raise PathError()
            if tok not in cur:
                raise PathError()
            cur = cur[tok]
    return cur


# ---------- value printing ----------

def format_number(n):
    if isinstance(n, bool):
        return "true" if n else "false"
    if isinstance(n, int):
        return str(n)
    # float: canonical round-trip form, no unnecessary trailing zeros,
    # integral floats print without a decimal point per spec ("numbers:
    # ... integers print without a decimal point").
    if n == int(n) and abs(n) < 1e16:
        return str(int(n))
    return repr(n)


def compact_json(value) -> str:
    """Serialize as compact JSON with no extra whitespace, preserving key
    order, using the same number formatting as scalar output (so results
    are consistent whether a number is printed standalone or nested)."""
    if isinstance(value, str):
        return json.dumps(value, ensure_ascii=False)
    if value is None:
        return "null"
    if isinstance(value, bool):
        return "true" if value else "false"
    if isinstance(value, (int, float)):
        return format_number(value)
    if isinstance(value, list):
        return "[" + ",".join(compact_json(v) for v in value) + "]"
    if isinstance(value, dict):
        return "{" + ",".join(
            json.dumps(k, ensure_ascii=False) + ":" + compact_json(v)
            for k, v in value.items()
        ) + "}"
    return "null"


def print_default(value):
    if isinstance(value, str):
        sys.stdout.write(value + "\n")
    elif value is None:
        sys.stdout.write("null\n")
    elif isinstance(value, bool):
        sys.stdout.write(("true" if value else "false") + "\n")
    elif isinstance(value, (int, float)):
        sys.stdout.write(format_number(value) + "\n")
    else:
        sys.stdout.write(compact_json(value) + "\n")


def shape_of(value, depth):
    if isinstance(value, str):
        return "string"
    if isinstance(value, bool):
        return "boolean"
    if isinstance(value, (int, float)):
        return "number"
    if value is None:
        return "null"
    if isinstance(value, list):
        L = len(value)
        if depth <= 0 or L == 0:
            return f"array[{L}]"
        return f"array[{L}] of {shape_of(value[0], depth - 1)}"
    if isinstance(value, dict):
        n = len(value)
        if depth <= 0:
            return f"object{{{n} keys}}"
        if n == 0:
            return "object{}"
        parts = [f"{k}: {shape_of(v, depth - 1)}" for k, v in value.items()]
        return "object{" + ", ".join(parts) + "}"
    return "unknown"


def main(argv):
    path_str = None
    file_arg = None
    shape_mode = False
    depth = 2

    positional = []
    i = 0
    while i < len(argv):
        arg = argv[i]
        if arg == "--help":
            sys.stdout.write(HELP)
            return 0
        elif arg == "--shape":
            shape_mode = True
        elif arg == "--depth":
            i += 1
            if i >= len(argv):
                sys.stderr.write("unknown flag: --depth\n")
                return 2
            depth = argv[i]
        elif arg.startswith("--depth="):
            depth = arg[len("--depth="):]
        elif arg.startswith("-") and arg != "-":
            sys.stderr.write(f"unknown flag: {arg}\n")
            return 2
        else:
            positional.append(arg)
        i += 1

    if not positional:
        sys.stderr.write("unknown flag: (missing PATH)\n")
        return 2

    path_str = positional[0]
    if len(positional) > 1:
        file_arg = positional[1]
    if len(positional) > 2:
        sys.stderr.write(f"unknown flag: {positional[2]}\n")
        return 2

    try:
        depth = int(depth)
    except (TypeError, ValueError):
        sys.stderr.write(f"error: invalid value for --depth: {depth}\n")
        return 2

    if file_arg is None or file_arg == "-":
        raw = sys.stdin.read()
    else:
        try:
            with open(file_arg, "r", encoding="utf-8") as f:
                raw = f.read()
        except OSError:
            sys.stderr.write(f"error: cannot read {file_arg}\n")
            return 2

    try:
        doc = json.loads(raw)
    except json.JSONDecodeError as e:
        sys.stderr.write(
            f"error: invalid JSON: line {e.lineno} col {e.colno} (char {e.pos}): {e.msg}\n"
        )
        return 2

    try:
        tokens = parse_path(path_str)
    except (ValueError, IndexError):
        sys.stderr.write(f"error: path not found: {path_str}\n")
        return 1

    try:
        value = navigate(doc, tokens)
    except PathError:
        sys.stderr.write(f"error: path not found: {path_str}\n")
        return 1

    if shape_mode:
        sys.stdout.write(shape_of(value, depth) + "\n")
    else:
        print_default(value)

    return 0


if __name__ == "__main__":
    sys.exit(main(sys.argv[1:]))
