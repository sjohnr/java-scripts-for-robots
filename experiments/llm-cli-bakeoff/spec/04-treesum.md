# treesum

Purpose: compact repo overview without shelling out to `find | wc -l` chains.

## Usage

```
treesum [DIR] [--top N] [--ext-limit M]
```

`DIR` defaults to `.`. `--top N` (default 10) controls how many largest files are listed. `--ext-limit M` (default 20) caps how many distinct extensions are shown in the by-extension table (the M with the most files; remaining are folded into a synthetic `other` row summing counts/LOC for everything not in the top M).

## Ignore list (fixed, not configurable)

Directory names ignored anywhere in the tree (not descended into): `.git`, `node_modules`, `build`, `.gradle`, `target`, `dist`, `.venv`, `__pycache__`, `.idea`, `.vscode`.

Symlinks are not followed (neither to files nor directories); a symlink is simply not counted.

## Extension classification

Extension = substring after the last `.` in the filename, lowercased. A filename with no `.` (or starting with `.` and no other `.`, e.g. `.gitignore`) has extension `""`, displayed as `(none)`.

## LOC counting

LOC = number of `\n` bytes in the file, plus 1 if the file is non-empty and does not end with `\n`. Binary files are still counted this way (no binary detection) — this is a known simplification, not a bug, and is stated in the report.

## Output (stdout)

```
files: <total files counted>
dirs: <total dirs counted, excluding ignored ones and excluding the root itself>
by extension:
  <ext-or-"(none)">  files=<n> loc=<n>
  ...
largest files:
  <size-bytes> <relative-path>
  ...
```

- `by extension` rows sorted by file count descending, ties broken by extension ascending (`(none)` sorts as if the string were empty, i.e. first among ties... concretely: compare the displayed label as a plain string, and `""` < any non-empty string, so `(none)`'s ties place it before lettered extensions — but note the label printed is `(none)` while the SORT key is the empty string, not the literal text `(none)`). If `other` row exists (from `--ext-limit` folding) it sorts last regardless of count.
- `largest files` sorted by size descending, ties broken by relative path ascending, top N. Relative paths use `/` separator regardless of OS, and do not have a leading `./`.
- All paths in output are relative to DIR.

## Errors

DIR does not exist or is not a directory: exit 2, stderr `error: cannot read <path>`.

## `--help`

Lists DIR, `--top`, `--ext-limit`, and states the fixed ignore list. Exit 0.
