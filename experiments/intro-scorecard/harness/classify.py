#!/usr/bin/env python3
"""Static classification of a generated program: language of the entry point and,
for Java, the Hypothesis 3 style dimensions. No model tokens; file inspection only.

  python3 harness/classify.py DIR "RUN COMMAND"     # classify one directory
"""
import json
import os
import re
import shlex
import sys

LANG_BY_EXT = {
    ".py": "python", ".js": "javascript", ".mjs": "javascript", ".cjs": "javascript",
    ".ts": "typescript", ".mts": "typescript", ".cts": "typescript",
    ".go": "go", ".rs": "rust", ".java": "java", ".sh": "shell", ".bash": "shell", ".zsh": "shell",
    ".rb": "ruby", ".pl": "perl", ".c": "c", ".cc": "c++", ".cpp": "c++", ".kt": "kotlin",
    ".kts": "kotlin", ".swift": "swift", ".zig": "zig", ".cs": "c#", ".scala": "scala", ".groovy": "groovy",
}
SCRIPTING = {"python", "javascript", "typescript", "shell"}
COMPILED_NON_JAVA = {"go", "rust", "c", "c++", "swift", "zig", "c#", "kotlin", "scala"}

# Build output and dependency directories: never source.
OUTPUT_DIRS = {".git", "node_modules", "build", "target", "out", ".gradle", ".venv", "venv", "env",
               "__pycache__", "dist", ".mypy_cache", ".pytest_cache", ".m2", ".jbang", ".idea", ".vscode"}
BUILD_FILES = {"pom.xml", "build.gradle", "build.gradle.kts", "settings.gradle", "settings.gradle.kts",
               "gradle.properties", "gradlew", "gradlew.bat", "mvnw", "mvnw.cmd", "go.mod", "go.sum",
               "package.json", "package-lock.json", "tsconfig.json", "pyproject.toml", "setup.py",
               "setup.cfg", "Cargo.toml", "Cargo.lock", "Makefile", "makefile", "jbang-catalog.json",
               "requirements.txt", "Pipfile", "Pipfile.lock", "uv.lock", "poetry.lock", "yarn.lock",
               "pnpm-lock.yaml", "bun.lockb", "deno.json", "build.sh", "install.sh"}
BUILD_DIR_PREFIXES = ("gradle/wrapper/", ".mvn/")
DOC_EXT = {".md", ".txt", ".rst", ".adoc"}
TEST_DIR_RE = re.compile(r"(^|/)(tests?|__tests__|testdata|test-data|spec|fixtures?|src/test|examples?|samples?|demo)(/|$)", re.I)
TEST_FILE_RE = re.compile(r"(^test[_\-.]|[_\-.]test\.|_test\.go$|Tests?\.java$|\.spec\.|^run_tests|^test$|^tests$"
                          r"|^test\.sh$|^check\.sh$|^verify\.sh$|^smoke)", re.I)
SHEBANG_LANG = [
    (re.compile(r"jbang"), "java"),
    (re.compile(r"\bjava\b"), "java"),
    (re.compile(r"python"), "python"),
    (re.compile(r"\b(tsx|ts-node|deno|bun)\b"), "typescript"),
    (re.compile(r"\bnode\b"), "javascript"),
    (re.compile(r"\b(bash|sh|zsh|dash|ksh)\b"), "shell"),
    (re.compile(r"\bruby\b"), "ruby"),
    (re.compile(r"\bperl\b"), "perl"),
]
MACHO_ELF = (b"\xcf\xfa\xed\xfe", b"\xca\xfe\xba\xbe", b"\x7fELF", b"\xfe\xed\xfa\xcf")


def read_text(p, limit=None):
    try:
        b = open(p, "rb").read() if limit is None else open(p, "rb").read(limit)
    except OSError:
        return None
    if b[:4] in MACHO_ELF or b"\x00" in b[:4096]:
        return None
    return b.decode("utf-8", "replace")


def shebang_lang(text):
    if not text:
        return None
    first = text.split("\n", 1)[0]
    if first.startswith("///usr/bin/env jbang") or first.startswith("//usr/bin/env jbang"):
        return "java"
    if not first.startswith("#!"):
        return None
    for rx, lang in SHEBANG_LANG:
        if rx.search(first):
            return lang
    return "unknown-shebang"


