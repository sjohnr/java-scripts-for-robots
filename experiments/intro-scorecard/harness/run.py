#!/usr/bin/env python3
"""Run the intro-scorecard agent trials.

  python3 harness/run.py --dry-run
  python3 harness/run.py --tasks logsum --runs 1            # calibration (6 runs)
  python3 harness/run.py                                    # everything not yet recorded
  python3 harness/run.py --only B-jsonpick-opus-3
  python3 harness/run.py --conditions B --runs 6 --cap 108  # top-up (needs approval)

Every run is sequential, in a seeded shuffled order, in a fresh directory outside the
repository that contains only SPEC.md. Runs whose meta.json exists are skipped.
Every attempt (including failed and aborted ones) is appended to results/ledger.jsonl
and counts toward the cap.
"""
import argparse
import datetime
import json
import os
import re
import shutil
import signal
import subprocess
import sys
import time

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
import common as C  # noqa: E402

# Directories and files treated as dependency caches or build output: sized, not copied.
EXCLUDE_DIRS = {".git", "node_modules", "build", "target", "out", ".gradle", ".venv", "venv",
                "__pycache__", "dist", ".mypy_cache", ".pytest_cache", ".m2", ".jbang"}
EXCLUDE_SUFFIXES = (".class", ".jar", ".aot", ".jsa", ".pyc", ".o")
KEEP_FILES = {"gradle-wrapper.jar", "maven-wrapper.jar"}
LIMIT_RE = re.compile(r"usage limit|rate limit|limit reached|limit will reset|resets at|"
                      r"out of extra usage|429|overloaded", re.I)


def now():
    return datetime.datetime.now(datetime.timezone.utc).isoformat(timespec="seconds")


def ledger_attempts():
    if not C.LEDGER.exists():
        return []
    return [json.loads(l) for l in C.LEDGER.read_text().splitlines() if l.strip()]


def ledger_append(entry):
    C.LEDGER.parent.mkdir(parents=True, exist_ok=True)
    with C.LEDGER.open("a") as f:
        f.write(json.dumps(entry) + "\n")


def run_claude(args, cwd, env, out_path, err_path, timeout):
    """Run claude in its own process group; kill the group on timeout."""
    t0 = time.time()
    with open(out_path, "w") as out, open(err_path, "w") as err:
        p = subprocess.Popen(["claude"] + args, cwd=cwd, env=env, stdout=out, stderr=err,
                             stdin=subprocess.DEVNULL, start_new_session=True)
        timed_out = False
        try:
            p.wait(timeout=timeout)
        except subprocess.TimeoutExpired:
            timed_out = True
            os.killpg(p.pid, signal.SIGTERM)
            try:
                p.wait(timeout=20)
            except subprocess.TimeoutExpired:
                os.killpg(p.pid, signal.SIGKILL)
                p.wait()
    return p.returncode, timed_out, time.time() - t0


def parse_stream(path):
    events = []
    for line in open(path, errors="replace"):
        line = line.strip()
        if line:
            try:
                events.append(json.loads(line))
            except json.JSONDecodeError:
                pass
    init = next((e for e in events if e.get("type") == "system" and e.get("subtype") == "init"), {})
    result = next((e for e in reversed(events) if e.get("type") == "result"), {})
    return events, init, result


def limit_hits(result, stderr_text, timed_out, events):
    hits = []
    if timed_out:
        hits.append("wall_clock_timeout")
    sub = result.get("subtype", "")
    if "budget" in sub:
        hits.append("max_budget_usd")
    if sub == "error_max_turns":
        hits.append("max_turns")
    text = ""
    if result.get("is_error"):
        text += str(result.get("result", ""))
    text += stderr_text[-4000:]
    for e in events:
        if "rate_limit" in str(e.get("type", "")):
            info = e.get("rate_limit_info") or {}
            if str(info.get("status", "")).lower() not in ("", "allowed", "allowed_warning"):
                text += " rate limit " + json.dumps(info)
        if e.get("type") == "assistant" and e.get("error"):
            text += " " + str(e.get("error"))
    if LIMIT_RE.search(text):
        hits.append("usage_or_rate_limit")
    return hits


def usage_summary(result):
    u = result.get("usage") or {}
    return {
        "input_tokens": u.get("input_tokens"),
        "output_tokens": u.get("output_tokens"),
        "cache_creation_input_tokens": u.get("cache_creation_input_tokens"),
        "cache_read_input_tokens": u.get("cache_read_input_tokens"),
    }


