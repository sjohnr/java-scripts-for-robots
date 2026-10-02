#!/usr/bin/env python3
"""Measurements for recorded runs and for the bake-off baselines. Uses no model tokens.

  python3 harness/measure.py                       # classify + conformance + size/disk for every run
  python3 harness/measure.py --bench               # ... plus startup and optimized startup
  python3 harness/measure.py --only A-logsum-opus-1 --bench --force
  python3 harness/measure.py --baselines --bench   # Go/Python/Node bake-off implementations

Results: results/raw/<run-id>/measure.json and results/baselines.json.
"""
import argparse
import datetime
import glob
import json
import os
import re
import shlex
import shutil
import subprocess
import sys
import tempfile
import time

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
import common as C  # noqa: E402
from classify import classify, java_top_level, read_text  # noqa: E402

CASES = json.loads((C.CONF / "cases.json").read_text())
LOOSE = {"logsum-help", "treesum-help", "jsonpick-help", "jarsrc-help", "jsonpick-bad-json"}
TRAINING_CASE = {"logsum": "logsum-basic", "treesum": "treesum-basic", "jsonpick": "jsonpick-object",
                 "jarsrc": "jarsrc-find-glob-doublestar"}
CASE_TIMEOUT_S = 60
WARM_TIMEOUT_S = 600
PLACEHOLDER_RE = re.compile(r"^(\[.*\]|<.*>|\.\.\.|ARGS?|\[?OPTIONS\]?|FILE|DIR|PATH)$")
# Excluded from disk totals (reported separately): runtime downloads and the Go compiler cache.
RUNTIME_CACHE_PARTS = [("jbang", "cache/jdks"), ("gomodcache", "golang.org/toolchain*"), ("gocache", "")]

_enc = None


def tokens(text):
    global _enc
    if _enc is None:
        import tiktoken
        _enc = tiktoken.get_encoding("o200k_base")
    return len(_enc.encode(text, disallowed_special=()))


def substitute(arg):
    return (arg.replace("{jarsrc}", str(C.FIX / "jarsrc"))
               .replace("{logsum}", str(C.FIX / "logsum.log"))
               .replace("{treesum}", str(C.FIX / "treesum"))
               .replace("{jsonpick}", str(C.FIX / "jsonpick.json"))
               .replace("{badjson}", str(C.FIX / "badjson.json")))


def normalize_command(cmd, task=None):
    """Remove trailing argument placeholders ([FILE] <args> ...) from a reported run command."""
    if not cmd:
        return None, []
    notes = []
    # Usage-style placeholder groups that may span words: [--since TIMESTAMP], <file>, ...
    stripped = re.sub(r"\s+(\[[^\]]*\]|<[^>]*>|\.\.\.)(?=\s|$)", "", cmd)
    while stripped != cmd:
        notes.append(f"dropped placeholders: {cmd[len(stripped):] if cmd.startswith(stripped) else cmd}")
        cmd = stripped
        stripped = re.sub(r"\s+(\[[^\]]*\]|<[^>]*>|\.\.\.)(?=\s|$)", "", cmd)
    try:
        toks = shlex.split(cmd)
    except ValueError:
        return cmd, ["unparseable; used verbatim"]
    if any(t in ("&&", "||", ";", "|") for t in toks) or "&&" in cmd or ";" in cmd:
        # Compound command: only a trailing --help/-h on the last segment is removed.
        m = re.search(r"\s+(--help|-h)\s*$", cmd)
        if m:
            notes.append(f"dropped trailing {m.group(1)}")
            cmd = cmd[:m.start()]
        return cmd, notes
    tail = ("--help", "-h") + (("find", "cat", "ls") if task == "jarsrc" else ())
    while toks and len(toks) > 1 and (PLACEHOLDER_RE.match(toks[-1]) or toks[-1] in tail):
        notes.append(f"dropped trailing {toks[-1]}")
        toks.pop()
    if notes:
        return " ".join(shlex.quote(t) for t in toks), notes
    return cmd, notes