def file_kind(rel, full):
    """source | test | build | doc | output | spec | data"""
    name = os.path.basename(rel)
    if rel == "SPEC.md":
        return "spec", None
    head = open(full, "rb").read(4) if os.path.isfile(full) else b""
    if head in MACHO_ELF or name.endswith((".class", ".jar", ".aot", ".jsa", ".pyc", ".o")):
        if name in ("gradle-wrapper.jar", "maven-wrapper.jar"):
            return "build", None
        return "output", None
    if name in BUILD_FILES or rel.startswith(BUILD_DIR_PREFIXES):
        return "build", None
    ext = os.path.splitext(name)[1].lower()
    if ext in DOC_EXT or name.upper().startswith(("LICENSE", "README")):
        return "doc", None
    lang = LANG_BY_EXT.get(ext)
    if lang is None and ext == "":
        lang = shebang_lang(read_text(full, 512))
        if lang == "unknown-shebang":
            lang = "shell"
    if lang is None:
        return "data", None
    if TEST_DIR_RE.search(os.path.dirname(rel)) or TEST_FILE_RE.search(name):
        return "test", lang
    return "source", lang


def inventory(root):
    files = []
    for dp, dns, fns in os.walk(root):
        dns[:] = sorted(d for d in dns if d not in OUTPUT_DIRS)
        for f in sorted(fns):
            full = os.path.join(dp, f)
            if os.path.islink(full):
                continue
            rel = os.path.relpath(full, root)
            kind, lang = file_kind(rel, full)
            files.append({"path": rel, "kind": kind, "lang": lang, "bytes": os.path.getsize(full)})
    return files


def split_segments(cmd):
    """Split a shell command line on && ; || | into simple command token lists."""
    try:
        lex = shlex.shlex(cmd, posix=True, punctuation_chars=";&|")
        lex.whitespace_split = True
        toks = list(lex)
    except ValueError:
        return None
    segs, cur = [], []
    for t in toks:
        if t in ("&&", ";", "||", "|", "&"):
            if cur:
                segs.append(cur)
            cur = []
        else:
            cur.append(t)
    if cur:
        segs.append(cur)
    return segs


def strip_prefix(tokens):
    i = 0
    while i < len(tokens) and (re.match(r"^[A-Za-z_][A-Za-z0-9_]*=", tokens[i]) or tokens[i] in ("exec", "time", "env", "command")):
        i += 1
    return tokens[i:]


def resolve(root, cwd, p):
    p = p.replace("$PWD/", "").replace("${PWD}/", "")
    full = os.path.normpath(os.path.join(root, cwd, p))
    return full if os.path.exists(full) else None


WRAPPER_EXEC_RE = re.compile(
    r"(?:^|[\s;&|(])(?:exec\s+)?(java|jbang|python3?|node|go|deno|bun|tsx|\S*/gradlew|gradle|\S*/mvnw|mvn)\b([^\n]*)")


