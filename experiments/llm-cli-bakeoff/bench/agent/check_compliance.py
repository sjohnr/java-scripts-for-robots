#!/usr/bin/env python3
"""Phase 5b instruction-compliance checker (R1-R10). Inspects a trial's
final directory and its stream-json transcript (captured in the raw run
JSON's "_events" field by run_phase5b.py) -- never relies on the agent's
own report of what it did.

Usage: python3 bench/agent/check_compliance.py results/agent-sonnet/raw/B-logsum-B-c-t1.json [...]
       python3 bench/agent/check_compliance.py --all   # every file under results/agent-sonnet/raw/
"""
import glob
import json
import os
import re
import sys

ROOT = os.path.dirname(os.path.dirname(os.path.dirname(os.path.abspath(__file__))))
RAW_DIR = os.path.join(ROOT, "results", "agent-sonnet", "raw")

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
import run_phase5b as harness  # noqa: E402 -- reuse detect_source_file/check_conformance, do not duplicate

BUILD_FILE_PATTERNS = ["pom.xml", "build.gradle", "build.gradle.kts", "settings.gradle", "settings.gradle.kts"]
KNOWN_SPEC_NAMES = {"spec.md", "logsum-spec.md", "jsonpick-spec.md"}
PREVIEW_FLAG_RE = re.compile(r"--enable-preview|(?<!\w)--source\b|-{1,2}source\s+2\d")


def bash_commands(events):
    cmds = []
    for e in events or []:
        if e.get("type") != "assistant":
            continue
        for block in (e.get("message", {}) or {}).get("content", []) or []:
            if block.get("type") == "tool_use" and block.get("name") == "Bash":
                c = (block.get("input") or {}).get("command")
                if c:
                    cmds.append(c)
    return cmds


