#!/usr/bin/env python3
"""Verdicts and reports from results/raw/*/{meta,measure}.json and results/baselines.json.

  python3 harness/analyze.py            # writes results/summary.md and results/scorecard.json
  python3 harness/analyze.py --runs     # also prints the per-run table

Manual review: results/raw/<run-id>/review.json may set {"language": ..., "reason": ...}
(overrides the classifier's language) or {"java_style": {"build": ..., "launch": ...}, "reason": ...}.
"""
import argparse
import datetime
import math
import os
import statistics
import sys

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
import common as C  # noqa: E402
from classify import SCRIPTING, COMPILED_NON_JAVA  # noqa: E402

Z = 1.959964


def wilson(k, n):
    if n == 0:
        return None, None
    p = k / n
    d = 1 + Z * Z / n
    c = (p + Z * Z / (2 * n)) / d
    h = Z * math.sqrt(p * (1 - p) / n + Z * Z / (4 * n * n)) / d
    return max(0.0, c - h), min(1.0, c + h)


def verdict(kind, t, lo, hi):
    if lo is None:
        return "inconclusive"
    if kind == ">=":
        return "supported" if lo >= t else "not supported" if hi < t else "inconclusive"
    if kind == ">":
        return "supported" if lo > t else "not supported" if hi <= t else "inconclusive"
    if kind == "<=":
        return "supported" if hi <= t else "not supported" if lo > t else "inconclusive"
    if kind == "<":
        return "supported" if hi < t else "not supported" if lo >= t else "inconclusive"
    raise ValueError(kind)


def load_runs():
    runs = []
    for d in sorted(C.RAW.iterdir()) if C.RAW.exists() else []:
        if not d.is_dir() or "." in d.name:
            continue
        meta = C.load_json(d / "meta.json")
        if not meta:
            continue
        m = C.load_json(d / "measure.json", {})
        rv = C.load_json(d / "review.json", {})
        cl = m.get("classification", {})
        lang = rv.get("language") or cl.get("language")
        js = dict(cl.get("java_style") or {})
        js.update(rv.get("java_style", {}))
        if js:
            js["combination"] = f"{js.get('build')} / {js.get('launch')}"
        runs.append({
            "id": meta["run_id"], "cond": meta["condition"], "task": meta["task"], "model": meta["model_alias"],
            "model_id": meta.get("model_id"), "meta": meta, "m": m, "lang": lang,
            "java": js if lang == "java" else None, "measured": bool(m),
            "pass": bool(m.get("conformance", {}).get("pass")),
            "startup": (m.get("startup") or {}).get("median_ms"),
            "aot": ((m.get("optimized") or {}).get("startup_aot") or {}).get("median_ms"),
            "tokens": (m.get("size") or {}).get("source_tokens"),
            "disk": (m.get("disk") or {}).get("total_bytes"),
            "review": rv, "flags": cl.get("flags", []),
        })
    return runs


def pct(x):
    return "n/a" if x is None else f"{100 * x:.0f}%"


def ci(lo, hi):
    return "n/a" if lo is None else f"{100 * lo:.0f}% - {100 * hi:.0f}%"


def prop_row(eid, hyp, text, k, n, kind, t):
    lo, hi = wilson(k, n)
    return {"id": eid, "hypothesis": hyp, "expectation": text, "n": n, "count": k,
            "share": (k / n) if n else None, "ci_low": lo, "ci_high": hi, "exceptions": None,
            "threshold": t, "comparison": kind, "verdict": verdict(kind, t, lo, hi) if n else "inconclusive"}


def every_row(eid, hyp, text, n, exceptions):
    return {"id": eid, "hypothesis": hyp, "expectation": text, "n": n, "count": n - exceptions,
            "share": ((n - exceptions) / n) if n else None, "ci_low": None, "ci_high": None,
            "exceptions": exceptions, "threshold": None, "comparison": "every",
            "verdict": ("inconclusive (n=0)" if n == 0 else "supported" if exceptions == 0 else "not supported")}