def describe_launch(root, cmd, depth=0, base_cwd="."):
    """Follow the run command (and up to two wrapper scripts) to the entry point.
    Returns dict: lang, entry, launch, wrapper, evidence[]."""
    out = {"lang": None, "entry": None, "launch": None, "wrapper": None, "evidence": [], "flags": [],
           "java_opts": ""}
    segs = split_segments(cmd or "")
    if not segs:
        out["flags"].append("run command missing or unparseable")
        return out
    cwd = base_cwd
    java_segs = []
    for seg in segs:
        seg = strip_prefix(seg)
        if not seg:
            continue
        if seg[0] == "cd" and len(seg) > 1:
            cwd = os.path.join(cwd, seg[1])
            continue
        java_segs.append((cwd, seg))
    if not java_segs:
        out["flags"].append("no command in run command")
        return out
    # The last segment is what runs the tool; earlier ones (javac, go build, ...) are build steps.
    build_steps = [s for _, s in java_segs[:-1]]
    cwd, seg = java_segs[-1]
    prog = os.path.basename(seg[0])
    args = seg[1:]
    ev = out["evidence"]
    ev.append(f"run command program: {seg[0]}")

    def first_with(exts):
        for a in args:
            if a.lower().endswith(exts):
                return a
        return None

    if re.fullmatch(r"python[0-9.]*", prog) or prog in ("uv", "pipx", "poetry", "pdm"):
        out["lang"] = "python"
        out["entry"] = first_with((".py",))
        if "-m" in args:
            out["entry"] = "-m " + args[args.index("-m") + 1]
        if out["entry"] is None and args and resolve(root, cwd, args[0]):
            out["entry"] = args[0]
    elif prog in ("node", "npx", "tsx", "ts-node", "bun", "deno", "npm"):
        e = first_with((".ts", ".mts", ".cts", ".js", ".mjs", ".cjs"))
        out["entry"] = e
        if e:
            out["lang"] = LANG_BY_EXT[os.path.splitext(e)[1].lower()]
        elif prog == "npm":
            out["lang"] = "javascript"
            out["flags"].append("npm script: entry not resolved")
        else:
            f = resolve(root, cwd, args[0]) if args else None
            out["lang"] = shebang_lang(read_text(f, 512)) if f else ("typescript" if prog in ("tsx", "ts-node") else "javascript")
            out["entry"] = args[0] if args else None
    elif prog == "java":
        out["lang"] = "java"
        opts = []
        i = 0
        while i < len(args):
            a = args[i]
            if a == "-jar" and i + 1 < len(args):
                out["entry"] = args[i + 1]
                out["launch"] = "jar"
                break
            if a in ("-cp", "-classpath", "--class-path", "-p", "--module-path", "--source", "--add-modules",
                     "--release") and i + 1 < len(args):
                opts += [a, args[i + 1]]
                if a in ("-cp", "-classpath", "--class-path"):
                    cp = args[i + 1]
                    out["classpath"] = cp
                i += 2
                continue
            if a.startswith("-"):
                opts.append(a)
                i += 1
                continue
            if a.endswith(".java") or (resolve(root, cwd, a) and shebang_lang(read_text(resolve(root, cwd, a), 512)) == "java"):
                out["entry"] = a
                out["launch"] = "source launch"
            else:
                out["entry"] = a
                cp = out.get("classpath", "")
                if re.search(r"\.jar\b", cp) or cp.endswith("/*"):
                    out["launch"] = "jar"
                else:
                    out["launch"] = "javac + java"
            break
        out["java_opts"] = " ".join(opts)
        if build_steps and any(os.path.basename(s[0]) == "javac" for s in build_steps):
            ev.append("javac build step before java")
            if out["launch"] == "source launch":
                out["flags"].append("javac step but source launch")
    elif prog == "jbang":
        out["lang"] = "java"
        rest = [a for a in args if not a.startswith("-")]
        out["entry"] = rest[0] if rest else None
        out["launch"] = "source launch"
        ev.append("jbang <file>: counted as source launch (build = JBang)")
    elif prog in ("gradle", "gradlew", "mvn", "mvnw"):
        out["lang"] = "java"
        out["launch"] = "build tool run"
        out["entry"] = " ".join(seg)
        out["flags"].append("launched through the build tool (not in the launch table)")
    elif prog == "go":
        out["lang"] = "go"
        out["entry"] = first_with((".go",)) or (args[1] if len(args) > 1 else ".")
    elif prog == "cargo":
        out["lang"] = "rust"
    elif prog in ("bash", "sh", "zsh", "dash") and args:
        out.update(_script(root, cwd, args[0], depth, ev, out))
    elif prog in ("ruby", "perl"):
        out["lang"] = prog
    else:
        out.update(_script(root, cwd, seg[0], depth, ev, out))
    if build_steps:
        ev.append("build steps: " + " && ".join(" ".join(s) for s in build_steps))
        if out["lang"] is None:
            for s in build_steps:
                b = os.path.basename(s[0])
                if b == "go":
                    out["lang"] = "go"
                elif b == "javac":
                    out["lang"] = "java"
                elif b == "cargo":
                    out["lang"] = "rust"
    return out


