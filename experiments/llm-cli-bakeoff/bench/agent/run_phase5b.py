#!/usr/bin/env python3
"""Phase 5b: Sonnet instruction-following pilot (Java), logsum + jsonpick.

Minimal extension of run_experiment_b.py (Haiku pilot harness): same
guardrails (sequential, fresh temp dir, --setting-sources project,
--strict-mcp-config, --max-budget-usd, subscription auth, ANTHROPIC_API_KEY
unset), reused conformance-checking approach, extended to:
  - a configurable model (default sonnet, was hardcoded haiku)
  - a second tool (jsonpick) for condition B-c
  - a new condition B-e (conservative snippet: classic main, no compact
    source, no module imports)
  - stream-json output capture so the transcript is available for the
    instruction-compliance checker (R5 preview flags in commands run, R9
    self-testing before finishing) -- the old --output-format json only
    returns the final summary, not tool calls.

Usage:
  python3 bench/agent/run_phase5b.py --dry-run [--model sonnet]
  python3 bench/agent/run_phase5b.py --pilot-gate     # exactly 1 run: B-c logsum trial 1
  python3 bench/agent/run_phase5b.py --plan default   # the full 16-run matrix (skips existing files)
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
RAW_OUT = os.path.join(ROOT, "results", "agent-sonnet", "raw")
SCRATCH_BASE = os.environ.get("AGENT_TRIAL_BASE", tempfile.gettempdir())
GOLDEN = os.path.join(ROOT, "tests", "golden")
JAVA_HOME = os.environ.get("JAVA_HOME", os.path.expanduser("~/.sdkman/candidates/java/25.0.4-tem"))

MODEL = "sonnet"
MAX_BUDGET_USD = "1.00"
WALL_CLOCK_TIMEOUT_S = 600

os.makedirs(RAW_OUT, exist_ok=True)

LOGSUM_CASE_IDS = ["logsum-basic", "logsum-top3", "logsum-level-error", "logsum-since",
                    "logsum-stdin", "logsum-bad-level", "logsum-bad-since", "logsum-missing-file"]

ALL_CASES = json.load(open(os.path.join(ROOT, "tests", "cases.json")))
JSONPICK_CASE_IDS = [c["id"] for c in ALL_CASES if c["cli"] == "jsonpick"]
JSONPICK_LOOSE = {"jsonpick-help", "jsonpick-bad-json"}

# Exactly the STEERING_SNIPPET constant from bench/agent/run_experiment_b.py,
# used verbatim (not the paraphrased fenced block in
# steering/AGENTS-java-scripts.md) -- this is the literal text that was sent
# to the model in the Haiku B-c trials. Do not edit.
STEERING_SNIPPET = """Write it in Java 25, as a single source file, run directly with `java Tool.java` -- no Maven, no Gradle, no build step, no pom.xml/build.gradle.
Use the JDK 25 compact-source-file style: a top-level `void main(String[] args)` with no enclosing class declaration (no `public class X { public static void main }` boilerplate), and `IO.println` from `java.lang.IO` instead of `System.out.println`.
If you need more than a couple of JDK classes, use `import module java.base;` instead of individual imports.
Do not use `--enable-preview` or `--source` flags -- compact source files and module imports are FINAL, non-preview features on JDK 25.
Stdlib only, no external dependencies.
If a helper method defined in this same file is referenced from a lambda or method reference, do not write `Tool::method` -- inside a compact source file that does not resolve. Use `(args) -> method(args)` instead."""

CONSERVATIVE_SNIPPET = """Write it in Java, targeting JDK 25, as a single file named Tool.java, run with `java Tool.java`.
Use the classic style only: `public class Tool { public static void main(String[] args) { ... } }` with a normal `public static void main(String[] args)` entry point.
Do NOT use the JDK 25 compact-source-file style: no top-level `void main`, no instance main, no `import module java.base;`, no `IO.println`/`IO.readln`. Use ordinary imports (e.g. `import java.util.*;`) and `System.out`/`System.err` as usual.
No build files (no pom.xml, build.gradle, settings.gradle). Stdlib only, no external dependencies.
Do not pass `--enable-preview`, `--source`, or any preview flag when compiling or running."""

TOOL_SPEC_FILE = {"logsum": "03-logsum.md", "jsonpick": "05-jsonpick.md"}
TOOL_SPEC_COPY_NAME = {"logsum": "logsum-spec.md", "jsonpick": "jsonpick-spec.md"}

BASE_INSTR = {
    "logsum": "Read spec.md (conventions) and logsum-spec.md (the logsum CLI spec) in this directory and implement the `logsum` CLI they describe, saved as a single file in this directory.",
    "jsonpick": "Read spec.md (conventions) and jsonpick-spec.md (the jsonpick CLI spec) in this directory and implement the `jsonpick` CLI they describe, saved as a single file in this directory.",
}

# condition_id -> suffix appended to BASE_INSTR[tool] (None = no language stated)
CONDITION_SUFFIX = {
    "B-a": "",
    "B-b": " Write it in Java.",
    "B-c": " " + STEERING_SNIPPET,
    "B-e": " " + CONSERVATIVE_SNIPPET,
}


def prep_trial_dir(tool):
    d = tempfile.mkdtemp(prefix=f"agent5b-{tool}-", dir=SCRATCH_BASE)
    shutil.copy(os.path.join(ROOT, "spec", "00-conventions.md"), os.path.join(d, "spec.md"))
    shutil.copy(os.path.join(ROOT, "spec", TOOL_SPEC_FILE[tool]), os.path.join(d, TOOL_SPEC_COPY_NAME[tool]))
    return d


def run_claude(prompt, cwd, extra_args=None):
    """Run headless via --output-format stream-json so the full transcript
    (all tool calls) is captured, not just the final summary. Returns a dict
    shaped like the old --output-format json result (same top-level keys:
    total_cost_usd, num_turns, usage, result, session_id, modelUsage, ...)
    plus "_events": the full list of stream-json events for the compliance
    checker."""
    env = dict(os.environ)
    env.pop("ANTHROPIC_API_KEY", None)
    cmd = ["claude", "-p", prompt, "--model", MODEL, "--output-format", "stream-json",
           "--verbose",
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
    events = []
    for line in p.stdout.splitlines():
        line = line.strip()
        if not line:
            continue
        try:
            events.append(json.loads(line))
        except json.JSONDecodeError:
            continue
    result_event = next((e for e in reversed(events) if e.get("type") == "result"), None)
    if p.returncode != 0 and result_event is None:
        return {"error": f"exit {p.returncode}", "stderr": p.stderr[-2000:], "wall_s": wall_s, "_events": events}
    if result_event is None:
        return {"error": "no result event", "stdout": p.stdout[-2000:], "stderr": p.stderr[-2000:], "wall_s": wall_s, "_events": events}
    data = dict(result_event)
    data["_events"] = events
    data["_wall_s_measured"] = wall_s
    return data


def bash_commands_from_events(events):
    cmds = []
    for e in events:
        if e.get("type") != "assistant":
            continue
        msg = e.get("message", {})
        for block in msg.get("content", []) or []:
            if block.get("type") == "tool_use" and block.get("name") == "Bash":
                cmd = (block.get("input") or {}).get("command")
                if cmd:
                    cmds.append(cmd)
    return cmds


def detect_source_file(trial_dir, exclude_names):
    """Best-effort: prefer .java (the language this pilot cares about), else
    any other recognizable source extension, else an extensionless shebang
    script. Returns the file path found -- language/invoker is resolved
    separately in detect_invoker so an unsteered (B-a) run in Python/Node/etc
    can still be conformance-tested, not just Java runs."""
    for ext in ("*.java", "*.py", "*.mjs", "*.js", "*.go"):
        candidates = glob.glob(os.path.join(trial_dir, "**", ext), recursive=True)
        candidates = [f for f in candidates if os.path.basename(f) not in exclude_names]
        if candidates:
            return candidates[0]
    for f in glob.glob(os.path.join(trial_dir, "**", "*"), recursive=True):
        if os.path.isdir(f) or os.path.basename(f) in exclude_names or "." in os.path.basename(f):
            continue
        try:
            head = open(f, "rb").read(200).decode("utf-8", "replace")
        except Exception:
            continue
        if head.startswith("#!"):
            return f
    return None


def detect_invoker(source_file):
    """Returns (argv_prefix, lang_label) for the given source file, or
    (None, label) if it can't be executed as-is."""
    if source_file is None:
        return None, "none"
    if source_file.endswith(".java"):
        return [f"{JAVA_HOME}/bin/java", source_file], "java"
    if source_file.endswith(".py"):
        return ["python3", source_file], "python"
    if source_file.endswith((".mjs", ".js")):
        return ["node", source_file], "node"
    if source_file.endswith(".go"):
        binpath = source_file[:-3] + "-bin"
        try:
            subprocess.run(["go", "build", "-o", binpath, source_file],
                            cwd=os.path.dirname(source_file), capture_output=True, timeout=60, check=True)
            return [binpath], "go"
        except Exception:
            return None, "go (build failed)"
    # extensionless: sniff shebang
    try:
        head = open(source_file, "rb").read(200).decode("utf-8", "replace")
    except Exception:
        head = ""
    if head.startswith("#!"):
        shebang = head.splitlines()[0]
        if "python" in shebang:
            return ["python3", source_file], "python (shebang)"
        if "node" in shebang:
            return ["node", source_file], "node (shebang)"
        if os.access(source_file, os.X_OK):
            return [source_file], "shebang (direct exec)"
    return None, "unrecognized"


