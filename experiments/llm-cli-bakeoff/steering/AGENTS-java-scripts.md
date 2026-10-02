# Steering snippet for LLM-written modern Java scripts (PROVISIONAL, PILOT EVIDENCE)

Status: updated after a live Phase 5 pilot (see `results/agent/summary.md`), replacing the original static-analysis-only draft. **Pilot, low N**: 3 trials per condition, one model (claude-haiku-4-5-20251001). The snippet below is condition **B-c** from that pilot. Treat this as provisional, directionally-informative evidence, not a validated, high-confidence recommendation -- and note the pilot's own most interesting finding: B-c (this snippet) passed conformance in only 2/3 trials and took far more turns (median 23) than the plainer B-b condition ("just write it in Java", no snippet, 3/3 pass, median 4 turns). More steering did not clearly help in this pilot; it may have made the model second-guess itself into more turns without preventing its one real mistake. A larger-N rerun with this same harness (`bench/agent/run_experiment_b.py`) is the natural next step before trusting this snippet over the plain instruction.

**Phase 5b update (PROVISIONAL, PILOT EVIDENCE, N=3):** a follow-up pilot on a stronger model (claude-sonnet-5, see `results/agent-sonnet/summary.md`) tested this exact snippet (same SHA-256, `ed5df3098228fff06fa7c498cc7bd1cde4a20372daa3db1e0533bb485caac672`) against a new, more conservative alternative -- condition B-e below -- that mandates classic `public static void main` and explicitly forbids compact source. On Sonnet, both conditions passed conformance 3/3, but B-e clearly beat B-c on every other measured axis: **10/10 instruction-compliance rules passed on all 3 B-e trials** (vs. 9/10, 9/10, 8/10 for B-c, which failed the filename rule -- `Tool.java` -- on all 3 trials, because this snippet only implies the filename via an example command rather than stating it directly), fewer median turns (8 vs. 15), and lower median cost ($0.2506 vs. $0.4249). **If you want the filename instruction followed reliably, prefer the B-e conservative snippet below, or add an explicit "name the file Tool.java" sentence to this one** -- that hybrid was not tested. This is still N=3 on one stronger model; not a validated recommendation to abandon the compact-source style, just a directionally clear result worth acting on provisionally.

## Alternative: B-e conservative snippet (classic main, no compact source)

```
Write it in Java, targeting JDK 25, as a single file named Tool.java, run with `java Tool.java`.
Use the classic style only: `public class Tool { public static void main(String[] args) { ... } }` with a normal `public static void main(String[] args)` entry point.
Do NOT use the JDK 25 compact-source-file style: no top-level `void main`, no instance main, no `import module java.base;`, no `IO.println`/`IO.readln`. Use ordinary imports (e.g. `import java.util.*;`) and `System.out`/`System.err` as usual.
No build files (no pom.xml, build.gradle, settings.gradle). Stdlib only, no external dependencies.
Do not pass `--enable-preview`, `--source`, or any preview flag when compiling or running.
```

SHA-256 of the snippet above: `b5d4a869656f7bbda2344da7b5eb9db1f266a55628f69733430df0048f670d59`.

## What to tell the model

```
Write this in Java 25, as a single source file, run directly with
`java Tool.java` -- no Maven, no Gradle, no build step, no pom.xml or
build.gradle. Use the JDK 25 compact-source-file style: no
`public class X { public static void main(String[] args) }`
boilerplate -- write a top-level `void main(String[] args)` (or
`void main()` if you don't need args) with no enclosing class
declaration, and use `IO.println` / `IO.readln` from `java.lang.IO`
instead of `System.out.println`. If you need more than a few JDK
classes, add `import module java.base;` at the top instead of
individual imports.

Do not use `--enable-preview` or `--source` flags -- compact source
files, instance main methods, and module import declarations are all
FINAL, non-preview features as of JDK 25 (JEP 512, JEP 511). Do not
add them; they will cause an "unnecessary flag" style error on a JDK
that doesn't recognize them, and they are not needed on JDK 25+.

Stdlib only. Java has no JSON parser in the standard library -- if
you need one, write a minimal hand-rolled recursive-descent parser
rather than assuming `javax.json` or similar exists (it does not,
outside of JSON-P which is not in the JDK either).

If you reference a helper method defined in the same file from inside
a lambda or method reference, do NOT use `ClassName::method` -- inside
a compact source file, a reference to the file's own implicit class by
name does not resolve. Use `(args) -> method(args)` or `this::method`
instead.
```

