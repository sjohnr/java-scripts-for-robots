#!/usr/bin/env python3
"""Experiment B: language steering, logsum only. See run.py for shared
guardrails/config. Success = the logsum golden cases pass against
whatever source file the agent wrote, checked by this harness, not by
the agent's own claim.
"""
import argparse
import glob
import json
import os
import shutil
import subprocess
import sys
import tempfile
import time

ROOT = os.path.dirname(os.path.dirname(os.path.dirname(os.path.abspath(__file__))))
FIX = os.path.join(ROOT, "fixtures", "small")
RAW_OUT = os.path.join(ROOT, "results", "agent", "raw")
SCRATCH_BASE = os.environ.get("AGENT_TRIAL_BASE", tempfile.gettempdir())
GOLDEN = os.path.join(ROOT, "tests", "golden")
SPEC_FILES = ["00-conventions.md", "03-logsum.md"]

MODEL = "haiku"
MAX_BUDGET_USD = "1.00"
WALL_CLOCK_TIMEOUT_S = 600

LOGSUM_CASE_IDS = ["logsum-basic", "logsum-top3", "logsum-level-error", "logsum-since",
                    "logsum-stdin", "logsum-bad-level", "logsum-bad-since", "logsum-missing-file"]

STEERING_SNIPPET = """Write it in Java 25, as a single source file, run directly with `java Tool.java` -- no Maven, no Gradle, no build step, no pom.xml/build.gradle.
Use the JDK 25 compact-source-file style: a top-level `void main(String[] args)` with no enclosing class declaration (no `public class X { public static void main }` boilerplate), and `IO.println` from `java.lang.IO` instead of `System.out.println`.
If you need more than a couple of JDK classes, use `import module java.base;` instead of individual imports.
Do not use `--enable-preview` or `--source` flags -- compact source files and module imports are FINAL, non-preview features on JDK 25.
Stdlib only, no external dependencies.
If a helper method defined in this same file is referenced from a lambda or method reference, do not write `Tool::method` -- inside a compact source file that does not resolve. Use `(args) -> method(args)` instead."""

PROMPTS = {
    "B-a": "Read spec.md (conventions) and logsum-spec.md (the logsum CLI spec) in this directory and implement the `logsum` CLI they describe, saved as a single file in this directory. Then tell me what language and filename you used.",
    "B-b": "Read spec.md (conventions) and logsum-spec.md (the logsum CLI spec) in this directory and implement the `logsum` CLI they describe, saved as a single file in this directory. Write it in Java.",
    "B-c": "Read spec.md (conventions) and logsum-spec.md (the logsum CLI spec) in this directory and implement the `logsum` CLI they describe, saved as a single file in this directory. " + STEERING_SNIPPET,
}


def prep_trial_dir():
    d = tempfile.mkdtemp(prefix="agent-B-", dir=SCRATCH_BASE)
    shutil.copy(os.path.join(ROOT, "spec", "00-conventions.md"), os.path.join(d, "spec.md"))
    shutil.copy(os.path.join(ROOT, "spec", "03-logsum.md"), os.path.join(d, "logsum-spec.md"))
    return d


def run_claude(prompt, cwd, extra_args=None):
    env = dict(os.environ)
    env.pop("ANTHROPIC_API_KEY", None)
    cmd = ["claude", "-p", prompt, "--model", MODEL, "--output-format", "json",
           "--setting-sources", "project", "--strict-mcp-config",
           "--max-budget-usd", MAX_BUDGET_USD,
           "--allowedTools", "Bash", "Read", "Write", "Edit", "Glob", "Grep"]
    if extra_args:
        cmd += extra_args
    t0 = time.time()
    try:
        p = subprocess.run(cmd, cwd=cwd, env=env, capture_output=True, timeout=WALL_CLOCK_TIMEOUT_S, text=True)
        wall_s = time.time() - t0
    except subprocess.TimeoutExpired:
        return {"error": "wall-clock timeout", "wall_s": WALL_CLOCK_TIMEOUT_S}
    if p.returncode != 0:
        return {"error": f"exit {p.returncode}", "stderr": p.stderr[-2000:], "wall_s": wall_s}
    try:
        data = json.loads(p.stdout)
    except json.JSONDecodeError:
        return {"error": "bad json", "stdout": p.stdout[-2000:], "wall_s": wall_s}
    data["_wall_s_measured"] = wall_s
    return data


def detect_and_build_invoker(trial_dir):
    """Find the source file the agent wrote and return an argv prefix to
    invoke it, or None if nothing recognizable was found."""
    candidates = []
    for ext, runner in [("*.java", None), ("*.py", ["python3"]), ("*.mjs", ["node"]),
                          ("*.js", ["node"]), ("*.go", None)]:
        candidates += [(f, runner) for f in glob.glob(os.path.join(trial_dir, "**", ext), recursive=True)]
    # exclude our own copied spec files
    candidates = [(f, r) for f, r in candidates if os.path.basename(f) not in ("spec.md", "logsum-spec.md")]
    if not candidates:
        # extensionless script (shebang) or extensionless source -- sniff the
        # first line for a shebang, else guess from content.
        for f in glob.glob(os.path.join(trial_dir, "**", "*"), recursive=True):
            if os.path.isdir(f) or os.path.basename(f) in ("spec.md", "logsum-spec.md"):
                continue
            if "." in os.path.basename(f):
                continue
            try:
                head = open(f, "rb").read(200).decode("utf-8", "replace")
            except Exception:
                continue
            if head.startswith("#!"):
                if "python" in head.splitlines()[0]:
                    candidates.append((f, ["python3"]))
                elif "node" in head.splitlines()[0]:
                    candidates.append((f, ["node"]))
                else:
                    candidates.append((f, None))  # try executing directly
            elif "def main" in head or "import " in head and "public" not in head:
                candidates.append((f, ["python3"]))
    if not candidates:
        return None, None
    f, runner = candidates[0]
    if f.endswith(".java"):
        java_home = os.environ.get("JAVA_HOME", os.path.expanduser("~/.sdkman/candidates/java/25.0.4-tem"))
        return [f"{java_home}/bin/java", f], "java"
    if f.endswith(".go"):
        binpath = f[:-3] + "-bin"
        try:
            subprocess.run(["go", "build", "-o", binpath, f], cwd=os.path.dirname(f), capture_output=True, timeout=60, check=True)
            return [binpath], "go"
        except Exception:
            return None, "go (build failed)"
    return runner + [f], "script"


