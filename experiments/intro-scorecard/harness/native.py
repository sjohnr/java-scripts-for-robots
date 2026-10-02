#!/usr/bin/env python3
"""Native-image startup for the intro scorecard's Java programs.

For every run whose measure.json has an AOT startup (the "Startup, optimized" row), builds a GraalVM
native image from the same jar, class path and main class that measure.py packaged, the way a javaw
--native wrapper does (`native-image <jvm opts> -cp <jar:cp> <Main>`), then benchmarks `<image> --help`
with the same hyperfine settings as measure.py. Writes results/raw/<run-id>/native.json; runs that
already have one are skipped unless --force.

  harness/native.py                        # every eligible run (sequential; ~15-30 s per build)
  harness/native.py --only B-treesum-haiku-2
  harness/native.py --limit 3 --force

Needs native-image on PATH; by default the script puts sdkman's 25-graalce first on PATH.
Images go to ~/.cache/intro-scorecard/native/<run-id>/ (override with INTRO_SCORECARD_NATIVE).
"""
import argparse, json, os, shlex, subprocess, sys, time
from pathlib import Path

sys.path.insert(0, str(Path(__file__).parent))
from measure import hyperfine  # same hyperfine settings as the AOT and startup rows

RESULTS = Path(__file__).resolve().parent.parent / "results"
OUT = Path(os.environ.get("INTRO_SCORECARD_NATIVE", Path.home() / ".cache/intro-scorecard/native"))


def graal_env():
    env = dict(os.environ)
    home = Path(os.environ.get("SDKMAN_DIR", Path.home() / ".sdkman")) / "candidates/java/25-graalce"
    if home.is_dir():
        env["JAVA_HOME"] = str(home)
        env["PATH"] = f"{home}/bin:{env['PATH']}"
    env.pop("JAVA_TOOL_OPTIONS", None)
    return env


def jvm_opts(aot_command):
    """JVM options measure.py used: everything between `java` and -XX:AOTCache=... in the AOT command."""
    parts = shlex.split(aot_command)
    end = next(i for i, p in enumerate(parts) if p.startswith("-XX:AOTCache="))
    return parts[1:end]


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--only", action="append", default=[])
    ap.add_argument("--limit", type=int)
    ap.add_argument("--force", action="store_true")
    args = ap.parse_args()
    env = graal_env()
    if subprocess.run(["which", "native-image"], env=env, capture_output=True).returncode != 0:
        sys.exit("native-image not found (sdk install java 25-graalce)")

    todo = []
    for d in sorted((RESULTS / "raw").iterdir()):
        m = d / "measure.json"
        if ".limit-" in d.name or not m.exists() or (args.only and d.name not in args.only):
            continue
        opt = (json.loads(m.read_text()).get("optimized") or {})
        if not (opt.get("startup_aot") or {}).get("median_ms"):
            continue
        if (d / "native.json").exists() and not args.force:
            continue
        todo.append((d, opt))
    if args.limit:
        todo = todo[:args.limit]
    print(f"{len(todo)} runs to build")

    for i, (d, opt) in enumerate(todo, 1):
        rid, cp, main_class = d.name, opt["classpath"], opt["main"]
        jvm = jvm_opts(opt["startup_aot"]["command"])
        out = OUT / rid
        out.mkdir(parents=True, exist_ok=True)
        image = out / "app"
        res = {"run_id": rid, "classpath": cp, "main": main_class, "jvm": jvm, "image": str(image)}
        print(f"[{i}/{len(todo)}] {rid}: native-image {main_class}", flush=True)
        started = time.time()
        p = subprocess.run(["native-image", *jvm, "-cp", cp, main_class, "-o", str(image)],
                           cwd=out, env=env, capture_output=True, text=True, timeout=1800)
        res["build_seconds"] = round(time.time() - started, 1)
        if p.returncode != 0 or not image.exists():
            res["error"] = "native-image failed: " + (p.stdout + p.stderr)[-1500:]
        else:
            res["image_bytes"] = image.stat().st_size
            h = subprocess.run([str(image), "--help"], cwd=out, env=env, capture_output=True, timeout=60)
            res["help_exit"] = h.returncode
            if h.returncode != opt.get("aot_help_exit", 0):
                res["error"] = f"--help exit {h.returncode} (JVM: {opt.get('aot_help_exit')}): " + h.stderr[-300:].decode("utf-8", "replace")
            else:
                res["startup_native"] = hyperfine(f"{shlex.quote(str(image))} --help", str(out), env, "native", str(d / "bench"))
        (d / "native.json").write_text(json.dumps(res, indent=1) + "\n")
        if "error" in res:
            print("    " + res["error"][:200], flush=True)
        else:
            print(f"    {res['startup_native'].get('median_ms', 0):.1f} ms", flush=True)


if __name__ == "__main__":
    main()
