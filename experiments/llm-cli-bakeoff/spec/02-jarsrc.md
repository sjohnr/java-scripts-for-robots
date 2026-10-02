# jarsrc

Purpose: query a Gradle-cache-like tree of `*-sources.jar` files without shelling out to `find`/`unzip`/`grep`.

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

Prints the source of `<fqcn>` from the **highest version** (per spec/00-conventions.md ordering) among all artifacts under root that contain that FQCN. If the FQCN appears in more than one distinct group:artifact (not just more than one version of the same artifact), that is an ambiguity error: exit 2, stderr:
```
error: <fqcn> is ambiguous across: <ga1>, <ga2>, ...
```
(group:artifact pairs, no version, sorted lexicographically, all of them listed, no cap).

`--lines A:B` (1-indexed, inclusive) prints only that line range; omitted lines are just not printed (no elision marker). If A or B is out of range, clamp silently to the available range. If A > total lines, print nothing (stdout empty, exit 0 still, this is not an error).

Not found (FQCN not present anywhere under root): exit 1, stderr `error: no source for <fqcn>` with prefix suggestions against all known FQCNs' simple names.

### ls

`<group:artifact>` (version omitted) lists every version directory found for that group:artifact, one per line, sorted by the version ordering in spec/00-conventions.md, ascending.

`<group:artifact:version>` lists every `.java` entry's FQCN inside that exact version's sources jar, one per line, sorted lexicographically.

Not found: exit 1, stderr `error: no artifact <group:artifact>` (or `...:version` if version was given and only the version is missing but group:artifact exists) with suggestions computed against known `group:artifact` strings.

## `--help`

Lists all three subcommands and both flags (`--root`, `--lines`). Exit 0.
