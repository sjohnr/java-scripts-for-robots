#!/usr/bin/env python3
"""Conformance runner: executes every implementation of every CLI over
tests/cases.json, diffing stdout/stderr/exit code across all of them.

Usage: python3 tests/run_conformance.py [--write-golden] [--verbose]

Without --write-golden: compares every implementation's output to the
golden files under tests/golden/<case-id>/{stdout,stderr,exit}. Fails
(nonzero exit, printed diff) on any mismatch.

With --write-golden: (re)writes golden files from the "reference"
implementation for each CLI (see REFERENCE below) -- use only after
manually confirming the reference implementation matches spec/.
"""
import json
import os
import subprocess
import sys

ROOT = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
FIX = os.path.join(ROOT, "fixtures", "small")
GOLDEN = os.path.join(ROOT, "tests", "golden")

# Reference implementation per CLI: whichever is used to generate golden
# files. Chosen as the implementation most directly checked against
# spec/ during development.
REFERENCE = {
    "noop": "java-classic",
    "jarsrc": "java-classic",
    "logsum": "java-classic",
    "treesum": "java-classic",
    "jsonpick": "java-classic",
}

JAVA_HOME = os.environ.get("JAVA_HOME", os.path.expanduser("~/.sdkman/candidates/java/25.0.4-tem"))

# Cases where spec/00-conventions.md explicitly says wording is NOT
# byte-compared across languages: --help text (only flags must be
# present, not checked here beyond exit code) and the one-line JSON
# parse-error reason (must exist and name a location, wording free).
LOOSE_CASES = {
    "noop-help", "jarsrc-help", "logsum-help", "treesum-help", "jsonpick-help",
    "jsonpick-bad-json",
}


def impls_for(cli):
    out = {}
    out["go"] = [os.path.join(ROOT, "tools", cli, "go", cli)]
    out["python"] = ["python3", os.path.join(ROOT, "tools", cli, "python", "tool.py")]
    out["node"] = ["node", os.path.join(ROOT, "tools", cli, "node", "tool.mjs")]
    classes = os.path.join(ROOT, "tools", cli, "java", "build", "classes")
    out["java-classic"] = [os.path.join(JAVA_HOME, "bin", "java"), "-cp", classes, "Tool"]
    jar = os.path.join(ROOT, "tools", cli, "java", "build", f"{cli}.jar")
    if os.path.exists(jar):
        out["java-jar"] = [os.path.join(JAVA_HOME, "bin", "java"), "-jar", jar]
    if cli in ("noop", "jarsrc"):
        compact_src = os.path.join(ROOT, "tools", cli, "java", "compact", "Tool.java")
        if os.path.exists(compact_src):
            out["java-compact"] = [os.path.join(JAVA_HOME, "bin", "java"), compact_src]
    if cli == "jarsrc":
        multi_src = os.path.join(ROOT, "tools", cli, "java", "multi", "Tool.java")
        if os.path.exists(multi_src):
            out["java-multi"] = [os.path.join(JAVA_HOME, "bin", "java"), multi_src]
    return out


def substitute(arg):
    return (arg.replace("{jarsrc}", os.path.join(FIX, "jarsrc"))
               .replace("{logsum}", os.path.join(FIX, "logsum.log"))
               .replace("{treesum}", os.path.join(FIX, "treesum"))
               .replace("{jsonpick}", os.path.join(FIX, "jsonpick.json"))
               .replace("{badjson}", os.path.join(FIX, "badjson.json")))


def run(cmd, args, stdin_path):
    stdin_data = None
    if stdin_path:
        with open(stdin_path, "rb") as f:
            stdin_data = f.read()
    try:
        p = subprocess.run(cmd + args, input=stdin_data, capture_output=True, timeout=30)
    except FileNotFoundError as e:
        return None, f"EXEC ERROR: {e}".encode(), 127
    return p.stdout, p.stderr, p.returncode


def main():
    write_golden = "--write-golden" in sys.argv
    verbose = "--verbose" in sys.argv
    cases = json.load(open(os.path.join(ROOT, "tests", "cases.json")))

    fails = 0
    total_checks = 0

    for case in cases:
        cid = case["id"]
        cli = case["cli"]
        args = [substitute(a) for a in case.get("args", [])]
        stdin_path = substitute(case["stdin"]) if "stdin" in case else None

        impls = impls_for(cli)
        ref_name = REFERENCE[cli]
        if ref_name not in impls:
            print(f"SKIP {cid}: reference impl {ref_name} not available")
            continue
        ref_cmd = impls[ref_name]
        ref_out, ref_err, ref_code = run(ref_cmd, args, stdin_path)

        gdir = os.path.join(GOLDEN, cid)
        if write_golden:
            os.makedirs(gdir, exist_ok=True)
            open(os.path.join(gdir, "stdout"), "wb").write(ref_out or b"")
            open(os.path.join(gdir, "stderr"), "wb").write(ref_err or b"")
            open(os.path.join(gdir, "exit"), "w").write(str(ref_code))
            if verbose:
                print(f"WROTE golden for {cid} (from {ref_name}): exit={ref_code}")
            continue

        gout = open(os.path.join(gdir, "stdout"), "rb").read()
        gerr = open(os.path.join(gdir, "stderr"), "rb").read()
        gcode = int(open(os.path.join(gdir, "exit")).read())

        loose = cid in LOOSE_CASES
        for name, cmd in impls.items():
            total_checks += 1
            out, err, code = run(cmd, args, stdin_path)
            if loose:
                ok = (code == gcode)
            else:
                ok = (out == gout and err == gerr and code == gcode)
            if not ok:
                fails += 1
                print(f"FAIL {cid} [{name}]")
                if out != gout:
                    print(f"  stdout mismatch:\n    got: {out!r}\n    want: {gout!r}")
                if err != gerr:
                    print(f"  stderr mismatch:\n    got: {err!r}\n    want: {gerr!r}")
                if code != gcode:
                    print(f"  exit code mismatch: got {code} want {gcode}")
            elif verbose:
                print(f"ok   {cid} [{name}]")

    if write_golden:
        print("golden files written")
        return 0

    print(f"\n{total_checks - fails}/{total_checks} checks passed across {len(cases)} cases")
    return 1 if fails else 0


if __name__ == "__main__":
    sys.exit(main())
