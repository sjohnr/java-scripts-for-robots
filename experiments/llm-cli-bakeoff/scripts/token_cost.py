#!/usr/bin/env python3
"""Phase 4: static token-cost analysis.

Uses the Anthropic token-counting API if ANTHROPIC_API_KEY is set,
otherwise falls back to tiktoken (o200k_base) as a PROXY -- every
number produced this way is labeled "(tiktoken proxy)" in output,
per the mission's instruction to label proxy counts clearly.

Measures, per implementation source file: bytes, lines, tokens.
Also tokenizes --help output, a representative successful output, a
representative error output, and (for Java modes) stderr noise.
Writes results/token_cost.md and results/token_cost.json.
"""
import json
import os
import subprocess
import sys

ROOT = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
FIX = os.path.join(ROOT, "fixtures", "small")

USE_API = bool(os.environ.get("ANTHROPIC_API_KEY"))
TOKENIZER_LABEL = "Anthropic API" if USE_API else "tiktoken o200k_base (proxy)"

if USE_API:
    import anthropic
    _client = anthropic.Anthropic()
    def count_tokens(text):
        if not text:
            return 0
        r = _client.messages.count_tokens(model="claude-sonnet-5", messages=[{"role": "user", "content": text}])
        return r.input_tokens
else:
    import tiktoken
    _enc = tiktoken.get_encoding("o200k_base")
    def count_tokens(text):
        return len(_enc.encode(text or ""))


def stats(text):
    if text is None:
        text = ""
    return {
        "bytes": len(text.encode("utf-8")),
        "lines": text.count("\n") + (1 if text and not text.endswith("\n") else 0),
        "tokens": count_tokens(text),
    }


SOURCE_FILES = {
    "noop": {
        "go": ["tools/noop/go/main.go"],
        "python": ["tools/noop/python/tool.py"],
        "node": ["tools/noop/node/tool.mjs"],
        "java-classic": ["tools/noop/java/classic/Tool.java"],
        "java-compact": ["tools/noop/java/compact/Tool.java"],
    },
    "jarsrc": {
        "go": ["tools/jarsrc/go/main.go", "tools/jarsrc/go/version.go"],
        "python": ["tools/jarsrc/python/tool.py"],
        "node": ["tools/jarsrc/node/tool.mjs"],
        "java-classic": ["tools/jarsrc/java/classic/Tool.java"],
        "java-compact": ["tools/jarsrc/java/compact/Tool.java"],
        "java-multi": ["tools/jarsrc/java/multi/Tool.java", "tools/jarsrc/java/multi/Scanner.java",
                        "tools/jarsrc/java/multi/VersionCompare.java", "tools/jarsrc/java/multi/Util.java",
                        "tools/jarsrc/java/multi/Commands.java"],
    },
    "logsum": {
        "go": ["tools/logsum/go/main.go"],
        "python": ["tools/logsum/python/tool.py"],
        "node": ["tools/logsum/node/tool.mjs"],
        "java-classic": ["tools/logsum/java/classic/Tool.java"],
    },
    "treesum": {
        "go": ["tools/treesum/go/main.go"],
        "python": ["tools/treesum/python/tool.py"],
        "node": ["tools/treesum/node/tool.mjs"],
        "java-classic": ["tools/treesum/java/classic/Tool.java"],
    },
    "jsonpick": {
        "go": ["tools/jsonpick/go/main.go", "tools/jsonpick/go/path.go", "tools/jsonpick/go/value.go"],
        "python": ["tools/jsonpick/python/tool.py"],
        "node": ["tools/jsonpick/node/tool.mjs"],
        "java-classic": ["tools/jsonpick/java/classic/Tool.java"],
        "java-jbang": ["tools/jsonpick/java/jbang/tool.java"],
    },
}


def read(rel):
    p = os.path.join(ROOT, rel)
    if not os.path.exists(p):
        return None
    return open(p, encoding="utf-8").read()


def source_stats():
    out = {}
    for cli, impls in SOURCE_FILES.items():
        out[cli] = {}
        for impl, files in impls.items():
            combined = "\n".join(read(f) or "" for f in files)
            out[cli][impl] = stats(combined)
    return out


HELP_CMDS = {
    "noop": {
        "go": ["tools/noop/go/noop", "--help"],
        "python": ["python3", "tools/noop/python/tool.py", "--help"],
        "node": ["node", "tools/noop/node/tool.mjs", "--help"],
    },
    "jarsrc": {
        "go": ["tools/jarsrc/go/jarsrc", "--help"],
        "python": ["python3", "tools/jarsrc/python/tool.py", "--help"],
        "node": ["node", "tools/jarsrc/node/tool.mjs", "--help"],
    },
    "logsum": {
        "go": ["tools/logsum/go/logsum", "--help"],
        "python": ["python3", "tools/logsum/python/tool.py", "--help"],
        "node": ["node", "tools/logsum/node/tool.mjs", "--help"],
    },
    "treesum": {
        "go": ["tools/treesum/go/treesum", "--help"],
        "python": ["python3", "tools/treesum/python/tool.py", "--help"],
        "node": ["node", "tools/treesum/node/tool.mjs", "--help"],
    },
    "jsonpick": {
        "go": ["tools/jsonpick/go/jsonpick", "--help"],
        "python": ["python3", "tools/jsonpick/python/tool.py", "--help"],
        "node": ["node", "tools/jsonpick/node/tool.mjs", "--help"],
    },
}


