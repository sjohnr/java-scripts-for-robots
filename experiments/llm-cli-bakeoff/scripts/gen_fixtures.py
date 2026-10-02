#!/usr/bin/env python3
"""Deterministic, seeded fixture generator for the llm-cli-bakeoff suite.

Usage:
  python3 gen_fixtures.py small OUT_DIR
  python3 gen_fixtures.py large OUT_DIR

Produces, under OUT_DIR:
  jarsrc/            a Gradle-cache-like tree of *-sources.jar files
  logsum.log         a log file
  treesum/            a file tree
  jsonpick.json       a JSON document
  CHECKSUMS.txt        sha256 of every generated top-level artifact (the
                        .log and .json files, and a manifest of the
                        jarsrc/treesum trees) so results are reproducible
                        without committing large binaries.

Same seed -> byte-identical output on any machine. Only the generator and
checksums are committed to source control; generated fixtures themselves
(especially the "large" scenario) are not.
"""
import hashlib
import io
import json
import os
import random
import sys
import zipfile

SEED = 20260919

IGNORE_DIRS = {".git", "node_modules", "build", ".gradle", "target", "dist", ".venv", "__pycache__", ".idea", ".vscode"}

GROUPS = ["com.example.core", "com.example.util", "org.sample.io", "org.sample.net", "io.acme.widgets"]
ARTIFACTS = ["alpha", "beta", "gamma", "delta", "epsilon", "zeta", "eta", "theta"]
LEVELS = ["DEBUG", "INFO", "WARN", "ERROR"]
EXTS = ["java", "py", "go", "js", "md", "json", "txt", "yaml", ""]

ERROR_TEMPLATES = [
    "failed to connect to {host} after {n} attempts",
    "request {uuid} timed out",
    "checksum mismatch: expected {hex} got {hex2}",
    "null pointer in handler for user {n}",
    "disk write failed at offset {n}",
]


def rand_hex(rng, n):
    return "".join(rng.choice("0123456789abcdef") for _ in range(n))


def rand_uuid(rng):
    return f"{rand_hex(rng,8)}-{rand_hex(rng,4)}-{rand_hex(rng,4)}-{rand_hex(rng,4)}-{rand_hex(rng,12)}"


def gen_version(rng, i):
    major = 1 + (i % 3)
    minor = rng.randint(0, 20)
    patch = rng.randint(0, 9)
    return f"{major}.{minor}.{patch}"


def java_source(fqcn, rng):
    simple = fqcn.rsplit(".", 1)[-1]
    pkg = fqcn.rsplit(".", 1)[0] if "." in fqcn else ""
    lines = []
    if pkg:
        lines.append(f"package {pkg};")
        lines.append("")
    lines.append(f"public class {simple} {{")
    nmethods = rng.randint(1, 5)
    for m in range(nmethods):
        lines.append(f"    public int method{m}(int x) {{")
        lines.append(f"        return x + {rng.randint(0,100)};")
        lines.append("    }")
    lines.append("}")
    lines.append("")
    return "\n".join(lines)