## Catalog of recurring mistakes

From this project's own hand-authoring (not live-trial data):

| # | Mistake | Frequency (hand-authored) | Why it happens |
|---|---|---|---|
| 1 | `ClassName::method` inside a compact source file's own implicit class fails to compile | 2/2 times attempted | The implicit class has no accessible name from inside a method-reference expression at this JDK's current implementation |
| 2 | Reaching for `-XX:AOTCache=` on a source-launch (`java Tool.java`) invocation, expecting it to help | 1/1 | AOT caches are built against a classpath; source-launch compiles in-memory and never touches one |
| 3 | Assuming an unchanged AOT cache flag will always be fast, without checking `-Xlog:aot` after a rebuild | 1/1 | A stale/mismatched cache degrades silently to a slower fallback rather than erroring |

From the live Phase 5 pilot (9 Java runs total: B-b x3, B-c x3, B-d x3, model claude-haiku-4-5-20251001; see `results/agent/summary.md` for full detail):

| # | Mistake | Count / 9 | Example |
|---|---|---:|---|
| 4 | Unhandled checked exception in a compact-source `void main(String[] args)` (missing `throws IOException`) | 1/9 | B-c trial 1: `Tool.java:8: error: unreported exception IOException; must be caught or declared to be thrown` -- a helper method throwing `IOException` was called from `main` without `main` itself declaring `throws IOException`. The classic `public static void main(String[] args) throws IOException` signature makes this visually obvious; the shorter compact-source form does not carry the same cue. |

No other compile errors or conformance failures occurred across the other 8 Java runs in the pilot -- this table is intentionally short given the N.

From the Phase 5b Sonnet pilot (9 Java runs total: B-b x3, B-c x3, B-e x3, model claude-sonnet-5; see `results/agent-sonnet/summary.md`):

| # | Mistake | Count / 9 | Example |
|---|---|---:|---|
| 5 | Public class name doesn't match the filename it was saved as | 1/9 | B-b trial 2: `logsum.java:13: error: class Logsum is public, should be declared in a file named Logsum.java` -- the model wrote `public class Logsum` into a file it had named `logsum.java`. Self-corrected in the same trial by renaming the file to `Logsum.java`. |

No preview flags, no missing-`throws` issues, and no other compile errors occurred across the other 8 Sonnet Java runs.

## Findings from the live pilot (answers to the original draft's open questions)

- **Does an unsteered model default to Python?** Yes, 3/3 trials (condition B-a, no language stated) -- Haiku chose Python every time for the logsum spec, and got it right (8/8 conformance) all 3 times.
- **Does the snippet reduce turns-to-passing vs. plain "write it in Java"?** No -- the opposite happened in this pilot. Plain "write it in Java" (B-b) passed 3/3 in a median of 4 turns; this snippet (B-c) passed only 2/3 in a median of 23 turns. Sample size is small (3 each), but the direction is the opposite of what steering was expected to do.
- **Does a correction turn ("rewrite in Java" after starting elsewhere) work?** Yes, 3/3 (condition B-d): the model wrote Python first (unsteered), then on a single follow-up turn ("Rewrite it in Java.", same session via `--resume`) produced passing Java each time. Cost was the highest of the four conditions (redoing the whole implementation from scratch in a second language), but it worked reliably in this small sample.
- **Maven/Gradle scaffolding:** not observed in any of the 9 Java runs -- no run reached for a build file despite none of the conditions except B-c explicitly forbidding it.
