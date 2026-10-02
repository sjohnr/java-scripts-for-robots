"""Shared constants and helpers for the intro-scorecard harness."""
import hashlib
import json
import os
import pathlib
import shutil
import subprocess

ROOT = pathlib.Path(__file__).resolve().parent.parent          # experiments/intro-scorecard
REPO = ROOT.parent.parent
BAKEOFF = ROOT.parent / "llm-cli-bakeoff"
TASKS_DIR = ROOT / "tasks"
RESULTS = ROOT / "results"
RAW = RESULTS / "raw"
LEDGER = RESULTS / "ledger.jsonl"
CONF = ROOT / "conformance"
FIX = ROOT / "fixtures" / "small"
WORK = pathlib.Path(os.environ.get("INTRO_SCORECARD_WORK",
                                   str(pathlib.Path.home() / ".cache" / "intro-scorecard" / "work")))

TASKS = ["logsum", "treesum", "jsonpick", "jarsrc"]
MODELS = ["haiku", "sonnet", "opus"]
RUNS_PER_CONDITION = {"A": 3, "B": 4}
SEED = 20261010
HARD_CAP = 84

PROMPTS = {
    "A": "Build the `{cli}` command-line tool described in SPEC.md.",
    "B": "Build the `{cli}` command-line tool described in SPEC.md. Write it in Java.",
}
FOLLOWUP = "Reply with only the shell command, run from this directory, that runs the tool."

WALL_TIMEOUT_S = 15 * 60
FOLLOWUP_TIMEOUT_S = 5 * 60
MAX_BUDGET_USD = "5.00"
CLAUDE_FLAGS = ["--output-format", "stream-json", "--verbose",
                "--setting-sources", "project", "--strict-mcp-config",
                "--permission-mode", "acceptEdits",
                "--allowedTools", "Read,Write,Edit,Glob,Grep,Bash",
                "--max-budget-usd", MAX_BUDGET_USD]

# JDK for agent runs and measurements (decision D4): JDK 25 GA first on PATH, other SDKMAN JDKs removed.
JDK_HOME = os.environ.get("INTRO_SCORECARD_JDK", str(pathlib.Path.home() / ".sdkman" / "candidates" / "java" / "25.0.4-tem"))

# Per-run package caches: name -> (env var, value template relative to the run's cache dir)
CACHE_DIRS = ["gradle", "m2", "maven-home", "jbang", "pip", "pyuser", "uv", "npm", "npm-prefix",
              "gopath", "gomodcache", "gocache", "xdg-cache", "coursier"]


def run_id(cond, task, model, k):
    return f"{cond}-{task}-{model}-{k}"


def parse_run_id(rid):
    cond, task, model, k = rid.split("-")
    return cond, task, model, int(k)


def order_key(rid, seed=SEED):
    """Position in the shuffled order. Hash-based so the order of existing runs is
    stable when the plan is extended (top-ups), and fully determined by the seed."""
    return hashlib.sha256(f"{seed}:{rid}".encode()).hexdigest()


def plan(models=MODELS, tasks=TASKS, conditions=("A", "B"), runs=None, seed=SEED):
    ids = []
    for c in conditions:
        n = runs if runs is not None else RUNS_PER_CONDITION[c]
        for t in tasks:
            for m in models:
                for k in range(1, n + 1):
                    ids.append(run_id(c, t, m, k))
    return sorted(ids, key=lambda r: order_key(r, seed))


def full_plan_positions(seed=SEED):
    """Seed position (1-based) of each run in the default 84-run plan."""
    return {rid: i + 1 for i, rid in enumerate(plan(seed=seed))}


def run_dirs(rid):
    base = WORK / rid
    return base, base / "project", base / "caches"


def run_env(rid):
    """Environment for the agent and for every later invocation of its program:
    no API key, no variables inherited from an enclosing Claude Code session,
    and every package cache pointed at a per-run directory. HOME is unchanged."""
    _, _, caches = run_dirs(rid)
    env = {k: v for k, v in os.environ.items()
           if not k.startswith("CLAUDE") and not k.startswith("ANTHROPIC")}
    env["CLAUDE_CODE_DISABLE_AUTO_MEMORY"] = "1"
    env.update(jdk_env(env))
    c = lambda n: str(caches / n)
    env.update({
        "GRADLE_USER_HOME": c("gradle"),
        "MAVEN_OPTS": f"-Dmaven.repo.local={c('m2')}/repository",
        "MAVEN_USER_HOME": c("maven-home"),
        "JBANG_DIR": c("jbang"),
        "JBANG_REPO": f"{c('m2')}/repository",
        "PIP_CACHE_DIR": c("pip"),
        "PYTHONUSERBASE": c("pyuser"),
        "UV_CACHE_DIR": c("uv"),
        "npm_config_cache": c("npm"),
        "npm_config_prefix": c("npm-prefix"),
        "GOPATH": c("gopath"),
        "GOMODCACHE": c("gomodcache"),
        "GOCACHE": c("gocache"),
        "XDG_CACHE_HOME": c("xdg-cache"),
        "COURSIER_CACHE": c("coursier"),
    })
    return env


def jdk_env(env):
    """JAVA_HOME and PATH with JDK_HOME/bin first and no other SDKMAN JDK on PATH."""
    path = [p for p in env.get("PATH", "").split(":") if "/candidates/java/" not in p]
    return {"JAVA_HOME": JDK_HOME, "PATH": ":".join([os.path.join(JDK_HOME, "bin")] + path)}


def make_cache_dirs(rid):
    _, _, caches = run_dirs(rid)
    for n in CACHE_DIRS:
        (caches / n).mkdir(parents=True, exist_ok=True)


def du_bytes(path):
    """Apparent size in bytes of all regular files under path (symlinks not followed)."""
    path = pathlib.Path(path)
    if not path.exists():
        return 0
    if path.is_file():
        return path.stat().st_size
    total = 0
    for dp, dns, fns in os.walk(path):
        for f in fns:
            p = os.path.join(dp, f)
            if not os.path.islink(p):
                try:
                    total += os.path.getsize(p)
                except OSError:
                    pass
    return total


def load_json(p, default=None):
    try:
        return json.loads(pathlib.Path(p).read_text())
    except (OSError, json.JSONDecodeError):
        return default


def write_json(p, data):
    pathlib.Path(p).parent.mkdir(parents=True, exist_ok=True)
    pathlib.Path(p).write_text(json.dumps(data, indent=2, sort_keys=False) + "\n")


def auth_status():
    env = {k: v for k, v in os.environ.items()
           if not k.startswith("CLAUDE") and not k.startswith("ANTHROPIC")}
    p = subprocess.run(["claude", "auth", "status"], capture_output=True, text=True, env=env, timeout=60)
    try:
        d = json.loads(p.stdout)
    except json.JSONDecodeError:
        return {"error": p.stdout[-500:] + p.stderr[-500:]}
    return {k: d.get(k) for k in ("loggedIn", "authMethod", "apiProvider", "subscriptionType")}


def tool_path(name):
    return shutil.which(name)
