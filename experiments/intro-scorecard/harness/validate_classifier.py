#!/usr/bin/env python3
"""Validate classify.py against existing programs with hand-assigned labels:
the bake-off Java variants (experiments/llm-cli-bakeoff/tools/*/java/) and the
javaw results (experiments/javaw/results/). Sources are read in place or copied
into a scratch directory; nothing under those experiments is modified.

Writes results/classifier-validation.json and .md.
"""
import os
import shutil
import sys
import tempfile

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
import common as C  # noqa: E402
from classify import classify  # noqa: E402

T = C.BAKEOFF / "tools"
J = C.ROOT.parent / "javaw" / "results"

# (label, files to stage {dest: source} or a directory, run command, expected)
# Expected keys: language, and for Java: build, launch, source, imports, files, dependencies, optimization.
CASES = [
    ("bakeoff logsum classic (javac + java)", {"Tool.java": T / "logsum/java/classic/Tool.java"},
     "javac -d build/classes Tool.java && java -cp build/classes Tool",
     dict(language="java", wrapper_dim="none", build="none", launch="javac + java", source="classic", imports="classic",
          files="single file", dependencies="none", optimization="none")),
    ("bakeoff treesum classic (jar)", {"Tool.java": T / "treesum/java/classic/Tool.java"},
     "java -jar build/treesum.jar",
     dict(language="java", wrapper_dim="none", build="none", launch="jar", source="classic", imports="classic",
          files="single file", dependencies="none", optimization="none")),
    ("bakeoff jarsrc compact", T / "jarsrc/java/compact", "java Tool.java",
     dict(language="java", wrapper_dim="none", build="none", launch="source launch", source="compact source",
          imports="module imports", files="single file", dependencies="none", optimization="none")),
    ("bakeoff jarsrc multi", T / "jarsrc/java/multi", "java Tool.java",
     dict(language="java", wrapper_dim="none", build="none", launch="source launch", source="classic", imports="classic",
          files="multiple files", dependencies="none", optimization="none")),
    ("bakeoff noop compact", T / "noop/java/compact", "java Tool.java",
     dict(language="java", wrapper_dim="none", build="none", launch="source launch", source="compact source",
          imports="classic", files="single file", dependencies="none", optimization="none")),
    ("bakeoff jsonpick jbang (jbang cmd)", T / "jsonpick/java/jbang", "jbang tool.java",
     dict(language="java", wrapper_dim="none", build="JBang", launch="source launch", source="classic", imports="classic",
          files="single file", dependencies="one or more", optimization="none")),
    ("bakeoff jsonpick jbang (shebang)", T / "jsonpick/java/jbang", "./tool.java",
     dict(language="java", wrapper_dim="none", build="JBang", launch="shebang", source="classic", imports="classic",
          files="single file", dependencies="one or more", optimization="none")),
    ("bakeoff jarsrc shebang", {"jarsrc": T / "jarsrc/java/build/jarsrc-shebang"}, "./jarsrc",
     dict(language="java", wrapper_dim="none", build="none", launch="shebang", source="classic", imports="classic",
          files="single file", dependencies="none", optimization="none")),
    ("bakeoff jarsrc AOT cache", {"Tool.java": T / "jarsrc/java/classic/Tool.java"},
     "java -XX:AOTCache=build/jarsrc.aot -jar build/jarsrc.jar",
     dict(language="java", wrapper_dim="none", build="none", launch="jar", source="classic", imports="classic",
          files="single file", dependencies="none", optimization="AOT cache")),
    ("bakeoff jarsrc native image", {"Tool.java": T / "jarsrc/java/classic/Tool.java",
                                     "jarsrc-native": T / "jarsrc/java/build/jarsrc-native"},
     "./jarsrc-native",
     dict(language="java", wrapper_dim="none", build="none", launch="native binary", source="classic", imports="classic",
          files="single file", dependencies="none", optimization="GraalVM native image")),
    ("bakeoff logsum go", T / "logsum/go", "./logsum", dict(language="go")),
    ("bakeoff logsum python", T / "logsum/python", "python3 tool.py", dict(language="python")),
    ("bakeoff logsum node", T / "logsum/node", "node tool.mjs", dict(language="javascript")),
    ("javaw 02 go", J / "02-generate-javaw-go--opus", "./javaw", dict(language="go")),
    ("javaw 03 javascript", J / "03-generate-javaw-javascript--opus", "./javaw", dict(language="javascript")),
    ("javaw 04 python", J / "04-generate-javaw-python--opus", "./javaw", dict(language="python")),
    ("javaw 05 java (sh wrapper)", J / "05-generate-javaw-java--opus", "./javaw",
     dict(language="java", wrapper_dim="wrapper script", build="none", launch="source launch", source="classic", imports="classic",
          files="single file", dependencies="none", optimization="none")),
    ("javaw 05 java (java cmd)", J / "05-generate-javaw-java--opus", "java Javaw.java",
     dict(language="java", wrapper_dim="none", build="none", launch="source launch", source="classic", imports="classic",
          files="single file", dependencies="none", optimization="none")),
    ("javaw 06 jbang", J / "06-generate-javaw-java-jbang--opus", "jbang javaw.java",
     dict(language="java", wrapper_dim="none", build="JBang", launch="source launch", source="classic", imports="classic",
          files="single file", dependencies="none", optimization="none")),
    ("javaw 11 compact (java cmd)", J / "11-generate-javaw-java-compact-source--opus", "java Javaw.java",
     dict(language="java", wrapper_dim="none", build="none", launch="source launch", source="compact source",
          imports="module imports", files="single file", dependencies="none", optimization="none")),
    ("javaw 11 compact (sh wrapper)", J / "11-generate-javaw-java-compact-source--opus", "./javaw",
     dict(language="java", wrapper_dim="wrapper script", build="none", launch="source launch", source="compact source",
          imports="module imports", files="single file", dependencies="none", optimization="none")),
    ("javaw 12 shebang", J / "12-generate-javaw-shebang--opus", "./javaw",
     dict(language="java", wrapper_dim="none", build="none", launch="shebang", source="classic", imports="classic",
          files="single file", dependencies="none", optimization="none")),
    ("javaw 13 detailed", J / "13-generate-javaw-java-detailed--opus", "./javaw",
     dict(language="java", wrapper_dim="none", build="none", launch="shebang", source="compact source", imports="module imports",
          files="single file", dependencies="none", optimization="none")),
    ("javaw 13 detailed v2", J / "13-generate-javaw-java-detailed--opus--v2", "./javaw",
     dict(language="java", wrapper_dim="none", build="none", launch="shebang", source="compact source", imports="module imports",
          files="single file", dependencies="none", optimization="none")),
]
DIMS = ["build", "launch", "wrapper_dim", "source", "imports", "files", "dependencies", "optimization"]