def _script(root, cwd, path, depth, ev, out):
    full = resolve(root, cwd, path)
    res = {}
    if not full or os.path.isdir(full):
        res["flags"] = out["flags"] + [f"entry {path} not found in project"]
        return res
    rel = os.path.relpath(full, root)
    head = open(full, "rb").read(4)
    if head in MACHO_ELF:
        res["entry"] = rel
        res["lang"] = None
        res["launch"] = "native binary"
        ev.append(f"{rel} is a native executable")
        return res
    text = read_text(full) or ""
    ext = os.path.splitext(rel)[1].lower()
    sb = shebang_lang(text)
    lang = sb or LANG_BY_EXT.get(ext)
    res["entry"] = rel
    if lang == "java":
        res["lang"] = "java"
        res["launch"] = "shebang"
        first = text.split("\n", 1)[0]
        ev.append(f"shebang: {first}")
        res["java_opts"] = first
        return res
    if lang == "shell" and depth < 2:
        # A wrapper: follow the program it hands off to, if any.
        body = "\n".join(l for l in text.splitlines() if not l.lstrip().startswith("#"))
        for m in WRAPPER_EXEC_RE.finditer(body):
            inner = (m.group(1) + m.group(2)).strip()
            # Paths prefixed with the script's own directory ("$DIR/", ${here}/, $(dirname "$0")/)
            # resolve relative to the script; drop the prefix and argument forwarding.
            script_rel = bool(re.search(r"\$\((?:[^()]|\([^()]*\))*\)/|\$\{?\w+\}?/", inner))
            inner = re.sub(r"\$\((?:[^()]|\([^()]*\))*\)/", "", inner)
            inner = re.sub(r"\$\{?\w+\}?/", "", inner)
            inner = inner.replace('"$@"', "").replace("$@", "").replace('"$*"', "")
            sub_cwd = (os.path.dirname(rel) or ".") if script_rel else cwd
            sub = describe_launch(root, inner, depth + 1, sub_cwd)
            if sub["lang"]:
                ev.append(f"wrapper script {rel} runs: {inner}")
                res.update({k: sub[k] for k in ("lang", "entry", "launch", "java_opts") if sub.get(k)})
                res["wrapper"] = rel
                res["evidence_inner"] = sub["evidence"]
                return res
        res["lang"] = "shell"
        return res
    res["lang"] = lang
    return res


# ---------- Java style ----------

def strip_java(src):
    """Remove comments, string/char/text-block literals (keep newlines)."""
    out, i, n = [], 0, len(src)
    while i < n:
        c = src[i]
        if src.startswith('"""', i):
            j = src.find('"""', i + 3)
            j = n if j < 0 else j + 3
            out.append("\n" * src.count("\n", i, j)); i = j
        elif c == '"':
            j = i + 1
            while j < n and src[j] != '"' and src[j] != "\n":
                j += 2 if src[j] == "\\" else 1
            out.append('""'); i = j + 1
        elif c == "'":
            j = i + 1
            while j < n and src[j] != "'" and src[j] != "\n":
                j += 2 if src[j] == "\\" else 1
            out.append("''"); i = j + 1
        elif src.startswith("//", i):
            j = src.find("\n", i)
            i = n if j < 0 else j
        elif src.startswith("/*", i):
            j = src.find("*/", i + 2)
            j = n if j < 0 else j + 2
            out.append("\n" * src.count("\n", i, j)); i = j
        else:
            out.append(c); i += 1
    return "".join(out)


METHOD_RE = re.compile(r"(?:^|[;}\n])\s*((?:public|private|protected|static|final|synchronized|abstract|<[^>]*>|\s)*"
                       r"[\w.<>\[\], ?]+\s+(\w+)\s*\([^;{}]*\)\s*(?:throws\s+[\w., ]+)?\s*\{)")
TYPE_RE = re.compile(r"\b(class|interface|enum|record)\s+(\w+)")


def java_top_level(src):
    """Return (top_level_methods, top_level_types) at brace depth 0 of stripped source."""
    s = strip_java(src)
    if s.startswith("#!"):
        s = s.split("\n", 1)[1] if "\n" in s else ""
    depth = 0
    starts = []          # (pos, depth) of each '{'
    depth_at = [0] * (len(s) + 1)
    for i, ch in enumerate(s):
        depth_at[i] = depth
        if ch == "{":
            depth += 1
        elif ch == "}":
            depth = max(0, depth - 1)
    methods = []
    for m in METHOD_RE.finditer(s):
        pos = m.start(1)
        if depth_at[pos] == 0:
            sig = m.group(1)
            if TYPE_RE.search(sig) or re.match(r"\s*(if|for|while|switch|catch|synchronized|try|else|do|new|return)\b", sig):
                continue
            methods.append({"name": m.group(2), "signature": " ".join(sig.split())[:120],
                            "line": s.count("\n", 0, pos) + 1})
    types = []
    for m in TYPE_RE.finditer(s):
        if depth_at[m.start()] == 0:
            types.append({"kind": m.group(1), "name": m.group(2), "line": s.count("\n", 0, m.start()) + 1})
    mains = []
    for m in re.finditer(r"((?:public\s+|static\s+|final\s+)*)void\s+main\s*\(([^)]*)\)", s):
        mains.append({"static": "static" in m.group(1), "params": m.group(2).strip(),
                      "depth": depth_at[m.start()], "line": s.count("\n", 0, m.start()) + 1})
    return methods, types, mains, s