def invoke(cmd, args, cwd, env, stdin_path=None, timeout=CASE_TIMEOUT_S):
    script = cmd if ("$@" in cmd) else f'{cmd} "$@"'
    stdin_data = open(stdin_path, "rb").read() if stdin_path else None
    t0 = time.time()
    try:
        p = subprocess.run(["bash", "-c", script, "bash"] + args, cwd=cwd, env=env, input=stdin_data,
                           capture_output=True, timeout=timeout)
        return p.stdout, p.stderr, p.returncode, time.time() - t0
    except subprocess.TimeoutExpired:
        return b"", b"TIMEOUT", -999, timeout


def conformance(task, cmd, cwd, env):
    cases = [c for c in CASES if c["cli"] == task]
    out = {"cases": [], "passed": 0, "total": len(cases)}
    # One untimed-ish warm-up so a first-run compile or dependency download is not a case failure.
    _, werr, wcode, wt = invoke(cmd, ["--help"], cwd, env, timeout=WARM_TIMEOUT_S)
    out["warmup"] = {"exit": wcode, "seconds": round(wt, 2), "stderr_tail": werr[-300:].decode("utf-8", "replace")}
    for c in cases:
        args = [substitute(a) for a in c.get("args", [])]
        stdin = substitute(c["stdin"]) if "stdin" in c else None
        so, se, code, _ = invoke(cmd, args, cwd, env, stdin)
        g = C.CONF / "golden" / c["id"]
        gout, gerr, gcode = (g / "stdout").read_bytes(), (g / "stderr").read_bytes(), int((g / "exit").read_text())
        ok = (code == gcode) if c["id"] in LOOSE else (so == gout and se == gerr and code == gcode)
        rec = {"id": c["id"], "ok": ok}
        if not ok:
            rec.update({"exit": code, "want_exit": gcode,
                        "stdout_head": so[:300].decode("utf-8", "replace"),
                        "stderr_head": se[:300].decode("utf-8", "replace")})
        out["cases"].append(rec)
        out["passed"] += ok
    out["pass"] = out["passed"] == out["total"]
    return out


def load_avg():
    try:
        return os.getloadavg()[0]
    except OSError:
        return None


def hyperfine(command, cwd, env, label, outdir):
    os.makedirs(outdir, exist_ok=True)
    jf = os.path.join(outdir, f"hyperfine-{label}.json")
    la0 = load_avg()
    p = subprocess.run(["hyperfine", "--warmup", "3", "--min-runs", "20", "--shell", "bash",
                        "--export-json", jf, "--command-name", label, command],
                       cwd=cwd, env=env, capture_output=True, text=True, timeout=1800)
    la1 = load_avg()
    if p.returncode != 0:
        return {"error": (p.stderr or p.stdout)[-800:], "command": command}
    r = json.load(open(jf))["results"][0]
    return {"command": command, "median_ms": r["median"] * 1000, "mean_ms": r["mean"] * 1000,
            "stddev_ms": (r["stddev"] or 0) * 1000, "min_ms": r["min"] * 1000, "runs": len(r["times"]),
            "load_avg_before": la0, "load_avg_after": la1}


def help_command(cmd):
    return f"set -- --help; {cmd}" if "$@" in cmd else f"{cmd} --help"


def size_report(files, root):
    src = [f for f in files if f["kind"] == "source"]
    build = [f for f in files if f["kind"] == "build"]
    test = [f for f in files if f["kind"] == "test"]

    def tok(fs):
        n = 0
        for f in fs:
            t = read_text(os.path.join(root, f["path"]))
            if t is not None:
                n += tokens(t)
        return n
    return {"source_tokens": tok(src), "build_file_tokens": tok(build), "test_tokens": tok(test),
            "source_files": [f["path"] for f in src], "build_files": [f["path"] for f in build],
            "source_bytes": sum(f["bytes"] for f in src), "build_file_bytes": sum(f["bytes"] for f in build)}