def stage(src, scratch):
    d = tempfile.mkdtemp(dir=scratch)
    if isinstance(src, dict):
        for dest, s in src.items():
            shutil.copy2(s, os.path.join(d, dest))
    else:
        shutil.copytree(src, d, dirs_exist_ok=True, ignore=shutil.ignore_patterns(".generate", "build"))
    return d


def main():
    scratch = tempfile.mkdtemp(prefix="classifier-validation-")
    rows, mismatches = [], 0
    for label, src, cmd, exp in CASES:
        d = stage(src, scratch)
        r = classify(d, cmd)
        got = {"language": r["language"]}
        if r.get("java_style"):
            got.update({k: r["java_style"][k] for k in DIMS})
        diffs = {k: (v, got.get(k)) for k, v in exp.items() if got.get(k) != v}
        mismatches += bool(diffs)
        rows.append({"case": label, "run_command": cmd, "expected": exp, "got": got,
                     "match": not diffs, "diffs": diffs, "flags": r["flags"]})
    shutil.rmtree(scratch)
    C.write_json(C.RESULTS / "classifier-validation.json", rows)
    lines = ["# Classifier validation", "",
             f"{len(rows) - mismatches} of {len(rows)} cases match the hand-assigned labels on every dimension.", "",
             "| Case | Run command | Language | Build | Launch | Wrapper | Source | Imports | Files | Deps | Optimization | Match |",
             "|---|---|---|---|---|---|---|---|---|---|---|---|"]
    for r in rows:
        g = r["got"]
        cells = [g.get("language")] + [g.get(k, "") for k in DIMS]
        cells = [f"**{c}** (expected {r['expected'][k]})" if k in r["diffs"] else (c or "")
                 for k, c in zip(["language"] + DIMS, cells)]
        lines.append(f"| {r['case']} | `{r['run_command']}` | " + " | ".join(cells) +
                     f" | {'yes' if r['match'] else 'NO'} |")
    (C.RESULTS / "classifier-validation.md").write_text("\n".join(lines) + "\n")
    print("\n".join(lines))
    return 1 if mismatches else 0


if __name__ == "__main__":
    sys.exit(main())