def check_conformance(source_file, tool):
    case_ids = LOGSUM_CASE_IDS if tool == "logsum" else JSONPICK_CASE_IDS
    invoker, lang = detect_invoker(source_file)
    if invoker is None:
        return {"lang_detected": lang, "passed": 0, "total": len(case_ids),
                "note": "no runnable source found"}
    passed = 0
    details = []
    for cid in case_ids:
        case = next(c for c in ALL_CASES if c["id"] == cid)
        fixture_key = "{logsum}" if tool == "logsum" else "{jsonpick}"
        fixture_path = os.path.join(FIX, "logsum.log") if tool == "logsum" else os.path.join(FIX, "jsonpick.json")
        args = [a.replace(fixture_key, fixture_path).replace("{badjson}", os.path.join(FIX, "badjson.json"))
                for a in case.get("args", [])]
        stdin_path = case.get("stdin", "").replace(fixture_key, fixture_path) if "stdin" in case else None
        stdin_data = open(stdin_path, "rb").read() if stdin_path else None
        try:
            p = subprocess.run(invoker + args, input=stdin_data, capture_output=True, timeout=30)
        except Exception as e:
            details.append({"case": cid, "ok": False, "error": str(e)})
            continue
        gout = open(os.path.join(GOLDEN, cid, "stdout"), "rb").read()
        gcode = int(open(os.path.join(GOLDEN, cid, "exit")).read())
        loose = cid in ("logsum-bad-level", "logsum-bad-since") or cid in JSONPICK_LOOSE
        ok = (p.returncode == gcode) if loose else (p.stdout == gout and p.returncode == gcode)
        details.append({"case": cid, "ok": ok})
        if ok:
            passed += 1
    return {"lang_detected": lang, "passed": passed, "total": len(case_ids), "details": details}


