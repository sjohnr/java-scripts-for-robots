#!/usr/bin/env python3
"""logsum: streaming summary of a log file (or stdin)."""
import re
import sys

LEVELS = ("DEBUG", "INFO", "WARN", "ERROR")

HELP = """usage: logsum [FILE] [--since TIMESTAMP] [--level LEVEL] [--top N]

  FILE            log file to read (omit or use "-" for stdin)
  --since TS      only consider lines with TIMESTAMP >= TS
  --level LEVEL   only consider lines at exactly this level
  --top N         number of top error groups to print (default 5)
"""

TS_RE = re.compile(r"^\d{4}-\d{2}-\d{2}T\d{2}:\d{2}:\d{2}$")

UUID_RE = re.compile(
    r"[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}"
)
HEX_RE = re.compile(r"\b(?=[0-9a-fA-F]*[a-fA-F])[0-9a-fA-F]{6,}\b")
NUM_RE = re.compile(r"[0-9]+")


def normalize(message: str) -> str:
    message = UUID_RE.sub("<uuid>", message)
    message = HEX_RE.sub("<hex>", message)
    message = NUM_RE.sub("<num>", message)
    return message


def is_valid_timestamp(ts: str) -> bool:
    return bool(TS_RE.match(ts))


def parse_line(line: str):
    # Strip trailing newline only (not other whitespace, to preserve message
    # content exactly). Handle both \n and \r\n.
    if line.endswith("\n"):
        line = line[:-1]
    if line.endswith("\r"):
        line = line[:-1]

    # TIMESTAMP LEVEL MESSAGE, split on single spaces for first two fields.
    first_sp = line.find(" ")
    if first_sp == -1:
        return None
    ts = line[:first_sp]
    rest = line[first_sp + 1:]
    second_sp = rest.find(" ")
    if second_sp == -1:
        return None
    level = rest[:second_sp]
    message = rest[second_sp + 1:]

    if not is_valid_timestamp(ts):
        return None
    if level not in LEVELS:
        return None
    return ts, level, message


def error(msg: str, code: int):
    sys.stderr.write(f"error: {msg}\n")
    sys.exit(code)


def main(argv):
    file_arg = None
    since = None
    level_filter = None
    top_n = 5

    i = 0
    while i < len(argv):
        arg = argv[i]
        if arg == "--help":
            sys.stdout.write(HELP)
            return 0
        elif arg == "--since":
            i += 1
            if i >= len(argv):
                sys.stderr.write("unknown flag: --since\n")
                return 2
            since = argv[i]
        elif arg.startswith("--since="):
            since = arg[len("--since="):]
        elif arg == "--level":
            i += 1
            if i >= len(argv):
                sys.stderr.write("unknown flag: --level\n")
                return 2
            level_filter = argv[i]
        elif arg.startswith("--level="):
            level_filter = arg[len("--level="):]
        elif arg == "--top":
            i += 1
            if i >= len(argv):
                sys.stderr.write("unknown flag: --top\n")
                return 2
            top_n = argv[i]
        elif arg.startswith("--top="):
            top_n = arg[len("--top="):]
        elif arg.startswith("-") and arg != "-":
            sys.stderr.write(f"unknown flag: {arg}\n")
            return 2
        else:
            if file_arg is None:
                file_arg = arg
            else:
                sys.stderr.write(f"unknown flag: {arg}\n")
                return 2
        i += 1

    if since is not None and not is_valid_timestamp(since):
        sys.stderr.write(f"error: invalid value for --since: {since}\n")
        return 2

    if level_filter is not None and level_filter not in LEVELS:
        sys.stderr.write(f"error: invalid value for --level: {level_filter}\n")
        return 2

    try:
        top_n = int(top_n)
    except (TypeError, ValueError):
        sys.stderr.write(f"error: invalid value for --top: {top_n}\n")
        return 2

    if file_arg is None or file_arg == "-":
        stream = sys.stdin
        close_after = False
    else:
        try:
            stream = open(file_arg, "r", encoding="utf-8", errors="replace", newline="")
        except OSError:
            sys.stderr.write(f"error: cannot read {file_arg}\n")
            return 2
        close_after = True

    total = 0
    by_level = {lvl: 0 for lvl in LEVELS}
    first_ts = None
    last_ts = None
    # group key -> [count, first_seen_original_message]
    groups = {}

    try:
        for raw_line in stream:
            parsed = parse_line(raw_line)
            if parsed is None:
                continue
            ts, level, message = parsed

            if since is not None and ts < since:
                continue
            if level_filter is not None and level != level_filter:
                continue

            total += 1
            by_level[level] += 1
            if first_ts is None:
                first_ts = ts
            last_ts = ts

            if level == "ERROR":
                key = normalize(message)
                if key not in groups:
                    groups[key] = [0, message]
                groups[key][0] += 1
    finally:
        if close_after:
            stream.close()

    out = []
    out.append(f"total: {total}")
    out.append(
        "by level: "
        + " ".join(f"{lvl}={by_level[lvl]}" for lvl in LEVELS)
    )
    out.append(f"first: {first_ts if first_ts is not None else 'n/a'}")
    out.append(f"last: {last_ts if last_ts is not None else 'n/a'}")
    out.append("top errors:")

    ranked = sorted(groups.items(), key=lambda kv: (-kv[1][0], kv[1][1]))
    for _, (count, msg) in ranked[:top_n]:
        out.append(f"  {count} {msg}")

    sys.stdout.write("\n".join(out) + "\n")
    return 0


if __name__ == "__main__":
    sys.exit(main(sys.argv[1:]))