def disk_report(files, root, caches):
    """Source + build files + build output (project) + per-run caches, minus runtime downloads."""
    from classify import OUTPUT_DIRS
    src_b = sum(f["bytes"] for f in files if f["kind"] in ("source", "build"))
    out_b = sum(f["bytes"] for f in files if f["kind"] == "output")
    for dp, dns, fns in os.walk(root):
        for d in list(dns):
            if d in OUTPUT_DIRS:
                out_b += C.du_bytes(os.path.join(dp, d))
                dns.remove(d)
    cache = {}
    runtime = 0
    if caches and os.path.isdir(caches):
        for n in sorted(os.listdir(caches)):
            b = C.du_bytes(os.path.join(caches, n))
            if b:
                cache[n] = b
        for name, pat in RUNTIME_CACHE_PARTS:
            for p in (glob.glob(os.path.join(caches, name, pat)) if pat else [os.path.join(caches, name)]):
                runtime += C.du_bytes(p)
    cache_b = sum(cache.values()) - runtime
    return {"source_and_build_files_bytes": src_b, "build_output_bytes": out_b, "cache_bytes": cache_b,
            "cache_breakdown": cache, "runtime_excluded_bytes": runtime,
            "total_bytes": src_b + out_b + cache_b}


# ---------- optimized startup (Java) ----------

def sh(cmd, cwd, env, timeout=900):
    p = subprocess.run(cmd, cwd=cwd, env=env, shell=isinstance(cmd, str), capture_output=True, text=True,
                       timeout=timeout)
    return p.returncode, (p.stdout + p.stderr)[-1500:]


def main_class(root, java_files):
    for rel in java_files:
        t = read_text(os.path.join(root, rel)) or ""
        methods, types, mains, s = java_top_level(t)
        if not mains:
            continue
        pkg = re.search(r"^\s*package\s+([\w.]+)\s*;", s, re.M)
        if methods:                     # compact source file: implicit class named after the file
            name = os.path.splitext(os.path.basename(rel))[0]
        else:
            m0 = mains[0]
            before = [t for t in types if t["line"] <= m0["line"]]
            name = before[-1]["name"] if before else (types[0]["name"] if types else None)
        if name:
            return (pkg.group(1) + "." if pkg else "") + name, rel
    return None, None


def java_feature(env):
    p = subprocess.run(["java", "-XshowSettings:properties", "-version"], capture_output=True, text=True, env=env)
    m = re.search(r"java\.specification\.version = (\d+)", p.stderr)
    return m.group(1) if m else "25"