def check(run):
    tool = run.get("tool")
    condition = run.get("condition")
    trial_dir = run.get("trial_dir")
    events = run.get("_events", [])
    cmds = bash_commands(events)
    cmds_text = "\n".join(cmds)

    # Re-detect the source file fresh from disk right now -- do not trust
    # the "source_file" path recorded at trial time.
    source_file = harness.detect_source_file(trial_dir, KNOWN_SPEC_NAMES) if trial_dir and os.path.isdir(trial_dir) else None
    dir_missing = not (trial_dir and os.path.isdir(trial_dir))

    r = {}
    notes = {}
    if dir_missing:
        notes["_trial_dir_missing"] = True

    # R1: implementation is Java
    r["R1"] = bool(source_file and source_file.endswith(".java"))

    # R2: single Tool.java at the path the spec/snippet requires (only B-c
    # and B-e explicitly instruct the filename "Tool.java"; B-a/B-b never
    # name a required filename, so R2 is scored against "single .java file
    # directly in the trial dir", filename match noted separately)
    java_files = []
    if trial_dir and os.path.isdir(trial_dir):
        java_files = [f for f in glob.glob(os.path.join(trial_dir, "**", "*.java"), recursive=True)
                      if os.path.basename(f) not in KNOWN_SPEC_NAMES]
    single = len(java_files) == 1
    basename_ok = single and os.path.basename(java_files[0]) == "Tool.java"
    top_level = single and os.path.dirname(java_files[0]) == trial_dir
    if condition in ("B-c", "B-e"):
        r["R2"] = single and basename_ok and top_level
    else:
        r["R2"] = single and top_level
    notes["R2"] = f"{len(java_files)} java file(s); basename={os.path.basename(java_files[0]) if single else 'n/a'}"

    # R3: no build files anywhere in the trial dir
    build_hits = []
    if trial_dir and os.path.isdir(trial_dir):
        for pat in BUILD_FILE_PATTERNS:
            build_hits += glob.glob(os.path.join(trial_dir, "**", pat), recursive=True)
    r["R3"] = len(build_hits) == 0
    notes["R3"] = build_hits

    # R4: stdlib only -- no non-java.*/javax.*/jdk.* imports, no //DEPS, no
    # third-party jar/classpath usage in any command the agent ran. Only
    # meaningful when Java was requested (B-b/B-c/B-e); n/a for B-a, which
    # never mandates a language, so a non-Java import here is expected, not
    # a violation.
    src_text = ""
    if source_file and os.path.exists(source_file):
        src_text = open(source_file, "r", errors="replace").read()
    if condition == "B-a" and not (source_file and source_file.endswith(".java")):
        r["R4"] = None
        notes["R4"] = "n/a (B-a does not mandate Java; language chosen recorded separately)"
    else:
        bad_imports = []
        for line in src_text.splitlines():
            m = re.match(r"\s*import\s+(module\s+)?([\w.]+)", line)
            if m:
                mod, name = m.group(1), m.group(2)
                if mod:
                    if name != "java.base":
                        bad_imports.append(line.strip())
                elif not (name.startswith("java.") or name.startswith("javax.") or name.startswith("jdk.")):
                    bad_imports.append(line.strip())
        has_deps_marker = "//DEPS" in src_text
        third_party_cp = bool(re.search(r"(?:^|\s)(-cp|-classpath)\s+\S*(?:\.jar|jackson|gson)", cmds_text, re.I)) \
            or bool(re.search(r"\bmvn\b|\bmaven\b|\bgradle\b|\bjbang\b", cmds_text, re.I))
        r["R4"] = not bad_imports and not has_deps_marker and not third_party_cp
        notes["R4"] = {"bad_imports": bad_imports, "deps_marker": has_deps_marker, "third_party_cp_or_buildtool": third_party_cp}

    # R5: no --enable-preview / --source preview flags in source file or
    # in any command the agent ran
    src_preview = bool(PREVIEW_FLAG_RE.search(src_text))
    cmd_preview = bool(PREVIEW_FLAG_RE.search(cmds_text))
    r["R5"] = not src_preview and not cmd_preview
    notes["R5"] = {"in_source": src_preview, "in_commands": cmd_preview}

    # R8 conformance re-run happens here (moved above R6 since R6 needs its
    # fresh details, not the stale recorded ones)
    fresh_conf = harness.check_conformance(source_file, tool) if source_file else \
        {"lang_detected": "none", "passed": 0, "total": 8, "note": "trial dir or source missing at re-check time", "details": []}
    fresh_pass = fresh_conf["passed"] == fresh_conf["total"]

    # R6: runs with plain `java Tool.java` (no extra flags needed) -- proxy:
    # the fresh re-run invocation used exactly [java, source_file] with no
    # extra args and produced results (not all executions errored)
    details = fresh_conf.get("details", [])
    r["R6"] = bool(details) and any("error" not in d for d in details)
    notes["R6"] = f"{sum(1 for d in details if 'error' in d)}/{len(details)} case invocations raised an exec error (fresh re-run)"

    # R7: snippet-specific style (only meaningful for B-c and B-e)
    if condition == "B-c":
        compact_main = bool(re.search(r"^\s*void\s+main\s*\(", src_text, re.M))
        no_public_class = not bool(re.search(r"\bpublic\s+class\b", src_text))
        uses_io_println = "IO.println" in src_text
        r["R7"] = compact_main and no_public_class and uses_io_println
        notes["R7"] = {"compact_main": compact_main, "no_public_class": no_public_class, "uses_io_println": uses_io_println}
    elif condition == "B-e":
        classic_main = bool(re.search(r"public\s+static\s+void\s+main\s*\(\s*String\[\]\s*\w+\s*\)", src_text))
        has_public_class = bool(re.search(r"\bpublic\s+class\s+Tool\b", src_text))
        no_compact_markers = not bool(re.search(r"^\s*void\s+main\s*\(", src_text, re.M)) \
            and "import module" not in src_text and "IO.println" not in src_text
        r["R7"] = classic_main and has_public_class and no_compact_markers
        notes["R7"] = {"classic_main": classic_main, "has_public_class": has_public_class, "no_compact_markers": no_compact_markers}
    else:
        r["R7"] = None
        notes["R7"] = "n/a (no snippet-mandated style for this condition)"

    # R8: conformance suite passes -- re-run fresh above (do not trust the
    # "success" field recorded at trial time); source file also re-detected
    # fresh from the trial dir rather than trusting the recorded path.
    r["R8"] = fresh_pass
    notes["R8"] = {
        "fresh_source_file": source_file, "fresh_passed": fresh_conf["passed"], "fresh_total": fresh_conf["total"],
        "lang_detected": fresh_conf.get("lang_detected"),
        "differs_from_recorded": fresh_pass != bool(run.get("success")),
    }

    # R9: agent ran the conformance suite / self-tested its own program
    # before finishing (transcript-verified, not the agent's own claim).
    # No conformance runner is available inside the trial dir, so this is
    # scored as: did the agent invoke `java <basename>` on its own source
    # file at least once via Bash before the final assistant message.
    base = os.path.basename(source_file) if source_file else None
    self_test = False
    n_hits = 0
    if base:
        # language-generic execution patterns: `java X.java`, `python3 X`,
        # `node X`, or a direct exec `./X`. A command that merely writes the
        # file (`cat > X <<EOF`, `chmod +x X`) does not count.
        classname = base[:-5] if base.endswith(".java") else None  # `java logsum` after javac, not source-launch
        exec_re = re.compile(r"(\bjava\s+\S*" + re.escape(base) + r"\b"
                              r"|\bpython3?\s+\S*" + re.escape(base) + r"\b"
                              r"|\bnode\s+\S*" + re.escape(base) + r"\b"
                              r"|(?:^|\s|\|)\./" + re.escape(base) + r"\b"
                              + (r"|\bjava\s+" + re.escape(classname) + r"\b" if classname else "") + r")")
        for c in cmds:
            if exec_re.search(c):
                n_hits += 1
                self_test = True
    r["R9"] = self_test
    notes["R9"] = f"{n_hits} self-invocation command(s) found in transcript" if base else "no source file"

    # R10: no unrequested extra files (README, tests dirs, extra scripts)
    extra = []
    if trial_dir and os.path.isdir(trial_dir):
        expected = set(KNOWN_SPEC_NAMES)
        if source_file:
            expected.add(os.path.basename(source_file))
        for entry in os.listdir(trial_dir):
            full = os.path.join(trial_dir, entry)
            if entry in expected:
                continue
            if os.path.isdir(full):
                extra.append(entry + "/")
            elif not entry.endswith(".class"):  # compiled artifacts from source-launch aren't "extra files"
                extra.append(entry)
    r["R10"] = len(extra) == 0
    notes["R10"] = extra

    return r, notes