def run_trial(tool, condition_id, trial_num):
    d = prep_trial_dir(tool)
    prompt = BASE_INSTR[tool] + CONDITION_SUFFIX[condition_id]
    result = run_claude(prompt, cwd=d)
    outcome = dict(result)
    outcome["tool"] = tool
    outcome["condition"] = condition_id
    outcome["trial"] = trial_num
    outcome["trial_dir"] = d
    outcome["model_requested"] = MODEL
    if "error" not in outcome:
        exclude = {"spec.md", TOOL_SPEC_COPY_NAME[tool]}
        src = detect_source_file(d, exclude)
        outcome["source_file"] = src
        conf = check_conformance(src, tool)
        outcome["conformance"] = conf
        outcome["success"] = conf["passed"] == conf["total"]
    fname = os.path.join(RAW_OUT, f"B-{tool}-{condition_id}-t{trial_num}.json")
    json.dump(outcome, open(fname, "w"), indent=2)
    return outcome


PLAN_DEFAULT = (
    [("logsum", "B-a", n) for n in (1, 2)] +
    [("logsum", "B-b", n) for n in (1, 2, 3)] +
    [("logsum", "B-c", n) for n in (1, 2, 3)] +
    [("logsum", "B-e", n) for n in (1, 2, 3)] +
    [("jsonpick", "B-c", n) for n in (1, 2)]
)


def main():
    global MODEL
    ap = argparse.ArgumentParser()
    ap.add_argument("--dry-run", action="store_true")
    ap.add_argument("--pilot-gate", action="store_true", help="exactly 1 run: logsum B-c trial 1")
    ap.add_argument("--plan", choices=["default"], help="run the full matrix (skips existing output files)")
    ap.add_argument("--model", default=MODEL)
    args = ap.parse_args()
    MODEL = args.model

    if args.pilot_gate:
        print("PLANNED (pilot gate): 1x logsum B-c trial 1")
        if args.dry_run:
            return
        r = run_trial("logsum", "B-c", 1)
        print(json.dumps({k: v for k, v in r.items() if k != "_events"}, indent=2))
        return

    if args.plan == "default":
        print(f"PLANNED: {len(PLAN_DEFAULT)} runs: {PLAN_DEFAULT}")
        if args.dry_run:
            return
        for tool, c, n in PLAN_DEFAULT:
            fname = os.path.join(RAW_OUT, f"B-{tool}-{c}-t{n}.json")
            if os.path.exists(fname):
                print(f"--- skipping {tool} {c} trial {n} (already have {fname}) ---")
                continue
            print(f"--- running {tool} {c} trial {n} ---")
            r = run_trial(tool, c, n)
            print(f"  success={r.get('success')} cost=${r.get('total_cost_usd')} turns={r.get('num_turns')}")
        return

    ap.print_help()


if __name__ == "__main__":
    main()