def clean_command(text):
    """The follow-up reply should be a bare command; strip fences/backticks/prompts."""
    if not text:
        return None
    t = text.strip()
    m = re.search(r"```[a-zA-Z]*\n(.*?)```", t, re.S)
    if m:
        t = m.group(1).strip()
    lines = [l.strip() for l in t.splitlines() if l.strip()]
    if not lines:
        return None
    line = lines[0]
    if line.startswith("`") and line.endswith("`"):
        line = line.strip("`")
    if line.startswith("$ "):
        line = line[2:]
    return line


def size_and_copy(project, dest):
    """Record sizes of excluded caches/build output, then copy everything else."""
    excluded = []
    for dp, dns, fns in os.walk(project):
        rel_dp = os.path.relpath(dp, project)
        for d in list(dns):
            if d in EXCLUDE_DIRS:
                p = os.path.join(dp, d)
                excluded.append({"path": os.path.normpath(os.path.join(rel_dp, d)), "bytes": C.du_bytes(p)})
                dns.remove(d)
        for f in fns:
            p = os.path.join(dp, f)
            rel = os.path.normpath(os.path.join(rel_dp, f))
            if os.path.islink(p):
                continue
            if f.endswith(EXCLUDE_SUFFIXES) and f not in KEEP_FILES:
                excluded.append({"path": rel, "bytes": os.path.getsize(p)})
                continue
            if is_binary_executable(p):
                excluded.append({"path": rel, "bytes": os.path.getsize(p), "kind": "binary"})
                continue
            target = os.path.join(dest, rel)
            os.makedirs(os.path.dirname(target), exist_ok=True)
            shutil.copy2(p, target)
    return excluded


def is_binary_executable(p):
    try:
        head = open(p, "rb").read(4)
    except OSError:
        return False
    return head in (b"\xcf\xfa\xed\xfe", b"\xca\xfe\xba\xbe", b"\x7fELF", b"\xfe\xed\xfa\xcf")


def followup(session, model, project, env, raw, gen_cost):
    if not session:
        return {"cost_usd": None, "reply": None, "run_command": None, "limit_hits": []}
    fargs = ["-p", C.FOLLOWUP, "--resume", session, "--model", model] + C.CLAUDE_FLAGS
    frc, fto, fwall = run_claude(fargs, project, env, raw / "followup.jsonl", raw / "followup-stderr.log",
                                 C.FOLLOWUP_TIMEOUT_S)
    fev, finit, fres = parse_stream(raw / "followup.jsonl")
    fhits = limit_hits(fres, (raw / "followup-stderr.log").read_text(errors="replace"), fto, fev)
    return {
        "exit_status": frc, "timed_out": fto, "wall_s": round(fwall, 1),
        # A resumed session reports cumulative cost; the follow-up's own cost is the difference.
        "cost_usd": (None if fres.get("total_cost_usd") is None else
                     fres["total_cost_usd"] - (gen_cost or 0)),
        "cost_usd_cumulative_reported": fres.get("total_cost_usd"), "num_turns": fres.get("num_turns"),
        "tokens": usage_summary(fres), "subtype": fres.get("subtype"),
        "reply": fres.get("result"), "run_command": clean_command(fres.get("result")),
        "limit_hits": fhits,
    }


def redo_followup(rid):
    """Repeat only the follow-up turn (after it hit a limit); the generation is kept."""
    raw = C.RAW / rid
    meta = C.load_json(raw / "meta.json")
    _, project, _ = C.run_dirs(rid)
    for f in ("followup.jsonl", "followup-stderr.log"):
        if (raw / f).exists():
            (raw / f).rename(raw / f"{f}.limit-{int(time.time())}")
    follow = followup(meta["session_id"], meta["model_alias"], project, C.run_env(rid), raw, meta.get("cost_usd"))
    follow["previous_attempts"] = (meta["followup"].get("previous_attempts") or []) + [
        {k: meta["followup"].get(k) for k in ("limit_hits", "reply", "cost_usd")}]
    meta["followup"] = follow
    meta["run_command"] = follow.get("run_command")
    C.write_json(raw / "meta.json", meta)
    return meta