def med(xs):
    xs = [x for x in xs if x is not None]
    return statistics.median(xs) if xs else None


def competitor_sets(runs):
    """{set_name: {task: {lang: [program dicts with startup/tokens/disk]}}}"""
    base = C.load_json(C.RESULTS / "baselines.json", {})
    sets = {"bake-off": {}, "condition A": {}}
    for key, b in base.items():
        sets["bake-off"].setdefault(b["task"], {}).setdefault(b["language"], []).append({
            "id": f"bake-off {key}", "startup": (b.get("startup") or {}).get("median_ms"),
            "tokens": b["size"]["source_tokens"], "disk": b["disk"]["total_bytes"]})
    for r in runs:
        if r["cond"] == "A" and r["pass"] and r["lang"] and r["lang"] != "java":
            sets["condition A"].setdefault(r["task"], {}).setdefault(r["lang"], []).append(
                {"id": r["id"], "startup": r["startup"], "tokens": r["tokens"], "disk": r["disk"]})
    return sets


def h4_rows(java, sets, suffix=""):
    rows, detail = [], {}
    for sname, s in sets.items():
        tag = "bake-off" if sname == "bake-off" else "condA"
        # Startup: every unoptimized Java program slower than the Go, Python and Node medians.
        n = exc = 0
        ex_ids = []
        for r in java:
            langs = s.get(r["task"], {})
            meds = [med([p["startup"] for p in langs.get(l, [])]) for l in ("go", "python", "javascript")]
            meds = [x for x in meds if x is not None]
            if r["startup"] is None or not meds:
                continue
            n += 1
            if not all(r["startup"] > x for x in meds):
                exc += 1
                ex_ids.append(r["id"])
        row = every_row(f"H4.startup.{tag}{suffix}", 4,
                        "Startup: every unoptimized Java program starts more slowly than the median Go, Python and Node programs for the same task.",
                        n, exc)
        row["baseline_set"] = sname
        row["exception_ids"] = ex_ids
        rows.append(row)
        # Source size: median Java > 1.25 x median of smallest competing language (per task),
        # evaluated as the share of Java programs above that bar, against 50%.
        k = n = 0
        for r in java:
            langs = s.get(r["task"], {})
            lm = [med([p["tokens"] for p in v]) for v in langs.values()]
            lm = [x for x in lm if x]
            if r["tokens"] is None or not lm:
                continue
            n += 1
            k += r["tokens"] > 1.25 * min(lm)
        row = prop_row(f"H4.source.{tag}{suffix}", 4,
                       "Source size: the median Java program is more than 25% larger, in tokens, than the median of the smallest competing language for the same task.",
                       k, n, ">", 0.5)
        row["baseline_set"] = sname
        row["method"] = "share of Java programs above 1.25x the smallest per-language median, against 50%"
        rows.append(row)
        # Disk: Java programs with dependencies > median competing program (all of them).
        n = exc = 0
        ex_ids = []
        for r in java:
            if not r["java"] or r["java"].get("dependencies") != "one or more":
                continue
            comp = [p["disk"] for v in s.get(r["task"], {}).values() for p in v]
            if r["disk"] is None or not comp:
                continue
            n += 1
            if not r["disk"] > med(comp):
                exc += 1
                ex_ids.append(r["id"])
        row = every_row(f"H4.disk.{tag}{suffix}", 4,
                        "Disk: Java programs that use dependencies occupy more disk space than the median competing program for the same task.",
                        n, exc)
        row["baseline_set"] = sname
        row["exception_ids"] = ex_ids
        rows.append(row)
        # Optimization: median AOT startup within 2x the Python and Node medians,
        # evaluated as the share of optimized Java programs within 2x of both, against 50%.
        k = n = 0
        for r in java:
            langs = s.get(r["task"], {})
            meds = [med([p["startup"] for p in langs.get(l, [])]) for l in ("python", "javascript")]
            meds = [x for x in meds if x is not None]
            if r["aot"] is None or not meds:
                continue
            n += 1
            k += all(r["aot"] <= 2 * x for x in meds)
        row = prop_row(f"H4.optimization.{tag}{suffix}", 4,
                       "Optimization: with optimization applied, median Java startup is within twice the median Python and Node startup.",
                       k, n, ">", 0.5)
        row["baseline_set"] = sname
        row["method"] = "share of AOT-optimized Java programs within 2x of the Python and Node medians, against 50%"
        rows.append(row)
    return rows


