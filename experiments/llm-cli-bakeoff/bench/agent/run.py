#!/usr/bin/env python3
"""Phase 5 (replacement, pilot): headless Claude Code agent trials.

Usage:
  python3 bench/agent/run.py --dry-run [--trials N] [--model M] [--tasks A1,A2,A3,B]
  python3 bench/agent/run.py --pilot            # exactly the 2-run pilot (A1-C2, B-b)
  python3 bench/agent/run.py --experiment A --trials 2
  python3 bench/agent/run.py --experiment B --trials 3

Guardrails (see prompts/02-phase-5-replacement.md): sequential only,
fresh temp dir per trial, --setting-sources project (no user/global
CLAUDE.md or memory), --strict-mcp-config (no inherited MCP servers),
--max-budget-usd per run, subscription auth (ANTHROPIC_API_KEY
unset for every trial run).
"""
import argparse
import json
import os
import shutil
import subprocess
import sys
import tempfile
import time

ROOT = os.path.dirname(os.path.dirname(os.path.dirname(os.path.abspath(__file__))))
FIX = os.path.join(ROOT, "fixtures", "small")
BIN = os.path.join(ROOT, "bench", "agent", "bin")
RAW_OUT = os.path.join(ROOT, "results", "agent", "raw")
SCRATCH_BASE = os.environ.get("AGENT_TRIAL_BASE", tempfile.gettempdir())

MODEL = "haiku"
MAX_BUDGET_USD = "0.75"
WALL_CLOCK_TIMEOUT_S = 600

GROUND_TRUTH = {
    "A1": {
        "prompt": "Using the local Gradle-style sources-jar cache rooted at ./jarsrc, show the signature and Javadoc (if any) of method0 on class com.example.core.alpha.Main.",
        "check_substrings": ["method0", "int x"],
    },
    "A2": {
        "prompt": "Summarize the errors in ./app.log: give the top 3 distinct error messages by frequency and the overall time range of the log (first and last timestamp).",
        "check_substrings": ["checksum mismatch", "null pointer", "failed to connect",
                              "2026-01-01T00:00:03", "2026-01-01T00:26:11"],
    },
    "A3": {
        "prompt": "In the ./repo directory, where is the symbol VersionCompare defined (which file), and which file in ./repo has the most lines?",
        "check_substrings": ["VersionCompare.java", "main.go"],
    },
}

CLAUDE_MD_SNIPPETS = {
    "A1": "This directory has a `jarsrc` tool on PATH. Usage: `jarsrc cat <fully.qualified.ClassName> --root <dir>` prints the source of a class from a local Gradle-style sources-jar cache. Run `jarsrc --help` for full usage.",
    "A2": "This directory has a `logsum` tool on PATH. Usage: `logsum <file> [--top N]` summarizes a log file: counts by level, first/last timestamp, and the top N most common error messages. Run `logsum --help` for full usage.",
    "A3": "This directory has a `treesum` tool on PATH. Usage: `treesum <dir>` gives file counts/LOC by extension and lists the largest files in a directory tree. Run `treesum --help` for full usage.",
}

TASK_TOOL = {"A1": "jarsrc", "A2": "logsum", "A3": "treesum"}


def prep_trial_dir(task_id):
    d = tempfile.mkdtemp(prefix=f"agent-{task_id}-", dir=SCRATCH_BASE)
    if task_id == "A1":
        shutil.copytree(os.path.join(FIX, "jarsrc"), os.path.join(d, "jarsrc"))
    elif task_id == "A2":
        shutil.copy(os.path.join(FIX, "logsum.log"), os.path.join(d, "app.log"))
    elif task_id == "A3":
        src = os.path.join(SCRATCH_BASE, "agent-pilot", "a3-repo")
        shutil.copytree(src, os.path.join(d, "repo"))
    return d