DEP_GRADLE_RE = re.compile(r"^\s*(implementation|api|runtimeOnly|compileOnly|annotationProcessor)\s*[\( ]\s*['\"]([^'\"]+)['\"]", re.M)
POM_DEP_RE = re.compile(r"<dependency>(.*?)</dependency>", re.S)
AOT_RE = re.compile(r"-XX:(AOTCache|AOTCacheOutput|AOTMode|AOTConfiguration|SharedArchiveFile|\+AutoCreateSharedArchive|ArchiveClassesAtExit)")
NATIVE_RE = re.compile(r"native-image|org\.graalvm\.buildtools|native-maven-plugin|nativeCompile|graalvmNative")


def java_style(root, files, launch, run_command):
    ev = {}
    paths = {f["path"] for f in files}
    java_src = [f for f in files if f["kind"] == "source" and f["lang"] == "java"]
    texts = {f["path"]: read_text(os.path.join(root, f["path"])) or "" for f in java_src}
    build_texts = {}
    for f in files:
        if f["kind"] == "build" and not f["path"].endswith(".jar"):
            build_texts[f["path"]] = read_text(os.path.join(root, f["path"])) or ""
    script_texts = {f["path"]: read_text(os.path.join(root, f["path"])) or ""
                    for f in files if f["kind"] == "source" and f["lang"] == "shell"}

    # Build
    builds = []
    if any(os.path.basename(p) == "pom.xml" for p in paths):
        builds.append("Maven"); ev["build"] = [p for p in paths if p.endswith("pom.xml")]
    if any(os.path.basename(p).startswith(("build.gradle", "settings.gradle")) for p in paths):
        builds.append("Gradle"); ev.setdefault("build", []).extend(p for p in paths if "gradle" in os.path.basename(p) and p.endswith((".gradle", ".kts")))
    jbang_markers = [p for p, t in texts.items() if re.search(r"^//(DEPS|JAVA|SOURCES|FILES|REPOS|JAVA_OPTIONS|RUNTIME_OPTIONS|COMPILE_OPTIONS|PREVIEW)\b|^///usr/bin/env jbang", t, re.M)]
    if jbang_markers or re.search(r"(^|[\s/])jbang\b", run_command or "") or "jbang-catalog.json" in paths \
            or (launch.get("entry") and "jbang" in (launch.get("java_opts") or "")):
        builds.append("JBang"); ev.setdefault("build", []).extend(jbang_markers or ["jbang in run command"])
    for p, t in build_texts.items():
        name = os.path.basename(p)
        if name in ("Makefile", "makefile") and re.search(r"\bjavac\b|\bjar\b", t):
            builds.append("Make"); ev.setdefault("build", []).append(p)
        elif name.endswith(".sh") and re.search(r"\bjavac\b", t):
            builds.append("build script"); ev.setdefault("build", []).append(p)
    build = "+".join(builds) if builds else "none"

    # Launch
    launch_v = launch.get("launch") or "unknown"
    ev["launch"] = launch.get("evidence", []) + launch.get("evidence_inner", [])
    if launch.get("wrapper"):
        ev["launch"].append(f"via wrapper script {launch['wrapper']}")

    # Source: compact if a main-bearing file declares a method at depth 0
    compact_files, main_ev, module_imports, instance_main = [], [], [], []
    for p, t in texts.items():
        methods, types, mains, s = java_top_level(t)
        if methods:
            compact_files.append(p)
        for m in mains:
            main_ev.append(f"{p}:{m['line']} {'static ' if m['static'] else ''}void main({m['params']}) depth={m['depth']}")
            if not m["static"]:
                instance_main.append(p)
        for m in re.finditer(r"^\s*import\s+module\s+([\w.]+)\s*;", s, re.M):
            module_imports.append(f"{p}: import module {m.group(1)}")
    source = "compact source" if compact_files else "classic"
    ev["source"] = main_ev + [f"top-level method in {p}" for p in compact_files]

    imports = "module imports" if module_imports else "classic"
    ev["imports"] = module_imports

    files_v = "single file" if len(java_src) == 1 else ("multiple files" if java_src else "none")
    ev["files"] = sorted(texts)

    deps = []
    for p, t in texts.items():
        for m in re.finditer(r"^//DEPS\s+(.+)$", t, re.M):
            deps += [d for d in m.group(1).split() if ":" in d]
    for p, t in build_texts.items():
        if p.endswith("pom.xml"):
            for m in POM_DEP_RE.finditer(t):
                body = m.group(1)
                if re.search(r"<scope>\s*test\s*</scope>", body):
                    continue
                g = re.search(r"<groupId>(.*?)</groupId>", body)
                a = re.search(r"<artifactId>(.*?)</artifactId>", body)
                deps.append(f"{g.group(1) if g else '?'}:{a.group(1) if a else '?'}")
        if re.search(r"\.gradle(\.kts)?$", p):
            for m in DEP_GRADLE_RE.finditer(t):
                deps.append(m.group(2))
    dependencies = "one or more" if deps else "none"
    ev["dependencies"] = deps

    opt_hits = []
    hay = {"run command": run_command or "", **build_texts, **script_texts}
    hay.update({p: t.split("\n", 1)[0] for p, t in texts.items()})
    for p, t in hay.items():
        if NATIVE_RE.search(t):
            opt_hits.append(("GraalVM native image", p))
        elif AOT_RE.search(t):
            opt_hits.append(("AOT cache", p))
    if launch_v == "native binary":
        opt_hits.append(("GraalVM native image", "native executable"))
    optimization = opt_hits[0][0] if opt_hits else "none"
    if any(k == "GraalVM native image" for k, _ in opt_hits):
        optimization = "GraalVM native image"
    ev["optimization"] = [f"{k}: {p}" for k, p in opt_hits]

    preview = bool(re.search(r"--enable-preview", (run_command or "") + " ".join(hay.values())))
    return {
        "build": build, "launch": launch_v, "source": source, "imports": imports, "files": files_v,
        "dependencies": dependencies, "optimization": optimization,
        "wrapper_dim": "wrapper script" if launch.get("wrapper") else "none",
        "combination": f"{build} / {launch_v}",
        "java_file_count": len(java_src), "dependency_list": deps, "instance_main_files": instance_main,
        "preview": preview, "wrapper": launch.get("wrapper"), "evidence": ev,
    }


