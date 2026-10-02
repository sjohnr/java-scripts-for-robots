#!/usr/bin/env python3
"""jarsrc: query a Gradle-cache-like tree of *-sources.jar files."""
import functools
import os
import re
import sys
import zipfile

HELP = """usage:
  jarsrc find <fqcn-or-glob> [--root DIR]
  jarsrc cat <fqcn> [--lines A:B] [--root DIR]
  jarsrc ls <group:artifact[:version]> [--root DIR]
  jarsrc --help

  --root DIR      root of the Gradle-cache-like tree (default: .)
  --lines A:B     1-indexed inclusive line range for `cat`
"""


# ---------- version comparison (spec/00-conventions.md) ----------

def split_version(v: str):
    return re.split(r"[.\-]", v)


def compare_segments(a: str, b: str) -> int:
    a_is_num = a.isdigit()
    b_is_num = b.isdigit()
    if a_is_num and b_is_num:
        ai, bi = int(a), int(b)
        return (ai > bi) - (ai < bi)
    return (a > b) - (a < b)


def compare_versions(v1: str, v2: str) -> int:
    s1 = split_version(v1)
    s2 = split_version(v2)
    for a, b in zip(s1, s2):
        c = compare_segments(a, b)
        if c != 0:
            return c
    return (len(s1) > len(s2)) - (len(s1) < len(s2))


version_cmp_key = functools.cmp_to_key(compare_versions)


# ---------- glob matching for `find` ----------

def glob_to_regex(pattern: str) -> re.Pattern:
    # '*' matches any run of chars except '.'; '**' matches any run
    # including '.'; no other wildcard syntax.
    out = []
    i = 0
    n = len(pattern)
    while i < n:
        c = pattern[i]
        if c == "*":
            if i + 1 < n and pattern[i + 1] == "*":
                out.append(".*")
                i += 2
            else:
                out.append("[^.]*")
                i += 1
        else:
            out.append(re.escape(c))
            i += 1
    return re.compile("^" + "".join(out) + "$")


# ---------- suggestions ----------

def suggestions(name: str, known_names) -> str:
    matches = sorted(n for n in known_names if n.startswith(name))
    matches = matches[:3]
    if not matches:
        return ""
    return " (did you mean: " + ", ".join(matches) + "?)"


# ---------- tree scanning ----------

class Jar:
    __slots__ = ("group", "artifact", "version", "path")

    def __init__(self, group, artifact, version, path):
        self.group = group
        self.artifact = artifact
        self.version = version
        self.path = path

    @property
    def ga(self):
        return f"{self.group}:{self.artifact}"

    @property
    def gav(self):
        return f"{self.group}:{self.artifact}:{self.version}"


def scan_jars(root: str):
    """Yield Jar objects found under root, matching
    <root>/<group-as-dirs>/<artifact>/<version>/<artifact>-<version>-sources.jar
    """
    jars = []
    for dirpath, dirnames, filenames in os.walk(root, followlinks=False):
        for fn in filenames:
            if not fn.endswith("-sources.jar"):
                continue
            full = os.path.join(dirpath, fn)
            if os.path.islink(full):
                continue
            # version dir is dirpath's basename; artifact dir is its parent;
            # group is everything between root and artifact dir.
            version_dir = dirpath
            version = os.path.basename(version_dir)
            artifact_dir = os.path.dirname(version_dir)
            artifact = os.path.basename(artifact_dir)
            group_dir = os.path.dirname(artifact_dir)
            rel_group = os.path.relpath(group_dir, root)
            if rel_group == ".":
                group = ""
            else:
                group = rel_group.replace(os.sep, "/").replace("/", ".")
            expected_name = f"{artifact}-{version}-sources.jar"
            if fn != expected_name:
                continue
            jars.append(Jar(group, artifact, version, full))
    return jars


def jar_java_entries(jar: Jar):
    """Return list of FQCNs contained in this jar."""
    fqcns = []
    try:
        with zipfile.ZipFile(jar.path) as zf:
            for name in zf.namelist():
                if name.endswith(".java") and not name.endswith("/"):
                    fqcn = name[:-len(".java")].replace("/", ".")
                    fqcns.append(fqcn)
    except (zipfile.BadZipFile, OSError):
        pass
    return fqcns


def jar_read_source(jar: Jar, fqcn: str):
    inner = fqcn.replace(".", "/") + ".java"
    try:
        with zipfile.ZipFile(jar.path) as zf:
            with zf.open(inner) as f:
                return f.read().decode("utf-8", errors="replace")
    except (zipfile.BadZipFile, OSError, KeyError):
        return None


def error(msg: str, code: int):
    sys.stderr.write(f"error: {msg}\n")
    return code


# ---------- subcommands ----------

def cmd_find(args, root):
    if not args:
        sys.stderr.write("unknown flag: (missing fqcn-or-glob)\n")
        return 2
    pattern_str = args[0]
    rx = glob_to_regex(pattern_str)

    jars = scan_jars(root)
    results = []  # (fqcn, gav)
    all_simple_names = set()
    for jar in jars:
        for fqcn in jar_java_entries(jar):
            simple = fqcn.rsplit(".", 1)[-1]
            all_simple_names.add(simple)
            if "*" in pattern_str:
                if rx.match(fqcn):
                    results.append((fqcn, jar.gav))
            else:
                if fqcn == pattern_str:
                    results.append((fqcn, jar.gav))

    if not results:
        query_simple = pattern_str.rsplit(".", 1)[-1]
        sug = suggestions(query_simple, all_simple_names)
        sys.stderr.write(f"error: no matches for {pattern_str}{sug}\n")
        return 1

    results.sort(key=lambda t: (t[0], t[1]))
    out = [f"{fqcn} {gav}" for fqcn, gav in results]
    sys.stdout.write("\n".join(out) + "\n")
    return 0