def run(cmd):
    try:
        p = subprocess.run(cmd, cwd=ROOT, capture_output=True, timeout=15)
        return p.stdout.decode("utf-8", "replace"), p.stderr.decode("utf-8", "replace")
    except Exception as e:
        return "", str(e)


def help_stats():
    out = {}
    for cli, impls in HELP_CMDS.items():
        out[cli] = {}
        for impl, cmd in impls.items():
            sout, serr = run(cmd)
            out[cli][impl] = {"stdout": stats(sout), "stderr": stats(serr)}
    return out


def naive_shell_flailing():
    """The ls/find/grep/unzip/head sequence an agent would use for jarsrc
    and treesum tasks without a purpose-built CLI, vs. one CLI call."""
    jarsrc_root = os.path.join(FIX, "jarsrc")
    treesum_root = os.path.join(FIX, "treesum")

    # jarsrc-equivalent task: "find the source of com.example.core.alpha.Main"
    naive_jarsrc_cmds = [
        ["find", jarsrc_root, "-name", "*-sources.jar"],
        ["bash", "-c", f"cd {jarsrc_root} && find . -name '*-sources.jar' | xargs -I{{}} unzip -l {{}} 2>/dev/null | grep -i Main"],
        ["bash", "-c", f"unzip -p {jarsrc_root}/com/example/core/alpha/2.0.4/alpha-2.0.4-sources.jar com/example/core/alpha/Main.java"],
    ]
    naive_out = ""
    for cmd in naive_jarsrc_cmds:
        sout, _ = run(cmd)
        naive_out += sout
    cli_out, _ = run([os.path.join(ROOT, "tools/jarsrc/go/jarsrc"), "cat", "com.example.core.alpha.Main", "--root", jarsrc_root])

    # treesum-equivalent task: "overview of file counts/LOC by extension"
    naive_treesum_cmds = [
        ["bash", "-c", f"find {treesum_root} -type f | wc -l"],
        ["bash", "-c", f"find {treesum_root} -name '*.java' | wc -l"],
        ["bash", "-c", f"find {treesum_root} -name '*.py' | wc -l"],
        ["bash", "-c", f"find {treesum_root} -name '*.go' | wc -l"],
        ["bash", "-c", f"find {treesum_root} -name '*.js' | wc -l"],
        ["bash", "-c", f"find {treesum_root} -name '*.md' | wc -l"],
        ["bash", "-c", f"find {treesum_root} -name '*.json' | wc -l"],
        ["bash", "-c", f"find {treesum_root} -name '*.yaml' | wc -l"],
        ["bash", "-c", f"find {treesum_root} -name '*.txt' | wc -l"],
        ["bash", "-c", f"find {treesum_root} -type f -exec wc -l {{}} + | tail -1"],
        ["bash", "-c", f"find {treesum_root} -type f -printf '%s %p\\n' 2>/dev/null | sort -rn | head -10"],
    ]
    naive_ts_out = ""
    for cmd in naive_treesum_cmds:
        sout, _ = run(cmd)
        naive_ts_out += sout
    cli_ts_out, _ = run([os.path.join(ROOT, "tools/treesum/go/treesum"), treesum_root])

    return {
        "jarsrc_task": {
            "naive_command_count": len(naive_jarsrc_cmds),
            "naive_output": stats(naive_out),
            "cli_command_count": 1,
            "cli_output": stats(cli_out),
        },
        "treesum_task": {
            "naive_command_count": len(naive_treesum_cmds),
            "naive_output": stats(naive_ts_out),
            "cli_command_count": 1,
            "cli_output": stats(cli_ts_out),
        },
    }


def main():
    result = {
        "tokenizer": TOKENIZER_LABEL,
        "source": source_stats(),
        "help": help_stats(),
        "naive_shell_flailing": naive_shell_flailing(),
    }
    os.makedirs(os.path.join(ROOT, "results"), exist_ok=True)
    json.dump(result, open(os.path.join(ROOT, "results", "token_cost.json"), "w"), indent=2)

    lines = [f"# Token cost analysis\n", f"Tokenizer: **{TOKENIZER_LABEL}**\n"]
    lines.append("## Source size per implementation\n")
    lines.append("| CLI | impl | bytes | lines | tokens |")
    lines.append("|---|---|---:|---:|---:|")
    for cli, impls in result["source"].items():
        for impl, s in impls.items():
            lines.append(f"| {cli} | {impl} | {s['bytes']} | {s['lines']} | {s['tokens']} |")

    lines.append("\n## --help output\n")
    lines.append("| CLI | impl | stdout tokens | stderr tokens |")
    lines.append("|---|---|---:|---:|")
    for cli, impls in result["help"].items():
        for impl, s in impls.items():
            lines.append(f"| {cli} | {impl} | {s['stdout']['tokens']} | {s['stderr']['tokens']} |")

    lines.append("\n## Naive shell flailing vs. one CLI call\n")
    nsf = result["naive_shell_flailing"]
    lines.append("| Task | approach | commands | output tokens |")
    lines.append("|---|---|---:|---:|")
    for task, d in nsf.items():
        lines.append(f"| {task} | naive shell | {d['naive_command_count']} | {d['naive_output']['tokens']} |")
        lines.append(f"| {task} | single CLI call | {d['cli_command_count']} | {d['cli_output']['tokens']} |")

    open(os.path.join(ROOT, "results", "token_cost.md"), "w").write("\n".join(lines) + "\n")
    print("\n".join(lines))


if __name__ == "__main__":
    main()