def package_java(rid, task, project, env, cls, outdir):
    """Package as a jar (project's own build if it has one, else javac + jar), then
    create an AOT cache from a training run. Returns (info, classpath, main, jvm_opts)."""
    st = cls["java_style"]
    opt = os.path.join(outdir, "opt")
    if os.path.exists(opt):
        shutil.rmtree(opt)
    shutil.copytree(project, opt, symlinks=True)
    info = {"method": None, "notes": []}
    jvm = ["--enable-preview"] if st.get("preview") else []
    build = st["build"]
    cp, main = None, None
    entry = cls["launch"].get("entry")
    try:
        if "Maven" in build:
            tool = "./mvnw" if os.path.exists(os.path.join(opt, "mvnw")) else shutil.which("mvn")
            if not tool:
                return {**info, "error": "Maven build but neither ./mvnw nor mvn is available"}, None, None, jvm
            info["method"] = f"{tool} package"
            rc, log = sh(f"{tool} -q -DskipTests package dependency:copy-dependencies -DoutputDirectory=target/lib -DincludeScope=runtime", opt, env)
            if rc != 0:
                return {**info, "error": "maven package failed: " + log}, None, None, jvm
            jars = [j for j in glob.glob(os.path.join(opt, "target", "*.jar"))
                    if not re.search(r"(sources|javadoc|original-)", os.path.basename(j))]
            if not jars:
                return {**info, "error": "no jar in target/"}, None, None, jvm
            jar = max(jars, key=os.path.getsize)
            cp = ":".join([jar] + sorted(glob.glob(os.path.join(opt, "target", "lib", "*.jar"))))
        elif "Gradle" in build:
            tool = "./gradlew" if os.path.exists(os.path.join(opt, "gradlew")) else shutil.which("gradle")
            if not tool:
                return {**info, "error": "Gradle build but neither ./gradlew nor gradle is available"}, None, None, jvm
            rc, log = sh(f"{tool} -q --no-daemon installDist", opt, env)
            info["method"] = f"{tool} installDist"
            if rc != 0:
                rc, log = sh(f"{tool} -q --no-daemon jar", opt, env)
                info["method"] = f"{tool} jar"
                if rc != 0:
                    return {**info, "error": "gradle build failed: " + log}, None, None, jvm
            libs = sorted(glob.glob(os.path.join(opt, "**", "build", "install", "*", "lib", "*.jar"), recursive=True))
            if not libs:
                libs = sorted(glob.glob(os.path.join(opt, "**", "build", "libs", "*.jar"), recursive=True))
            if not libs:
                return {**info, "error": "no jar produced by gradle"}, None, None, jvm
            cp = ":".join(libs)
        elif "JBang" in build:
            src = entry if entry and os.path.exists(os.path.join(opt, entry)) else st["evidence"]["files"][0]
            jar = os.path.join(opt, "app.jar")
            info["method"] = "jbang export fatjar"
            rc, log = sh(["jbang", "export", "fatjar", "--force", "-O", jar, src], opt, env)
            if rc != 0:
                return {**info, "error": "jbang export failed: " + log}, None, None, jvm
            cp = jar
        else:
            info["method"] = "javac + jar"
            srcdir = os.path.join(opt, ".scorecard-src")
            os.makedirs(srcdir)
            files = []
            for rel in st["evidence"]["files"]:
                t = read_text(os.path.join(opt, rel)) or ""
                if t.startswith("#!"):
                    t = "//" + t                     # keep line numbers, drop the shebang
                dest_name = os.path.basename(rel)
                if not dest_name.endswith(".java"):
                    methods, types, _, _ = java_top_level(t)
                    pub = re.search(r"public\s+(?:final\s+)?(?:class|record|enum|interface)\s+(\w+)", t)
                    dest_name = (pub.group(1) if pub and not methods else re.sub(r"\W", "_", dest_name).capitalize()) + ".java"
                    info["notes"].append(f"{rel} copied as {dest_name}")
                dest = os.path.join(srcdir, os.path.dirname(rel), dest_name)
                os.makedirs(os.path.dirname(dest), exist_ok=True)
                open(dest, "w").write(t)
                files.append(dest)
            classes = os.path.join(opt, ".scorecard-classes")
            rel_files = [os.path.relpath(f, srcdir) for f in files]
            args = ["javac", "-d", classes, "-encoding", "UTF-8"]
            if st.get("preview"):
                args += ["--release", java_feature(env), "--enable-preview"]
            rc, log = sh(args + files, opt, env)
            if rc != 0:
                return {**info, "error": "javac failed: " + log}, None, None, jvm
            main, _ = main_class(srcdir, rel_files)
            if not main:
                return {**info, "error": "main class not found"}, None, None, jvm
            jar = os.path.join(opt, "app.jar")
            rc, log = sh(["jar", "--create", "--file", jar, "--main-class", main, "-C", classes, "."], opt, env)
            if rc != 0:
                return {**info, "error": "jar failed: " + log}, None, None, jvm
            cp = jar
        if main is None:
            # Main-Class from the jar manifest, else the class with main().
            for j in cp.split(":"):
                p = subprocess.run(["unzip", "-p", j, "META-INF/MANIFEST.MF"], capture_output=True, text=True)
                m = re.search(r"^Main-Class:\s*(\S+)", p.stdout, re.M)
                if m:
                    main = m.group(1)
                    break
            if main is None:
                main, _ = main_class(opt, st["evidence"]["files"])
        if main is None:
            return {**info, "error": "main class not found"}, None, None, jvm
    except subprocess.TimeoutExpired:
        return {**info, "error": "packaging timed out"}, None, None, jvm
    info["classpath"] = cp
    info["main"] = main
    # AOT cache from one training run (JEP 514 one-step workflow).
    aot = os.path.join(opt, "app.aot")
    case = next(c for c in CASES if c["id"] == TRAINING_CASE[task])
    targs = [substitute(a) for a in case["args"]]
    p = subprocess.run(["java"] + jvm + [f"-XX:AOTCacheOutput={aot}", "-cp", cp, main] + targs,
                       cwd=opt, env=env, capture_output=True, text=True, timeout=300)
    info["training"] = {"args": targs, "exit": p.returncode, "stderr_tail": p.stderr[-400:]}
    if not os.path.exists(aot):
        return {**info, "error": "AOT cache not created"}, cp, main, jvm
    info["aot_bytes"] = os.path.getsize(aot)
    info["aot"] = aot
    # Sanity: the packaged program still produces --help.
    p = subprocess.run(["java"] + jvm + [f"-XX:AOTCache={aot}", "-cp", cp, main, "--help"], cwd=opt, env=env,
                       capture_output=True, timeout=120)
    info["aot_help_exit"] = p.returncode
    info["aot_stderr"] = p.stderr[-300:].decode("utf-8", "replace")
    return info, cp, main, jvm