def expectations(runs, suffix=""):
    A = [r for r in runs if r["cond"] == "A" and r["lang"]]
    B = [r for r in runs if r["cond"] == "B"]
    Bj = [r for r in B if r["lang"] == "java"]
    rows = []
    n = len(A)
    rows.append(prop_row("H1.python" + suffix, 1, "Python is produced in at least 50% of runs.",
                         sum(r["lang"] == "python" for r in A), n, ">=", 0.5))
    rows.append(prop_row("H1.scripting" + suffix, 1,
                         "A scripting language (Python, JavaScript, TypeScript or a shell script) is produced in at least two-thirds of runs.",
                         sum(r["lang"] in SCRIPTING for r in A), n, ">=", 2 / 3))
    rows.append(prop_row("H1.compiled" + suffix, 1,
                         "A compiled language other than Java (for example Go or Rust) is produced in at most 25% of runs.",
                         sum(r["lang"] in COMPILED_NON_JAVA for r in A), n, "<=", 0.25))
    rows.append(prop_row("H1.java" + suffix, 1, "Java is produced in fewer than 10% of runs.",
                         sum(r["lang"] == "java" for r in A), n, "<", 0.10))
    Bm = [r for r in B if r["lang"]]
    row = every_row("H2.java" + suffix, 2, 'Appending "Write it in Java." to the prompt produces Java in every run.',
                    len(Bm), sum(r["lang"] != "java" for r in Bm))
    row["exception_ids"] = [r["id"] for r in Bm if r["lang"] != "java"]
    rows.append(row)
    rows.append(prop_row("H2.pass" + suffix, 2, "At least 85% of those runs pass the task's conformance tests.",
                         sum(r["pass"] for r in Bj), len(Bj), ">=", 0.85))
    combos = {}
    for r in Bj:
        combos[r["java"]["combination"]] = combos.get(r["java"]["combination"], 0) + 1
    distinct = len(combos)
    rows.append({"id": "H3.distinct" + suffix, "hypothesis": 3, "expectation": "At least four distinct style combinations are produced.",
                 "n": len(Bj), "count": distinct, "share": None, "ci_low": None, "ci_high": None, "exceptions": None,
                 "threshold": 4, "comparison": "count >=",
                 "verdict": "inconclusive" if not Bj else "supported" if distinct >= 4 else "not supported"})
    top = max(combos.values()) if combos else 0
    row = prop_row("H3.max_share" + suffix, 3, "No single style combination accounts for more than two-thirds of runs.",
                   top, len(Bj), "<=", 2 / 3)
    row["most_common"] = max(combos, key=combos.get) if combos else None
    rows.append(row)
    java = [r for r in runs if r["lang"] == "java" and r["pass"]]
    rows += h4_rows(java, competitor_sets(runs), suffix)
    return rows


def fmt_row(r):
    obs = "n/a" if r["n"] == 0 else (
        f"{r['count']} distinct (n={r['n']})" if r["comparison"] == "count >=" else
        f"{r['count']}/{r['n']} ({pct(r['share'])})")
    if r["comparison"] == "every":
        interval = f"{r['exceptions']} exception(s)" if r["n"] else "n/a"
    elif r["comparison"] == "count >=":
        interval = "count, no interval"
    else:
        interval = ci(r["ci_low"], r["ci_high"])
    exp = r["expectation"] + (f" [{r['baseline_set']}]" if r.get("baseline_set") else "")
    return f"| {r['id']} | {exp} | {obs} | {interval} | {r['verdict']} |"


