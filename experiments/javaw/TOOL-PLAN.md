# javaw: Tool Plan

`javaw` (working name: `java-wrapper`) is the single tool that drives the Today and Tomorrow sections of the talk. It is a Java program that generates a pure POSIX `sh` launcher script for a Java program. The generated wrapper is the product. `javaw` is never in the launch path.

## Core idea

| Principle | Detail |
|---|---|
| One job | `javaw` generates wrapper scripts. It does not build, bundle, or launch programs itself. |
| Java stays out of the loop | A Java tool that launched Java programs would be Java launching Java. Generating code once removes the generator from every later run. |
| Generated code only | The wrapper contains only what the chosen options require. No unused options, no resolution logic. |
| Distribution model | Check out the repo and run the wrapper. Nothing else to install beyond the JDK. |
| Honest claims | "Near-zero wrapper overhead" and "avoids known pitfalls", not "perfect" or "zero latency". The wrapper still starts a JVM. |

## Decisions

| # | Decision |
|---|---|
| 1 | The bake-off tools are throwaway: they introduce the idea and the hook, and the talk is not about them. `javaw` is the single Today/Tomorrow tool. |
| 2 | Profiles configure `javaw` (generation time), not the wrapper. They may be downloaded from GitHub by `javaw`. The wrapper never downloads profiles. |
| 3 | The wrapper is POSIX `sh`. Avoid bash-only features (arrays, `[[ ]]`, process substitution). |
| 4 | Windows is out of scope. Say so on a slide. |
| 5 | No new dependency format. `javaw` reads existing conventions and produces a flat classpath. |
| 6 | No fat jar and no bundle verb. The tool stays single-purpose. |
| 7 | The only download a wrapper may do is individual jars from Maven Central: HTTPS only, pinned SHA-256, one generated line per jar. Private repositories are out of scope. |
| 8 | Downloaded jars live in a `lib/` directory next to the wrapper (git-ignored), so the checkout is self-contained. |
| 9 | Dependency resolution (including transitives) happens at generation time and is delegated to existing tools (Maven or JBang). The wrapper receives a finished, flat list. |
| 10 | In the Tomorrow version, the wrapper builds a jar from source on first launch using only JDK tools (`javac`, `jar`), so source programs can use the AOT cache. This adds no dependency beyond the JDK, which is already required. Dependencies are not bundled; they stay on the classpath. |
| 11 | Output naming is explicit and predictable: a default name derived from the input, an `-o` override, and as many wrappers as needed. See "Output and naming". |

## Output and naming

| Topic | Behavior |
|---|---|
| Default name | `run-` plus the input's base name (extension removed) converted to kebab-case. `Tool.java` and `tool.jar` both become `run-tool`. See the conversion rules below. |
| Default location | The current directory |
| Override | `-o NAME` / `--output NAME` sets the wrapper's file name (and directory, if a path is given) |
| Output directory | If `-o` ends in `/` or names an existing directory, wrappers are written there using their default names |
| Multiple wrappers | Run `javaw` as many times as needed. Passing several inputs in one invocation (`javaw A.java B.java`) writes one wrapper per input, each with its default name. `-o` with several inputs must be a directory. |
| Overwriting | Refuse to overwrite an existing file unless `--force` is given. This also protects against default-name collisions: `Tool.java` and `tool.jar` in the same directory both map to `run-tool`, so use `-o` for one of them. |
| Permissions | Written with the executable bit set |
| Header comment | Each wrapper starts with a comment recording the `javaw` version and the exact options used, so it can be regenerated |
| Relocatable | The wrapper locates files relative to its own directory, never relative to its name, so renaming or moving it with its siblings still works |

Kebab-case conversion rules:

| Rule | Example |
|---|---|
| Split at a lowercase-to-uppercase boundary | `FooBar` becomes `foo-bar` |
| Split an acronym from the following word | `HTTPServer` becomes `http-server` |
| Treat `_`, `.`, and spaces as separators | `example1_assert` becomes `example1-assert` |
| Keep digits attached to the preceding word | `Example1` becomes `example1` |
| Collapse repeated separators and lowercase everything | `Foo__Bar` becomes `foo-bar` |
| Already-kebab input is unchanged apart from the prefix | `my-tool.jar` becomes `run-my-tool` |

Files the wrapper creates next to itself must not collide when several wrappers share a directory:

| Artifact | Sharing rule |
|---|---|
| `lib/` (downloaded jars) | Shared. Jars are keyed by their coordinates and checksum, so wrappers with common dependencies reuse them. |
| Built jar (Tomorrow) | Per wrapper, named from the wrapper (for example `.NAME/NAME.jar`) |
| AOT cache (Tomorrow) | Per wrapper, named from the wrapper, so one wrapper's rebuild never invalidates another's cache |
| Native binary (Tomorrow) | Per wrapper, same naming rule |

## Version 1 (Today): basic generator

Goal: small enough that the whole generator and a generated wrapper can be read on screen.