# ---------- per run ----------

def measure_run(rid, bench, force):
    raw = C.RAW / rid
    meta = C.load_json(raw / "meta.json")
    if meta is None:
        return None
    mpath = raw / "measure.json"
    old = C.load_json(mpath, {})
    _, project, caches = C.run_dirs(rid)
    project = str(project)
    env = C.run_env(rid)
    if old and not force:
        # Reuse classification, conformance, size and disk; add benchmarks only (decision D8).
        if not bench or "startup" in old or not old.get("conformance", {}).get("pass"):
            return old
        if not os.path.isdir(project):
            project = str(raw / "files")
        bench_run(rid, old, old["run_command_used"], project, env, old["classification"])
        old["benchmarked"] = datetime.datetime.now().isoformat(timespec="seconds")
        C.write_json(mpath, old)
        return old
    res = {"run_id": rid, "measured": datetime.datetime.now().isoformat(timespec="seconds")}
    if not os.path.isdir(project):
        project = str(raw / "files")
        res["note"] = "work directory missing; measured the recorded copy (no build output or caches)"
    manual = C.load_json(raw / "run_command.override.json")
    reported = manual["run_command"] if manual else meta.get("run_command")
    cmd, notes = normalize_command(reported, meta["task"])
    res["run_command_reported"] = meta.get("run_command")
    res["run_command_used"] = cmd
    res["run_command_notes"] = notes + (["manual override: " + manual.get("reason", "")] if manual else [])
    cls = classify(project, cmd)
    res["classification"] = {k: v for k, v in cls.items() if k != "files"}
    res["files"] = cls["files"]
    task = meta["task"]
    if cmd:
        res["conformance"] = conformance(task, cmd, project, env)
    else:
        res["conformance"] = {"pass": False, "passed": 0, "total": 0, "error": "no run command"}
    res["size"] = size_report(cls["files"], project)
    res["disk"] = disk_report(cls["files"], project, str(caches))
    if bench and res["conformance"].get("pass"):
        bench_run(rid, res, cmd, project, env, cls)
        res["benchmarked"] = res["measured"]
    C.write_json(mpath, res)
    return res