def cmd_cat(args, root, lines_arg):
    if not args:
        sys.stderr.write("unknown flag: (missing fqcn)\n")
        return 2
    fqcn = args[0]

    jars = scan_jars(root)
    # Map (group, artifact) -> list of (version, jar) that contain fqcn
    matches_by_ga = {}
    all_simple_names = set()

    for jar in jars:
        fqcns = jar_java_entries(jar)
        for f in fqcns:
            all_simple_names.add(f.rsplit(".", 1)[-1])
        if fqcn in fqcns:
            matches_by_ga.setdefault(jar.ga, []).append(jar)

    if not matches_by_ga:
        query_simple = fqcn.rsplit(".", 1)[-1]
        sug = suggestions(query_simple, all_simple_names)
        sys.stderr.write(f"error: no source for {fqcn}{sug}\n")
        return 1

    if len(matches_by_ga) > 1:
        gas = sorted(matches_by_ga.keys())
        sys.stderr.write(f"error: {fqcn} is ambiguous across: {', '.join(gas)}\n")
        return 2

    (ga, jar_list), = matches_by_ga.items()
    best = max(jar_list, key=lambda j: version_cmp_key(j.version))
    source = jar_read_source(best, fqcn)
    if source is None:
        sys.stderr.write(f"error: no source for {fqcn}\n")
        return 1

    if source.endswith("\n"):
        src_lines = source[:-1].split("\n")
    else:
        src_lines = source.split("\n") if source else []

    if lines_arg is not None:
        try:
            a_str, b_str = lines_arg.split(":", 1)
            a, b = int(a_str), int(b_str)
        except ValueError:
            sys.stderr.write(f"error: invalid value for --lines: {lines_arg}\n")
            return 2
        total = len(src_lines)
        if a > total:
            return 0
        if a < 1:
            a = 1
        if b > total:
            b = total
        if b < a:
            return 0
        selected = src_lines[a - 1:b]
    else:
        selected = src_lines

    if selected:
        sys.stdout.write("\n".join(selected) + "\n")
    return 0


def cmd_ls(args, root):
    if not args:
        sys.stderr.write("unknown flag: (missing group:artifact[:version])\n")
        return 2
    spec = args[0]
    parts = spec.split(":")
    if len(parts) == 2:
        group, artifact = parts
        version = None
    elif len(parts) == 3:
        group, artifact, version = parts
    else:
        sys.stderr.write(f"error: no artifact {spec}\n")
        return 1

    jars = scan_jars(root)
    all_ga = set(j.ga for j in jars)
    ga_key = f"{group}:{artifact}"

    matching = [j for j in jars if j.group == group and j.artifact == artifact]

    if not matching:
        sug = suggestions(ga_key, all_ga)
        sys.stderr.write(f"error: no artifact {ga_key}{sug}\n")
        return 1

    if version is None:
        versions = sorted({j.version for j in matching}, key=version_cmp_key)
        sys.stdout.write("\n".join(versions) + "\n" if versions else "")
        return 0
    else:
        version_matches = [j for j in matching if j.version == version]
        if not version_matches:
            sug = suggestions(ga_key, all_ga)
            sys.stderr.write(f"error: no artifact {ga_key}:{version}{sug}\n")
            return 1
        jar = version_matches[0]
        fqcns = sorted(jar_java_entries(jar))
        sys.stdout.write("\n".join(fqcns) + "\n" if fqcns else "")
        return 0


def main(argv):
    if not argv:
        sys.stderr.write("unknown flag: (missing subcommand)\n")
        return 2

    if argv[0] == "--help":
        sys.stdout.write(HELP)
        return 0

    subcommand = argv[0]
    if subcommand not in ("find", "cat", "ls"):
        sys.stderr.write(f"unknown flag: {subcommand}\n")
        return 2

    rest = argv[1:]
    positional = []
    root = "."
    lines_arg = None

    i = 0
    while i < len(rest):
        arg = rest[i]
        if arg == "--help":
            sys.stdout.write(HELP)
            return 0
        elif arg == "--root":
            i += 1
            if i >= len(rest):
                sys.stderr.write("unknown flag: --root\n")
                return 2
            root = rest[i]
        elif arg.startswith("--root="):
            root = arg[len("--root="):]
        elif arg == "--lines":
            i += 1
            if i >= len(rest):
                sys.stderr.write("unknown flag: --lines\n")
                return 2
            lines_arg = rest[i]
        elif arg.startswith("--lines="):
            lines_arg = arg[len("--lines="):]
        elif arg.startswith("-") and arg != "-":
            sys.stderr.write(f"unknown flag: {arg}\n")
            return 2
        else:
            positional.append(arg)
        i += 1

    if subcommand == "find":
        return cmd_find(positional, root)
    elif subcommand == "cat":
        return cmd_cat(positional, root, lines_arg)
    elif subcommand == "ls":
        return cmd_ls(positional, root)


if __name__ == "__main__":
    sys.exit(main(sys.argv[1:]))