| Feature | Behavior | Gap or quirk it exposes |
|---|---|---|
| Source-launch wrapper | `javaw Tool.java` writes an executable script running `exec java Tool.java "$@"` | Launch modes; a generated shim as the first fix |
| Jar mode | `--jar tool.jar` writes `exec java -jar tool.jar "$@"` | Source launch vs. packaged startup (168ms vs. 16-19ms in the bake-off) |
| Classpath | `--classpath lib/*` records the given paths as-is. Local only, not portable, and the slide says so. | The dependency gap |
| Basic options | `--java-home`, `--jvm-opts`, minimum Java version check with a clear error message | Wrong JDK on PATH |
| Output naming | Default name from the input; `-o` to override (see "Output and naming") | Predictable names when generating more than one wrapper |
| Wrapper basics | Shebang, resolves its own directory, `chmod +x` on creation | None, but it makes the output feel real |
| Generator CLI | Argument parsing and `--help` text | CLI parsing and help text gaps |
| Generator code | Text blocks for templates; tests with assertions | Java features that work well |

Size target: roughly 100-150 lines of Java.

## Version 2 (Tomorrow): expanded generator

| Feature | Behavior | Wish list item it answers |
|---|---|---|
| Dependency adapters | JBang `//DEPS` first (POM as follow-up). Resolved at generation time to a flat list; baked into the wrapper as download lines with pinned SHA-256. | Zero-tooling dependencies |
| Wrapper-side fetch | For each missing jar: `curl` over HTTPS, verify checksum, write to a temp file, `mv` into `lib/`. Fast path: completion marker file. | Zero-tooling dependencies |
| Build on first launch | For a `.java` source, the wrapper compiles and packages a jar on first run with `javac` and `jar`, then launches from the jar | AOT for source-launch |
| Cache-safe AOT mode | Wrapper stores a stamp derived from its own baked contents (including checksums) plus the JDK version. On mismatch it rebuilds the cache; it never silently falls back. | Fail fast on AOT cache mismatch |
| On-demand cache creation | First run creates the AOT cache; later runs use it | AOT for source-launch |
| Safe cache handling | Atomic writes; concurrent first runs cannot corrupt the cache | Launch consistency |
| Native mode | `--native` builds with GraalVM `native-image` on first use if installed, caches the result, falls back to the jar if not | Startup time for small programs |
| Profiles | `javaw --profile fast-start` loads opinionated generation options, optionally downloaded from GitHub (pinned to a commit) and cached for offline use | Opinionated defaults |
| Dogfooding | `javaw javaw.jar` generates the wrapper that launches `javaw` itself | Shows the Java-launching-Java problem solved with its own output |

Suggested profiles: `fast-start`, `small-disk`, `max-perf`, `portable`.

## Wrapper contents by mode

| Mode | What the wrapper contains |
|---|---|
| Source (v1) | Shebang, own-directory resolution, JDK check, `exec java Tool.java "$@"` |
| Jar (v1) | Same, with `-jar` and optional JVM options |
| Classpath (v1) | Adds `-cp` with the paths as given |
| Deps (v2) | Adds the download block, checksum verification, and `lib/` classpath |
| Build-on-first-launch (v2) | Adds the compile and package step with a staleness check |
| AOT (v2) | Adds the stamp check, cache build, and launch flags |
| Native (v2) | Adds the native-image build step with jar fallback |

## Cut line for prep

| Tier | Features |
|---|---|
| Must demo | Cache-safe AOT mode, on-demand creation, build on first launch, dogfooding |
| Nice | Dependency adapters with wrapper-side fetch, profiles from GitHub (pre-cache and keep a recorded fallback for the network) |
| Stretch | Native mode (pre-bake the build) |

## Talk mapping

| Talk beat | Plan element |
|---|---|
| Today: live demo of gaps and quirks | Building v1 exposes argument parsing, help text, assertions, text blocks; the dependency gap appears at `--classpath` |
| Today: launch modes and tradeoffs | Source, jar, and AOT wrappers side by side; stale cache described and shown on a slide rather than a live demo |
| Today: scorecard | `javaw` compared to equivalents in Go, Python, and Node (text generation is easy in all of them; startup is the difference) |
| Tomorrow: quality of life | v2 features above, introduced one flag at a time |
| Tomorrow: wish list | Each v2 feature maps to a wish list item; the tool is a workaround that should not need to exist |
| Conclusion | Dogfooding as the closing demo |

## Known limits (state them on a slide)

| Limit | Note |
|---|---|
| Windows | Not supported |
| Private repositories | Not supported; Maven Central only |
| Offline first run | Fails with a clear error |
| Transitive resolution | Delegated to Maven or JBang at generation time |
| Wrapper still starts a JVM | Wrapper overhead is small but not zero |
| Wrapper-side build | Uses only the JDK, which is already required |

## Open questions

| Question | Lean |
|---|---|
| Does "build on first launch" stay in the "launch, not build" spirit? | Yes if it uses only `javac` and `jar` and produces one jar of the program's own classes; say so plainly in the talk |
| How is the build invalidated when sources change? | Compare source modification times or a content hash in the stamp |
| "Why not JBang?" | JBang is a runtime launcher with its own dependency; `javaw` generates a launcher and then gets out of the way |
| `curl` missing | Fail with a clear message naming the tool |
| Should the wrapper work when invoked through a symlink? | Yes if cheap: resolve the link before finding the own directory. Otherwise document it as unsupported. |
| Should the `run-` prefix be configurable (for example via a profile)? | Not for v1. Use `-o` for a different name. |