def tool_use_blocks(events):
    blocks = []
    for e in events or []:
        if e.get("type") != "assistant":
            continue
        for block in (e.get("message", {}) or {}).get("content", []) or []:
            if block.get("type") == "tool_use":
                blocks.append(block)
    return blocks


def bash_results(events):
    """Pairs each Bash tool_use id with its tool_result is_error flag, in
    transcript order, by scanning the following "user" event(s)."""
    id_to_error = {}
    for e in events or []:
        if e.get("type") != "user":
            continue
        for block in (e.get("message", {}) or {}).get("content", []) or []:
            if block.get("type") == "tool_result":
                id_to_error[block.get("tool_use_id")] = bool(block.get("is_error"))
    return id_to_error


def turns_to_first_clean_self_test(run, source_file):
    """Proxy for 'turns to first passing conformance run': no per-turn file
    snapshots were kept (only the final trial-dir state), so this cannot
    reconstruct whether an EARLIER version of the source already passed the
    real conformance suite. Instead it reports the 1-based assistant-turn
    index of the first Bash invocation of `java <basename-of-final-file>`
    that did not return a tool error, which is the best transcript-only
    proxy available. Returns (turn_index_or_None, caveat_str)."""
    if not source_file:
        return None, "no source file"
    base = os.path.basename(source_file)
    id_to_error = bash_results(run.get("_events", []))
    turn = 0
    classname = base[:-5] if base.endswith(".java") else None
    self_test_re = re.compile(r"(\bjava\s+\S*" + re.escape(base) + r"\b"
                               r"|\bpython3?\s+\S*" + re.escape(base) + r"\b"
                               r"|\bnode\s+\S*" + re.escape(base) + r"\b"
                               r"|(?:^|\s|\|)\./" + re.escape(base) + r"\b"
                               + (r"|\bjava\s+" + re.escape(classname) + r"\b" if classname else "") + r")")
    for e in run.get("_events", []) or []:
        if e.get("type") != "assistant":
            continue
        turn += 1
        for block in (e.get("message", {}) or {}).get("content", []) or []:
            if block.get("type") == "tool_use" and block.get("name") == "Bash":
                cmd = (block.get("input") or {}).get("command", "")
                if self_test_re.search(cmd):
                    is_err = id_to_error.get(block.get("id"), None)
                    if is_err is False:
                        return turn, "proxy: first clean self-invocation, not a verified conformance-suite pass (no per-turn snapshots kept)"
    return None, "never (proxy: no clean self-invocation found in transcript)"