def execute(rid, positions):
    cond, task, model, k = C.parse_run_id(rid)
    raw = C.RAW / rid
    base, project, caches = C.run_dirs(rid)
    started = now()
    attempt = {"run_id": rid, "started": started, "event": "start"}
    ledger_append(attempt)

    # Fresh work directory; keep any earlier aborted attempt for inspection.
    if base.exists():
        base.rename(base.with_name(f"{rid}.aborted-{int(time.time())}"))
    if raw.exists():
        raw.rename(raw.with_name(f"{rid}.aborted-{int(time.time())}"))
    project.mkdir(parents=True)
    C.make_cache_dirs(rid)
    shutil.copy(C.TASKS_DIR / task / "SPEC.md", project / "SPEC.md")
    raw.mkdir(parents=True)

    prompt = C.PROMPTS[cond].format(cli=task)
    (raw / "prompt.txt").write_text(prompt + "\n")
    env = C.run_env(rid)

    args = ["-p", prompt, "--model", model] + C.CLAUDE_FLAGS
    rc, timed_out, wall = run_claude(args, project, env, raw / "transcript.jsonl", raw / "stderr.log",
                                     C.WALL_TIMEOUT_S)
    ended = now()
    events, init, result = parse_stream(raw / "transcript.jsonl")
    stderr_text = (raw / "stderr.log").read_text(errors="replace")
    hits = limit_hits(result, stderr_text, timed_out, events)

    # Follow-up turn in the same session for the run command.
    session = result.get("session_id") or init.get("session_id")
    follow = followup(session, model, project, env, raw, result.get("total_cost_usd"))

    excluded = size_and_copy(project, raw / "files")
    model_usage = result.get("modelUsage") or {}
    meta = {
        "run_id": rid,
        "condition": cond,
        "task": task,
        "model_alias": model,
        "model_id": init.get("model"),
        "model_ids_used": sorted(model_usage.keys()),
        "replicate": k,
        "seed": C.SEED,
        "seed_position": positions.get(rid),
        "started": started,
        "ended": ended,
        "wall_s": round(wall, 1),
        "exit_status": rc,
        "result_subtype": result.get("subtype"),
        "is_error": result.get("is_error"),
        "num_turns": result.get("num_turns"),
        "duration_ms": result.get("duration_ms"),
        "duration_api_ms": result.get("duration_api_ms"),
        "tokens": usage_summary(result),
        "model_usage": model_usage,
        "cost_usd": result.get("total_cost_usd"),
        "limit_hits": hits,
        "api_key_source": init.get("apiKeySource"),
        "claude_code_version": init.get("claude_code_version"),
        "session_id": session,
        "work_dir": str(base),
        "followup": follow,
        "run_command": follow.get("run_command"),
        "excluded_from_copy": excluded,
        "prompt": prompt,
        "claude_args": args,
    }
    C.write_json(raw / "meta.json", meta)
    ledger_append({"run_id": rid, "ended": now(), "event": "end", "limit_hits": hits,
                   "cost_usd": meta["cost_usd"]})
    return meta


def limit_retry_state(rid):
    """'run' if the generation hit a usage/rate limit, 'followup' if only the follow-up did, else None."""
    m = C.load_json(C.RAW / rid / "meta.json")
    if not m:
        return None
    if "usage_or_rate_limit" in (m.get("limit_hits") or []):
        return "run"
    f = m.get("followup") or {}
    if "usage_or_rate_limit" in (f.get("limit_hits") or []) or (m.get("session_id") and f.get("reply") is None):
        return "followup"
    return None


def supersede_limited(rid):
    """Keep a limit-hit attempt on record (decision D9) and free its slot for a retry."""
    t = int(time.time())
    raw = C.RAW / rid
    base, _, _ = C.run_dirs(rid)
    if raw.exists():
        raw.rename(raw.with_name(f"{rid}.limit-{t}"))
    if base.exists():
        base.rename(base.with_name(f"{rid}.limit-{t}"))
    ledger_append({"run_id": rid, "event": "superseded", "time": now(),
                   "reason": "D9: hit a usage or rate limit; retried", "kept_as": f"{rid}.limit-{t}"})