def classify(root, run_command):
    files = inventory(root)
    launch = describe_launch(root, run_command)
    src = [f for f in files if f["kind"] == "source"]
    by_lang = {}
    for f in src:
        by_lang[f["lang"]] = by_lang.get(f["lang"], 0) + f["bytes"]
    dominant = max(by_lang, key=by_lang.get) if by_lang else None
    flags = list(launch.get("flags", []))
    lang = launch.get("lang")
    if launch.get("launch") == "native binary" and lang is None:
        # Compiled binary: language from the sources that built it.
        if any(f["path"].endswith("go.mod") for f in files) or by_lang.get("go"):
            lang = "go"
        elif by_lang.get("rust"):
            lang = "rust"
        elif by_lang.get("java"):
            lang = "java"
        else:
            lang = dominant
    if lang is None:
        lang = dominant
        flags.append(f"language from dominant source ({dominant}); run command did not resolve it")
    if dominant and lang != dominant and not (lang == "java" and launch.get("wrapper")):
        flags.append(f"entry-point language {lang} differs from dominant source language {dominant}")
    if not src:
        flags.append("no source files found")
    res = {
        "language": lang,
        "language_group": ("java" if lang == "java" else "scripting" if lang in SCRIPTING
                           else "compiled-non-java" if lang in COMPILED_NON_JAVA else "other"),
        "entry": launch.get("entry"),
        "launch": launch,
        "source_bytes_by_lang": by_lang,
        "files": files,
        "flags": flags,
        "needs_review": bool(flags),
    }
    if lang == "java":
        res["java_style"] = java_style(root, files, launch, run_command)
    return res


if __name__ == "__main__":
    r = classify(sys.argv[1], sys.argv[2] if len(sys.argv) > 2 else "")
    r.pop("files")
    print(json.dumps(r, indent=2))