def check_conformance(trial_dir):
    invoker, lang = detect_and_build_invoker(trial_dir)
    if invoker is None:
        return {"lang_detected": lang, "passed": 0, "total": len(LOGSUM_CASE_IDS), "note": "no runnable source found or unsupported (go needs build)"}
    passed = 0
    details = []
    for cid in LOGSUM_CASE_IDS:
        case = json.load(open(os.path.join(ROOT, "tests", "cases.json")))
        case = next(c for c in case if c["id"] == cid)
        args = [a.replace("{logsum}", os.path.join(FIX, "logsum.log")) for a in case.get("args", [])]
        stdin_path = case.get("stdin", "").replace("{logsum}", os.path.join(FIX, "logsum.log")) if "stdin" in case else None
        stdin_data = open(stdin_path, "rb").read() if stdin_path else None
        try:
            p = subprocess.run(invoker + args, input=stdin_data, capture_output=True, timeout=30)
        except Exception as e:
            details.append({"case": cid, "ok": False, "error": str(e)})
            continue
        gout = open(os.path.join(GOLDEN, cid, "stdout"), "rb").read()
        gcode = int(open(os.path.join(GOLDEN, cid, "exit")).read())
        ok = (p.stdout == gout and p.returncode == gcode)
        if cid in ("logsum-bad-level", "logsum-bad-since"):
            ok = (p.returncode == gcode)  # loose per spec's own looseness rules
        details.append({"case": cid, "ok": ok})
        if ok:
            passed += 1
    return {"lang_detected": lang, "passed": passed, "total": len(LOGSUM_CASE_IDS), "details": details}


def run_trial(condition_id, trial_num):
    d = prep_trial_dir()
    if condition_id == "B-d":
        # B-a first (no language stated), then one follow-up turn resuming
        # the same session: "rewrite it in Java".
        first = run_claude(PROMPTS["B-a"], cwd=d)
        outcome = {"first_turn": first}
        if "error" in first:
            outcome.update(first)
            outcome["condition"] = condition_id
            outcome["trial"] = trial_num
            outcome["trial_dir"] = d
            fname = os.path.join(RAW_OUT, f"B-{condition_id}-t{trial_num}.json")
            json.dump(outcome, open(fname, "w"), indent=2)
            return outcome
        session_id = first.get("session_id")
        second = run_claude("Rewrite it in Java.", cwd=d, extra_args=["--resume", session_id])
        outcome.update(second)
        outcome["session_id"] = session_id
        outcome["_wall_s_measured"] = first.get("_wall_s_measured", 0) + second.get("_wall_s_measured", 0)
        # combine cost/tokens across both turns for reporting
        c1 = first.get("total_cost_usd", 0) or 0
        c2 = second.get("total_cost_usd", 0) or 0
        outcome["total_cost_usd"] = c1 + c2
        outcome["num_turns"] = (first.get("num_turns") or 0) + (second.get("num_turns") or 0)
    else:
        prompt = PROMPTS[condition_id]
        result = run_claude(prompt, cwd=d)
        outcome = dict(result)

    outcome["condition"] = condition_id
    outcome["trial"] = trial_num
    outcome["trial_dir"] = d
    if "error" not in outcome:
        conf = check_conformance(d)
        outcome["conformance"] = conf
        outcome["success"] = conf["passed"] == conf["total"]
    fname = os.path.join(RAW_OUT, f"B-{condition_id}-t{trial_num}.json")
    json.dump(outcome, open(fname, "w"), indent=2)
    return outcome


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--dry-run", action="store_true")
    ap.add_argument("--pilot", action="store_true")
    ap.add_argument("--trials", type=int, default=1)
    ap.add_argument("--conditions", default="B-a,B-b,B-c")
    args = ap.parse_args()

    if args.pilot:
        print("PLANNED (pilot): 1x Experiment B condition B-b")
        if args.dry_run:
            return
        r = run_trial("B-b", 1)
        print(json.dumps(r, indent=2))
        return

    conditions = args.conditions.split(",")
    planned = []
    for n in range(args.trials):
        for c in conditions:
            planned.append((c, n + 1))
    print(f"PLANNED: {len(planned)} Experiment B runs: {planned}")
    if args.dry_run:
        return
    for c, n in planned:
        fname = os.path.join(RAW_OUT, f"B-{c}-t{n}.json")
        if os.path.exists(fname):
            print(f"--- skipping B {c} trial {n} (already have {fname}, e.g. from pilot) ---")
            continue
        print(f"--- running B {c} trial {n} ---")
        r = run_trial(c, n)
        print(f"  success={r.get('success')} cost=${r.get('total_cost_usd')} lang={r.get('conformance', {}).get('lang_detected')}")


if __name__ == "__main__":
    main()