def gen_jarsrc(root, n_jars, rng):
    os.makedirs(root, exist_ok=True)
    made = 0
    ga_seen = {}
    idx = 0
    while made < n_jars:
        group = GROUPS[idx % len(GROUPS)]
        artifact = ARTIFACTS[(idx // len(GROUPS)) % len(ARTIFACTS)]
        ga = (group, artifact)
        nversions = ga_seen.get(ga)
        if nversions is None:
            nversions = rng.randint(1, 3)
            ga_seen[ga] = nversions
        for v in range(nversions):
            if made >= n_jars:
                break
            version = gen_version(rng, v)
            group_path = group.replace(".", "/")
            dir_path = os.path.join(root, group_path, artifact, version)
            os.makedirs(dir_path, exist_ok=True)
            jar_path = os.path.join(dir_path, f"{artifact}-{version}-sources.jar")
            nclasses = rng.randint(2, 6)
            buf = io.BytesIO()
            with zipfile.ZipFile(buf, "w", zipfile.ZIP_DEFLATED) as zf:
                for c in range(nclasses):
                    cname = f"Widget{c}" if c else "Main"
                    fqcn = f"{group}.{artifact}.{cname}"
                    inner_path = fqcn.replace(".", "/") + ".java"
                    zf.writestr(inner_path, java_source(fqcn, rng))
            with open(jar_path, "wb") as f:
                f.write(buf.getvalue())
            made += 1
        idx += 1
    return root


def gen_logsum(path, n_lines, rng):
    with open(path, "w", newline="\n") as f:
        y, mo, d, h, mi, s = 2026, 1, 1, 0, 0, 0
        for i in range(n_lines):
            s += rng.randint(0, 3)
            mi += s // 60
            s %= 60
            h += mi // 60
            mi %= 60
            d += h // 24
            h %= 24
            ts = f"{y:04d}-{mo:02d}-{min(d,28):02d}T{h:02d}:{mi:02d}:{s:02d}"
            level = rng.choices(LEVELS, weights=[30, 50, 15, 5])[0]
            if level == "ERROR":
                t = rng.choice(ERROR_TEMPLATES)
                msg = t.format(host=f"host{rng.randint(1,9)}.local", n=rng.randint(1,999999),
                                uuid=rand_uuid(rng), hex=rand_hex(rng, 8), hex2=rand_hex(rng, 8))
            else:
                msg = f"{level.lower()} event {rng.randint(1,999999)} processed in {rng.randint(1,500)}ms"
            f.write(f"{ts} {level} {msg}\n")


def gen_treesum(root, n_files, rng):
    os.makedirs(root, exist_ok=True)
    dirs = [root]
    for i in range(max(1, n_files // 25)):
        parent = rng.choice(dirs)
        d = os.path.join(parent, f"dir{i}")
        os.makedirs(d, exist_ok=True)
        dirs.append(d)
    for i in range(n_files):
        parent = rng.choice(dirs)
        ext = rng.choice(EXTS)
        name = f"file{i}" + (f".{ext}" if ext else "")
        path = os.path.join(parent, name)
        nlines = rng.randint(0, 200)
        with open(path, "w", newline="\n") as f:
            for l in range(nlines):
                f.write(f"line {l} " + rand_hex(rng, 8) + "\n")
    return root


def gen_jsonpick(path, n_items, rng):
    doc = {
        "meta": {"generated": "2026-09-19", "count": n_items},
        "items": [],
    }
    for i in range(n_items):
        doc["items"].append({
            "id": i,
            "name": f"item-{i}",
            "tags": [rng.choice(["a", "b", "c"]) for _ in range(rng.randint(0, 3))],
            "value": round(rng.uniform(0, 1000), 2),
            "active": rng.choice([True, False]),
            "nested": {"depth2": {"depth3": rng.randint(0, 100)}},
        })
    with open(path, "w", newline="\n") as f:
        json.dump(doc, f, separators=(",", ":"))


def sha256_file(path):
    h = hashlib.sha256()
    with open(path, "rb") as f:
        for chunk in iter(lambda: f.read(1 << 20), b""):
            h.update(chunk)
    return h.hexdigest()


def sha256_tree_manifest(root):
    h = hashlib.sha256()
    for dirpath, dirnames, filenames in os.walk(root):
        dirnames[:] = sorted(d for d in dirnames if d not in IGNORE_DIRS)
        for fn in sorted(filenames):
            p = os.path.join(dirpath, fn)
            rel = os.path.relpath(p, root)
            h.update(rel.encode("utf-8"))
            h.update(sha256_file(p).encode("utf-8"))
    return h.hexdigest()


def main():
    if len(sys.argv) != 3 or sys.argv[1] not in ("small", "large"):
        print("usage: gen_fixtures.py {small|large} OUT_DIR", file=sys.stderr)
        sys.exit(2)
    scenario, out_dir = sys.argv[1], sys.argv[2]
    sizes = {
        "small": dict(jars=10, log_lines=1000, tree_files=200, json_items=120),
        "large": dict(jars=2000, log_lines=2_000_000, tree_files=50_000, json_items=900_000),
    }[scenario]

    os.makedirs(out_dir, exist_ok=True)
    rng = random.Random(SEED ^ (0 if scenario == "small" else 1))

    jarsrc_root = gen_jarsrc(os.path.join(out_dir, "jarsrc"), sizes["jars"], random.Random(rng.randint(0, 2**31)))
    logsum_path = os.path.join(out_dir, "logsum.log")
    gen_logsum(logsum_path, sizes["log_lines"], random.Random(rng.randint(0, 2**31)))
    treesum_root = gen_treesum(os.path.join(out_dir, "treesum"), sizes["tree_files"], random.Random(rng.randint(0, 2**31)))
    jsonpick_path = os.path.join(out_dir, "jsonpick.json")
    gen_jsonpick(jsonpick_path, sizes["json_items"], random.Random(rng.randint(0, 2**31)))

    checksums = {
        "logsum.log": sha256_file(logsum_path),
        "jsonpick.json": sha256_file(jsonpick_path),
        "jarsrc_tree": sha256_tree_manifest(jarsrc_root),
        "treesum_tree": sha256_tree_manifest(treesum_root),
    }
    with open(os.path.join(out_dir, "CHECKSUMS.txt"), "w", newline="\n") as f:
        for k, v in checksums.items():
            f.write(f"{v}  {k}\n")

    print(f"generated {scenario} fixtures in {out_dir}")
    for k, v in checksums.items():
        print(f"  {k}: {v}")


if __name__ == "__main__":
    main()