def bench_run(rid, res, cmd, project, env, cls):
    raw = C.RAW / rid
    task = C.parse_run_id(rid)[1]
    res["load_avg_at_bench_start"] = load_avg()
    res["startup"] = hyperfine(help_command(cmd), project, env, "startup", str(raw / "bench"))
    if cls["language"] == "java":
        info, cp, main, jvm = package_java(rid, task, project, env, cls, str(C.run_dirs(rid)[0]))
        res["optimized"] = info
        if info.get("aot") and info.get("aot_help_exit") == 0:
            q = shlex.quote
            base = "java " + " ".join(jvm) + " -cp " + q(cp) + " " + q(main) + " --help"
            res["optimized"]["startup_aot"] = hyperfine(
                f"java {' '.join(jvm)} -XX:AOTCache={q(info['aot'])} -cp {q(cp)} {q(main)} --help",
                project, env, "aot", str(raw / "bench"))
            res["optimized"]["startup_jar_no_aot"] = hyperfine(base, project, env, "jar", str(raw / "bench"))


# ---------- baselines ----------

def measure_baselines(bench):
    out = C.load_json(C.RESULTS / "baselines.json", {})
    base = C.WORK / "baselines"
    for task in C.TASKS:
        for lang in ("go", "python", "node"):
            key = f"{task}/{lang}"
            if key in out and (not bench or "startup" in out[key]):
                continue
            src = C.BAKEOFF / "tools" / task / lang
            d = base / task / lang
            caches = base / task / f"{lang}-caches"
            if d.exists():
                shutil.rmtree(d)
            if caches.exists():
                shutil.rmtree(caches)
            shutil.copytree(src, d, ignore=shutil.ignore_patterns("__pycache__", task))
            env = {k: v for k, v in os.environ.items() if not k.startswith(("CLAUDE", "ANTHROPIC"))}
            env.update({"GOCACHE": str(caches / "gocache"), "GOMODCACHE": str(caches / "gomodcache"),
                        "GOPATH": str(caches / "gopath")})
            if lang == "go":
                subprocess.run(["go", "build", "-o", task, "."], cwd=d, env=env, check=True, capture_output=True)
                cmd = f"./{task}"
            elif lang == "python":
                cmd = "python3 tool.py"
            else:
                cmd = "node tool.mjs"
            cls = classify(str(d), cmd)
            r = {"task": task, "language": "javascript" if lang == "node" else lang, "run_command": cmd,
                 "conformance": conformance(task, cmd, str(d), env),
                 "size": size_report(cls["files"], str(d)),
                 "disk": disk_report(cls["files"], str(d), str(caches))}
            if bench:
                r["startup"] = hyperfine(help_command(cmd), str(d), env, f"baseline-{task}-{lang}",
                                         str(C.RESULTS / "baseline-bench"))
            out[key] = r
            print(f"baseline {key}: pass={r['conformance']['pass']} tokens={r['size']['source_tokens']} "
                  f"disk={r['disk']['total_bytes']} startup={r.get('startup', {}).get('median_ms')}")
            C.write_json(C.RESULTS / "baselines.json", out)
    return out


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--only", action="append")
    ap.add_argument("--bench", action="store_true")
    ap.add_argument("--force", action="store_true")
    ap.add_argument("--baselines", action="store_true")
    a = ap.parse_args()
    if a.baselines:
        measure_baselines(a.bench)
        return 0
    ids = a.only or sorted(p.name for p in C.RAW.iterdir() if (p / "meta.json").exists() and "." not in p.name)
    for rid in ids:
        r = measure_run(rid, a.bench, a.force)
        if r is None:
            print(f"{rid}: no meta.json")
            continue
        cl = r["classification"]
        js = cl.get("java_style") or {}
        print(f"{rid}: lang={cl['language']} pass={r['conformance'].get('passed')}/{r['conformance'].get('total')} "
              f"tokens={r['size']['source_tokens']} disk={r['disk']['total_bytes']} "
              f"startup={r.get('startup', {}).get('median_ms')} "
              f"style={js.get('combination', '')} flags={cl['flags']}")
    return 0


if __name__ == "__main__":
    sys.exit(main())