def table(rows):
    out = ["| ID | Expectation | Observed | 95% interval | Verdict |", "|---|---|---|---|---|"]
    return out + [fmt_row(r) for r in rows]


def kb(b):
    return "n/a" if b is None else (f"{b / 1e6:.1f} MB" if b >= 1e6 else f"{b / 1e3:.1f} KB")


def ms(x):
    return "n/a" if x is None else f"{x:.1f}"


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--runs", action="store_true")
    a = ap.parse_args()
    runs = load_runs()
    rows = expectations(runs)
    L = ["# Intro scorecard: summary", "",
         f"Generated {datetime.datetime.now().isoformat(timespec='minutes')} from {len(runs)} recorded runs "
         f"({sum(r['cond'] == 'A' for r in runs)} condition A, {sum(r['cond'] == 'B' for r in runs)} condition B). "
         "Pooled across models. Verdict rule: supported if the 95% Wilson interval lies entirely on the expected side "
         "of the threshold, not supported if entirely on the other side, otherwise inconclusive. \"Every\" expectations "
         "report exceptions instead and are supported only with zero exceptions.", "",
         "## Verdicts", ""] + table(rows)

    # Per-model breakdown
    L += ["", "## Per-model breakdown (supporting detail)", ""]
    for m in C.MODELS:
        sub = [r for r in runs if r["model"] == m]
        if not sub:
            continue
        ids = sorted({r["model_id"] for r in sub if r["model_id"]})
        L += [f"### {m} ({', '.join(ids)})", ""] + table(expectations(sub)) + [""]

    # Per-task language distribution (A)
    L += ["## Condition A: language by task", ""]
    langs = sorted({r["lang"] or "none" for r in runs if r["cond"] == "A"})
    L += ["| Task | " + " | ".join(langs) + " | n |", "|---" * (len(langs) + 2) + "|"]
    for t in C.TASKS:
        sub = [r for r in runs if r["cond"] == "A" and r["task"] == t]
        L.append(f"| {t} | " + " | ".join(str(sum((r['lang'] or 'none') == l for r in sub)) for l in langs) + f" | {len(sub)} |")
    nonjar = [r for r in runs if r["cond"] == "A" and r["task"] != "jarsrc" and r["lang"]]
    jar = [r for r in runs if r["cond"] == "A" and r["task"] == "jarsrc" and r["lang"]]
    if jar and nonjar:
        L += ["", f"Java share: jarsrc {sum(r['lang'] == 'java' for r in jar)}/{len(jar)}, other tasks "
                  f"{sum(r['lang'] == 'java' for r in nonjar)}/{len(nonjar)}. "
                  f"Python share: jarsrc {sum(r['lang'] == 'python' for r in jar)}/{len(jar)}, other tasks "
                  f"{sum(r['lang'] == 'python' for r in nonjar)}/{len(nonjar)}."]

    # Style combinations (B)
    Bj = [r for r in runs if r["cond"] == "B" and r["java"]]
    L += ["", "## Condition B: style combinations", ""]
    combos = {}
    for r in Bj:
        combos.setdefault(r["java"]["combination"], []).append(r)
    L += ["| Build / launch | Count | Share | Runs |", "|---|---|---|---|"]
    for c, rs in sorted(combos.items(), key=lambda kv: -len(kv[1])):
        L.append(f"| {c} | {len(rs)} | {pct(len(rs) / len(Bj))} | {', '.join(r['id'] for r in rs)} |")
    dims = ["build", "launch", "wrapper_dim", "source", "imports", "files", "dependencies", "optimization"]
    L += ["", "All dimensions, per run:", "", "| Run | Model | " + " | ".join("Wrapper" if d == "wrapper_dim" else d.capitalize() for d in dims) + " | Pass |",
          "|---" * (len(dims) + 3) + "|"]
    for r in sorted(Bj, key=lambda r: r["id"]):
        L.append(f"| {r['id']} | {r['model_id']} | " + " | ".join(str(r['java'].get(d)) for d in dims) +
                 f" | {'yes' if r['pass'] else 'no'} |")
    L += ["", "Dimension totals:", "", "| Dimension | Values |", "|---|---|"]
    for d in dims:
        cnt = {}
        for r in Bj:
            cnt[r["java"].get(d)] = cnt.get(r["java"].get(d), 0) + 1
        L.append(f"| {d} | " + ", ".join(f"{k}: {v}" for k, v in sorted(cnt.items(), key=lambda kv: -kv[1])) + " |")

    # Sensitivity (decision D2): Make and build script folded into "none".
    def fold(b):
        parts = [x for x in (b or "none").split("+") if x not in ("Make", "build script")]
        return "+".join(parts) or "none"
    folded = {}
    for r in Bj:
        k = f"{fold(r['java'].get('build'))} / {r['java'].get('launch')}"
        folded[k] = folded.get(k, 0) + 1
    sensitivity = None
    if Bj:
        top = max(folded.values())
        lo, hi = wilson(top, len(Bj))
        sensitivity = {"rule": "Make and build script counted as build none", "combinations": folded,
                       "distinct": len(folded),
                       "distinct_verdict": "supported" if len(folded) >= 4 else "not supported",
                       "max_share": top / len(Bj), "max_share_ci": [lo, hi],
                       "max_share_verdict": verdict("<=", 2 / 3, lo, hi)}
        L += ["", "### Sensitivity: Make and build script counted as build \"none\"", "",
              "| Build / launch | Count |", "|---|---|"]
        L += [f"| {k} | {v} |" for k, v in sorted(folded.items(), key=lambda kv: -kv[1])]
        L += ["", "| Expectation | Observed | 95% interval | Verdict |", "|---|---|---|---|",
              f"| At least four distinct style combinations | {len(folded)} distinct | count, no interval | "
              f"{'supported' if len(folded) >= 4 else 'not supported'} |",
              f"| No single combination over two-thirds | {top}/{len(Bj)} ({pct(top / len(Bj))}) | {ci(lo, hi)} | "
              f"{verdict('<=', 2 / 3, lo, hi)} |"]

    # jsonpick dependency vs hand-written parser
    jp = [r for r in Bj if r["task"] == "jsonpick"]
    L += ["", "## jsonpick in condition B: dependency vs hand-written parser", "",
          "| Run | Parser | Dependencies | Source tokens | Disk | Pass |", "|---|---|---|---|---|---|"]
    for r in sorted(jp, key=lambda r: r["id"]):
        deps = r["java"].get("dependency_list") or []
        L.append(f"| {r['id']} | {'dependency' if deps else 'hand-written'} | {', '.join(deps) or 'none'} | "
                 f"{r['tokens']} | {kb(r['disk'])} | {'yes' if r['pass'] else 'no'} |")
    for kind in ("dependency", "hand-written"):
        g = [r for r in jp if bool(r["java"].get("dependency_list")) == (kind == "dependency")]
        if g:
            L.append(f"| median ({kind}, n={len(g)}) | | | {med([r['tokens'] for r in g])} | {kb(med([r['disk'] for r in g]))} | |")

    # H4 comparisons
    sets = competitor_sets(runs)
    java = [r for r in runs if r["lang"] == "java" and r["pass"]]
    L += ["", "## Hypothesis 4 comparisons", "",
          "Medians per task. Startup in ms (`--help`, hyperfine, run as the program instructs). Java rows: passing Java programs from both conditions; AOT = harness-packaged jar with an AOT cache.", ""]
    h4lang = []
    for t in C.TASKS:
        L += [f"### {t}", "", "| Set | Language | n | Startup median | Source tokens median | Disk median |", "|---|---|---|---|---|---|"]
        for sname, s in sets.items():
            for l, ps in sorted(s.get(t, {}).items()):
                e = {"task": t, "set": sname, "language": l, "n": len(ps),
                     "startup_median_ms": med([p["startup"] for p in ps]),
                     "source_tokens_median": med([p["tokens"] for p in ps]),
                     "disk_median_bytes": med([p["disk"] for p in ps])}
                h4lang.append(e)
                L.append(f"| {sname} | {l} | {len(ps)} | {ms(e['startup_median_ms'])} | {e['source_tokens_median']} | {kb(e['disk_median_bytes'])} |")
        jt = [r for r in java if r["task"] == t]
        e = {"task": t, "set": "generated", "language": "java", "n": len(jt),
             "startup_median_ms": med([r["startup"] for r in jt]), "source_tokens_median": med([r["tokens"] for r in jt]),
             "disk_median_bytes": med([r["disk"] for r in jt])}
        eo = {"task": t, "set": "generated", "language": "java (AOT)", "n": sum(r["aot"] is not None for r in jt),
              "startup_median_ms": med([r["aot"] for r in jt]), "source_tokens_median": None, "disk_median_bytes": None}
        h4lang += [e, eo]
        L.append(f"| generated | java | {len(jt)} | {ms(e['startup_median_ms'])} | {e['source_tokens_median']} | {kb(e['disk_median_bytes'])} |")
        L.append(f"| generated | java (AOT) | {eo['n']} | {ms(eo['startup_median_ms'])} | | |")
        L.append("")
    not_packaged = [(r["id"], (r["m"].get("optimized") or {}).get("error")) for r in java
                    if (r["m"].get("optimized") or {}).get("error")]
    if not_packaged:
        L += ["Programs that could not be packaged with an AOT cache:", "", "| Run | Reason |", "|---|---|"]
        L += [f"| {i} | {str(e).splitlines()[0][:200]} |" for i, e in not_packaged] + [""]

    # Run log
    L += ["## Runs", "", "| Run | Model ID | Turns | Output tokens | Cost (USD) | Wall (s) | Language | Pass | Run command | Limits | Review flags |",
          "|---|---|---|---|---|---|---|---|---|---|---|"]
    for r in sorted(runs, key=lambda r: r["meta"].get("seed_position") or 999):
        mt = r["meta"]
        L.append(f"| {r['id']} | {mt.get('model_id')} | {mt.get('num_turns')} | {(mt.get('tokens') or {}).get('output_tokens')} | "
                 f"{(mt.get('cost_usd') or 0):.2f} | {mt.get('wall_s')} | {r['lang']} | {'yes' if r['pass'] else 'no'} | "
                 f"`{(r['m'].get('run_command_used') or mt.get('run_command') or '').replace('|', '\\|')}` | "
                 f"{', '.join(mt.get('limit_hits') or []) or '-'} | {'; '.join(r['flags']) or '-'} |")
    cost = sum((r["meta"].get("cost_usd") or 0) for r in runs)
    fcost = sum(((r["meta"].get("followup") or {}).get("cost_usd") or 0) for r in runs)
    L += ["", f"Generation cost (notional, list price): ${cost:.2f}. Follow-up turns (excluded): ${fcost:.2f}."]

    for extra in ("topup.md", "threats.md"):
        f = C.RESULTS / extra
        if f.exists():
            L += ["", f.read_text().strip()]
    (C.RESULTS / "summary.md").write_text("\n".join(L) + "\n")
    C.write_json(C.RESULTS / "scorecard.json", {
        "generated": datetime.datetime.now().isoformat(timespec="seconds"),
        "runs": {"total": len(runs), "A": sum(r["cond"] == "A" for r in runs), "B": sum(r["cond"] == "B" for r in runs)},
        "expectations": [{k: v for k, v in r.items()} for r in rows],
        "h4_by_language": h4lang,
        "h3_sensitivity": sensitivity,
        "h3_combinations": {c: len(rs) for c, rs in combos.items()},
    })
    print("\n".join(table(rows)))
    return 0


if __name__ == "__main__":
    sys.exit(main())