def source_loc_and_tokens(source_file):
    if not source_file or not os.path.exists(source_file):
        return None, None
    text = open(source_file, "r", errors="replace").read()
    lines = len(text.splitlines())
    approx_tokens = len(re.findall(r"\w+|[^\w\s]", text))  # whitespace/punct split, NOT a real tokenizer
    return lines, approx_tokens


def extract_metrics(run):
    usage = (run.get("modelUsage") or {}).get(run.get("model", "") or next(iter(run.get("modelUsage") or {}), ""), {})
    if not usage:
        # fall back: single-model runs -- take the only entry
        mu = run.get("modelUsage") or {}
        usage = next(iter(mu.values()), {})
    events = run.get("_events", [])
    tool_calls = tool_use_blocks(events)
    cost = run.get("total_cost_usd")
    budget_cap_hit = cost is not None and cost >= 0.999  # MAX_BUDGET_USD = 1.00 in run_phase5b.py
    wall_s = run.get("_wall_s_measured")
    timeout_hit = wall_s is not None and wall_s >= 599
    source_file = harness.detect_source_file(run.get("trial_dir"), KNOWN_SPEC_NAMES) \
        if run.get("trial_dir") and os.path.isdir(run.get("trial_dir")) else None
    loc, approx_tok = source_loc_and_tokens(source_file)
    tturn, tcaveat = turns_to_first_clean_self_test(run, source_file)
    return {
        "input_tokens": usage.get("inputTokens"), "output_tokens": usage.get("outputTokens"),
        "cache_read_tokens": usage.get("cacheReadInputTokens"), "cache_creation_tokens": usage.get("cacheCreationInputTokens"),
        "tool_calls": len(tool_calls),
        "budget_cap_hit": budget_cap_hit, "timeout_hit": timeout_hit,
        "turns_to_first_clean_self_test": tturn, "turns_to_first_clean_self_test_caveat": tcaveat,
        "source_loc": loc, "source_tokens_approx": approx_tok,
    }


def summarize(run):
    r, notes = check(run)
    passed = sum(1 for v in r.values() if v is True)
    scored = sum(1 for v in r.values() if v is not None)
    metrics = extract_metrics(run)
    return {
        "tool": run.get("tool"), "condition": run.get("condition"), "trial": run.get("trial"),
        "model": run.get("model_requested"), "turns": run.get("num_turns"),
        "cost_usd": run.get("total_cost_usd"), "success_conformance": run.get("success"),
        "success_conformance_fresh": r.get("R8"),
        "tokens": sum((v.get("inputTokens", 0) + v.get("outputTokens", 0) +
                        v.get("cacheReadInputTokens", 0) + v.get("cacheCreationInputTokens", 0))
                       for v in (run.get("modelUsage") or {}).values()),
        "wall_s": run.get("_wall_s_measured"),
        "rules": r, "notes": notes, "rules_passed": f"{passed}/{scored}",
        "metrics": metrics,
    }


def main():
    args = sys.argv[1:]
    if args and args[0] == "--all":
        files = sorted(glob.glob(os.path.join(RAW_DIR, "*.json")))
    else:
        files = args
    for f in files:
        run = json.load(open(f))
        s = summarize(run)
        print(json.dumps(s, indent=2))


if __name__ == "__main__":
    main()