def run_claude(prompt, cwd, path_env, setting_sources="project", extra_env=None):
    env = dict(os.environ)
    env.pop("ANTHROPIC_API_KEY", None)
    env["PATH"] = path_env
    if extra_env:
        env.update(extra_env)
    cmd = [
        "claude", "-p", prompt,
        "--model", MODEL,
        "--output-format", "json",
        "--setting-sources", setting_sources,
        "--strict-mcp-config",
        "--max-budget-usd", MAX_BUDGET_USD,
        "--allowedTools", "Bash", "Read", "Write", "Edit", "Glob", "Grep",
    ]
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
        return {"error": "bad json", "stdout": p.stdout[-2000:], "stderr": p.stderr[-2000:], "wall_s": wall_s}
    data["_wall_s_measured"] = wall_s
    return data


def run_experiment_a_trial(task_id, condition_id, trial_num, out_dir):
    gt = GROUND_TRUTH[task_id]
    tool = TASK_TOOL[task_id]
    d = prep_trial_dir(task_id)
    base_path = os.path.expanduser("~/.local/bin") + ":/usr/bin:/bin:/usr/local/bin"
    node_path = os.path.dirname(shutil.which("node") or "/usr/local/bin")
    java_path = os.path.join(os.environ.get("JAVA_HOME", os.path.expanduser("~/.sdkman/candidates/java/25.0.4-tem")), "bin")
    py_path = os.path.dirname(shutil.which("python3") or "/usr/local/bin")
    go_path = os.path.dirname(shutil.which("go") or "/usr/local/bin")
    common_path = f"{base_path}:{node_path}:{py_path}:{go_path}:{java_path}"

    if condition_id == "C1":
        path_env = common_path
    else:
        path_env = f"{BIN}:{common_path}"

    if condition_id == "C2":
        open(os.path.join(d, "CLAUDE.md"), "w").write(CLAUDE_MD_SNIPPETS[task_id] + "\n")

    result = run_claude(gt["prompt"], cwd=d, path_env=path_env)

    outcome = dict(result)
    outcome["task"] = task_id
    outcome["condition"] = condition_id
    outcome["trial"] = trial_num
    outcome["trial_dir"] = d
    if "error" not in result:
        text = (result.get("result") or "").lower()
        hits = [s for s in gt["check_substrings"] if s.lower() in text]
        outcome["check_hits"] = hits
        outcome["check_total"] = len(gt["check_substrings"])
        outcome["success"] = len(hits) == len(gt["check_substrings"])

    fname = os.path.join(out_dir, f"A-{task_id}-{condition_id}-t{trial_num}.json")
    json.dump(outcome, open(fname, "w"), indent=2)
    return outcome


def main():
    global MODEL
    ap = argparse.ArgumentParser()
    ap.add_argument("--dry-run", action="store_true")
    ap.add_argument("--pilot", action="store_true")
    ap.add_argument("--experiment", choices=["A", "B"])
    ap.add_argument("--trials", type=int, default=1)
    ap.add_argument("--model", default=MODEL)
    ap.add_argument("--tasks", default="A1,A2,A3")
    args = ap.parse_args()
    MODEL = args.model

    if args.pilot:
        planned = [("A1", "C2", 1)]
        print(f"PLANNED (pilot): 1x Experiment A ({planned[0]}), 1x Experiment B (B-b) -- see run_experiment_b.py")
        if args.dry_run:
            return
        r = run_experiment_a_trial("A1", "C2", 1, RAW_OUT)
        print(json.dumps(r, indent=2))
        return

    if args.experiment == "A":
        tasks = args.tasks.split(",")
        conditions = ["C1", "C2", "C3"]
        # interleaved: A1-C1,A1-C2,A1-C3,A2-C1,... trial 1 pass, then trial 2 pass
        planned = []
        for n in range(args.trials):
            for t in tasks:
                for c in conditions:
                    planned.append((t, c, n + 1))
        print(f"PLANNED: {len(planned)} Experiment A runs: {planned}")
        if args.dry_run:
            return
        for t, c, n in planned:
            fname = os.path.join(RAW_OUT, f"A-{t}-{c}-t{n}.json")
            if os.path.exists(fname):
                print(f"--- skipping A {t} {c} trial {n} (already have {fname}, e.g. from pilot) ---")
                continue
            print(f"--- running A {t} {c} trial {n} ---")
            r = run_experiment_a_trial(t, c, n, RAW_OUT)
            print(f"  success={r.get('success')} cost=${r.get('total_cost_usd')} turns={r.get('num_turns')}")


if __name__ == "__main__":
    main()
