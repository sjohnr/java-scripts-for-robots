#!/usr/bin/env python3
"""treesum: compact repo overview without shelling out to find | wc -l chains."""
import os
import sys

IGNORE_DIRS = {
    ".git", "node_modules", "build", ".gradle", "target", "dist",
    ".venv", "__pycache__", ".idea", ".vscode",
}

HELP = """usage: treesum [DIR] [--top N] [--ext-limit M]

  DIR             directory to summarize (default: .)
  --top N         number of largest files to list (default 10)
  --ext-limit M   max distinct extensions shown in the by-extension table
                  (default 20); remainder folded into an "other" row

Ignored directory names (not descended into): .git, node_modules, build,
.gradle, target, dist, .venv, __pycache__, .idea, .vscode
"""


def count_loc(path: str) -> int:
    nl = 0
    last_byte = b""
    with open(path, "rb") as f:
        while True:
            chunk = f.read(1 << 20)
            if not chunk:
                break
            nl += chunk.count(b"\n")
            last_byte = chunk[-1:]
    if last_byte == b"":
        return 0
    if last_byte == b"\n":
        return nl
    return nl + 1


def main(argv):
    dir_arg = None
    top_n = 10
    ext_limit = 20

    i = 0
    while i < len(argv):
        arg = argv[i]
        if arg == "--help":
            sys.stdout.write(HELP)
            return 0
        elif arg == "--top":
            i += 1
            if i >= len(argv):
                sys.stderr.write("unknown flag: --top\n")
                return 2
            top_n = argv[i]
        elif arg.startswith("--top="):
            top_n = arg[len("--top="):]
        elif arg == "--ext-limit":
            i += 1
            if i >= len(argv):
                sys.stderr.write("unknown flag: --ext-limit\n")
                return 2
            ext_limit = argv[i]
        elif arg.startswith("--ext-limit="):
            ext_limit = arg[len("--ext-limit="):]
        elif arg.startswith("-") and arg != "-":
            sys.stderr.write(f"unknown flag: {arg}\n")
            return 2
        else:
            if dir_arg is None:
                dir_arg = arg
            else:
                sys.stderr.write(f"unknown flag: {arg}\n")
                return 2
        i += 1

    try:
        top_n = int(top_n)
    except (TypeError, ValueError):
        sys.stderr.write(f"error: invalid value for --top: {top_n}\n")
        return 2
    try:
        ext_limit = int(ext_limit)
    except (TypeError, ValueError):
        sys.stderr.write(f"error: invalid value for --ext-limit: {ext_limit}\n")
        return 2

    root = dir_arg if dir_arg is not None else "."

    if not os.path.isdir(root):
        sys.stderr.write(f"error: cannot read {root}\n")
        return 2

    total_files = 0
    total_dirs = 0
    ext_files = {}
    ext_loc = {}
    file_sizes = []  # (size, relpath)

    for dirpath, dirnames, filenames in os.walk(root, topdown=True, followlinks=False):
        # Prune ignored + symlinked directories.
        pruned = []
        for d in dirnames:
            full = os.path.join(dirpath, d)
            if d in IGNORE_DIRS:
                continue
            if os.path.islink(full):
                continue
            pruned.append(d)
        dirnames[:] = pruned

        if dirpath != root:
            total_dirs += 1

        for fn in filenames:
            full = os.path.join(dirpath, fn)
            if os.path.islink(full):
                continue
            try:
                size = os.path.getsize(full)
            except OSError:
                continue

            last_dot = fn.rfind(".")
            if last_dot <= 0:
                # No "." at all, or the only "." is a leading one (e.g.
                # ".gitignore") -> extension is "".
                ext = ""
            else:
                ext = fn[last_dot + 1:].lower()

            loc = count_loc(full)

            total_files += 1
            ext_files[ext] = ext_files.get(ext, 0) + 1
            ext_loc[ext] = ext_loc.get(ext, 0) + loc

            relpath = os.path.relpath(full, root)
            relpath = relpath.replace(os.sep, "/")
            file_sizes.append((size, relpath))

    # by-extension table: sort by file count desc, tie-break by label
    # ascending where "(none)" sorts using empty-string key.
    ext_items = sorted(ext_files.items(), key=lambda kv: (-kv[1], kv[0]))

    if len(ext_items) > ext_limit:
        kept = ext_items[:ext_limit]
        folded = ext_items[ext_limit:]
        other_files = sum(ext_files[e] for e, _ in folded)
        other_loc = sum(ext_loc[e] for e, _ in folded)
        display_rows = [(e, ext_files[e], ext_loc[e]) for e, _ in kept]
        display_rows.append(("other", other_files, other_loc))
    else:
        display_rows = [(e, ext_files[e], ext_loc[e]) for e, _ in ext_items]

    file_sizes.sort(key=lambda t: (-t[0], t[1]))
    largest = file_sizes[:top_n] if top_n >= 0 else []

    out = []
    out.append(f"files: {total_files}")
    out.append(f"dirs: {total_dirs}")
    out.append("by extension:")
    for ext, nfiles, nloc in display_rows:
        label = "(none)" if ext == "" else ext
        out.append(f"  {label}  files={nfiles} loc={nloc}")
    out.append("largest files:")
    for size, relpath in largest:
        out.append(f"  {size} {relpath}")

    sys.stdout.write("\n".join(out) + "\n")
    return 0


if __name__ == "__main__":
    sys.exit(main(sys.argv[1:]))