def probe(model):
    """One tiny call to see whether the model is usable again. Returns True when it is."""
    d = C.WORK / "_probe"
    d.mkdir(parents=True, exist_ok=True)
    env = {k: v for k, v in os.environ.items() if not k.startswith(("CLAUDE", "ANTHROPIC"))}
    try:
        p = subprocess.run(["claude", "-p", "Reply with just OK.", "--model", model, "--output-format", "json",
                            "--setting-sources", "project", "--strict-mcp-config"],
                           cwd=d, env=env, capture_output=True, text=True, timeout=300)
    except subprocess.TimeoutExpired:
        return False
    try:
        r = json.loads(p.stdout)
    except json.JSONDecodeError:
        return False
    return not r.get("is_error") and not LIMIT_RE.search(str(r.get("result", "")) + p.stderr)


def regenerate_scorecard(out):
    p = subprocess.run([sys.executable, os.path.join(os.path.dirname(os.path.abspath(__file__)), "analyze.py")],
                       capture_output=True, text=True)
    out(f"    scorecard regenerated ({'ok' if p.returncode == 0 else 'FAILED: ' + p.stderr[-300:]})")


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--dry-run", action="store_true")
    ap.add_argument("--models", default=",".join(C.MODELS))
    ap.add_argument("--tasks", default=",".join(C.TASKS))
    ap.add_argument("--conditions", default="A,B")
    ap.add_argument("--runs", type=int, help="runs per task per model per condition (default A=3, B=4)")
    ap.add_argument("--only", action="append", help="run id(s) to run, e.g. B-logsum-opus-1")
    ap.add_argument("--cap", type=int, default=C.HARD_CAP, help="max counted attempts in the ledger")
    ap.add_argument("--max-new", type=int, help="stop launching after this many new runs")
    ap.add_argument("--parallel", type=int, default=1, help="concurrent agent runs (decision D8: 4)")
    ap.add_argument("--probe-interval", type=int, default=1200, help="seconds between probes while paused")
    ap.add_argument("--max-wait-hours", type=float, default=24.0, help="give up after pausing this long in total")
    ap.add_argument("--no-measure", action="store_true", help="skip classification + conformance after each run")
    a = ap.parse_args()

    if a.only:
        ids = a.only
    else:
        ids = C.plan(a.models.split(","), a.tasks.split(","), a.conditions.split(","), a.runs)
    positions = C.full_plan_positions()

    # Limit-hit generations are retried; limit-hit follow-ups are repeated in place.
    retry_runs = [r for r in ids if limit_retry_state(r) == "run"]
    followups = [r for r in ids if limit_retry_state(r) == "followup"]
    todo = [r for r in ids if not (C.RAW / r / "meta.json").exists() or r in retry_runs]
    led = ledger_attempts()
    attempts = (sum(1 for e in led if e.get("event") == "start")
                - sum(1 for e in led if e.get("event") == "superseded"))

    print(f"planned: {len(ids)}  already recorded: {len(ids) - len(todo)}  to run: {len(todo)}  "
          f"limit retries: {len(retry_runs)}  follow-up retries: {len(followups)}")
    print(f"ledger attempts so far: {attempts}  cap: {a.cap}  seed: {C.SEED}  parallel: {a.parallel}")
    by = {}
    for r in todo:
        c, t, m, _ = C.parse_run_id(r)
        by[(c, t, m)] = by.get((c, t, m), 0) + 1
    for (c, t, m), n in sorted(by.items()):
        print(f"  {c} {t:9s} {m:7s} {n}")
    if a.dry_run:
        for r in todo:
            print(f"  {positions.get(r, '-'):>3} {r}")
        return 0
    if C.tool_path("claude") is None:
        sys.exit("claude not found on PATH")

    import threading
    sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
    lock = threading.Lock()
    out_lock = threading.Lock()
    st = {"attempts": attempts, "consec": 0, "stop": None, "paused": False, "active": 0,
          "completed": 0, "launched": 0, "queue": list(todo), "followups": list(followups),
          "limited_models": set()}

    def out(msg):
        with out_lock:
            print(msg, flush=True)

    def finish(rid, meta):
        """Bookkeeping after a run or follow-up: stop rules, limit pause, retries, measurement."""
        hit = "usage_or_rate_limit" in (meta.get("limit_hits") or [])
        fhit = "usage_or_rate_limit" in ((meta.get("followup") or {}).get("limit_hits") or [])
        with lock:
            if meta.get("api_key_source") not in (None, "none"):
                st["stop"] = f"run {rid} used apiKeySource={meta.get('api_key_source')}"
            if hit or fhit:
                st["consec"] += 1
                st["limited_models"].add(meta["model_alias"])
                if hit:
                    supersede_limited(rid)
                    st["attempts"] -= 1
                    st["queue"].insert(0, rid)         # retried first, keeping the shuffled order
                else:
                    st["followups"].append(rid)
                if st["consec"] >= 2 and not st["paused"]:
                    st["paused"] = True
                    out(f"PAUSE: two runs in a row (completion order) hit a usage or rate limit "
                        f"({', '.join(sorted(st['limited_models']))}); waiting for in-flight runs, then probing")
            else:
                st["consec"] = 0
        if not hit and not fhit and not a.no_measure:
            try:
                import measure
                r = measure.measure_run(rid, bench=False, force=True)
                cl = r["classification"]
                js = cl.get("java_style") or {}
                out(f"    measured {rid}: lang={cl['language']} pass={r['conformance'].get('passed')}/"
                    f"{r['conformance'].get('total')} style={js.get('combination', '-')} flags={cl['flags']}")
            except Exception as e:  # measurement problems must not stop generation
                out(f"    measure {rid} FAILED: {e!r}")
        with lock:
            st["completed"] += 1
            batch = st["completed"] % a.parallel == 0
        if batch:
            regenerate_scorecard(out)

    def worker():
        while True:
            with lock:
                if st["stop"]:
                    return
                if st["paused"]:
                    job = None
                elif st["followups"]:
                    job = ("followup", st["followups"].pop(0))
                    st["active"] += 1
                elif st["queue"] and not (a.max_new and st["launched"] >= a.max_new):
                    if st["attempts"] >= a.cap:
                        st["stop"] = f"cap of {a.cap} attempts reached"
                        return
                    job = ("run", st["queue"].pop(0))
                    st["attempts"] += 1
                    st["launched"] += 1
                    st["active"] += 1
                else:
                    return
            if job is None:
                time.sleep(5)
                continue
            kind, rid = job
            try:
                if kind == "followup":
                    out(f"--- follow-up retry {rid} {now()}")
                    meta = redo_followup(rid)
                    meta = dict(meta, limit_hits=[])
                else:
                    auth = C.auth_status()
                    if auth.get("authMethod") != "claude.ai":
                        with lock:
                            st["stop"] = f"auth mode is not the claude.ai subscription: {auth}"
                            st["queue"].insert(0, rid)
                            st["attempts"] -= 1
                        continue
                    with lock:
                        n = st["attempts"]
                    out(f"--- [{n}/{a.cap}] {rid} (seed position {positions.get(rid)}) {now()}")
                    meta = execute(rid, positions)
                    out(f"    {rid}: model={meta['model_id']} turns={meta['num_turns']} cost=${meta['cost_usd']} "
                        f"wall={meta['wall_s']}s subtype={meta['result_subtype']} limits={meta['limit_hits']} "
                        f"followup_limits={meta['followup'].get('limit_hits')} apiKeySource={meta['api_key_source']}")
                    out(f"    {rid}: run command: {meta['run_command']}")
                finish(rid, meta)
            finally:
                with lock:
                    st["active"] -= 1

    threads = [threading.Thread(target=worker, daemon=True) for _ in range(a.parallel)]
    for t in threads:
        t.start()
    waited = 0.0
    while any(t.is_alive() for t in threads):
        time.sleep(5)
        with lock:
            ready = st["paused"] and st["active"] == 0 and not st["stop"]
            models = sorted(st["limited_models"])
        if ready:
            if waited / 3600 >= a.max_wait_hours:
                with lock:
                    st["stop"] = f"paused for {a.max_wait_hours} h in total without the limit clearing"
                continue
            out(f"    paused; probing {', '.join(models)} every {a.probe_interval}s ({now()})")
            while True:
                # Wall-clock wait: time.sleep does not advance while the machine sleeps.
                t0 = time.time()
                while time.time() - t0 < a.probe_interval:
                    time.sleep(10)
                waited += time.time() - t0
                if all(probe(m) for m in models):
                    out(f"RESUME: limit cleared ({now()})")
                    with lock:
                        st["paused"] = False
                        st["consec"] = 0
                        st["limited_models"] = set()
                    break
                if waited / 3600 >= a.max_wait_hours:
                    break
    regenerate_scorecard(out)
    if st["stop"]:
        print(f"STOP: {st['stop']}")
        return 3
    print(f"done: {st['completed']} completed this session; queue left: {len(st['queue'])}")
    return 0


if __name__ == "__main__":
    sys.exit(main())
