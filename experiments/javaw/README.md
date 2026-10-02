# javaw experiments

Every version of the `javaw` v1 tool (see `../TOOL-PLAN.md`) and the exact prompt that produced it, recorded for the talk (Today section, outline draft 3).

## Usage

```
./generate 01                 # run prompts/01-*.md
./generate 12 --model opus    # same, with a model; result dir gets a --opus suffix
./generate 12 -v 2              # use REQUIREMENTS-v2.md; result dir gets a --v2 suffix
./generate 01 --rerun         # archive the existing result, then run again
./generate 01 --dry-run       # show the command, run nothing
./generate --list             # list prompts
```

Every run sends one composed prompt: the numbered prompt, a `---` separator, then the requirements doc (`REQUIREMENTS-v1.md` by default, `REQUIREMENTS-v2.md` with `-v 2`; identical for every prompt). The script runs `claude -p` with that on stdin and the working directory set to `results/<prompt-name>/`, so everything the agent writes lands there.

So a prompt only says how to build the tool (language, build style, Java options, goal framing), never what the tool does. Each requirements doc is self-contained, since only one is sent. Edit one and its SHA-256 (recorded in each run's `meta.txt`, along with the file name) keeps older results distinguishable.

| Doc | Content |
|---|---|
| `REQUIREMENTS-v1.md` | Today: the basic generator (source, jar, classpath, basic options, naming, CLI) |
| `REQUIREMENTS-v2.md` | Tomorrow: everything in v1 plus build on first launch, AOT cache, dogfooding (Must), dependency adapters, wrapper-side fetch, profiles (Nice), native mode (Stretch), taken from TOOL-PLAN.md |

## Installing an implementation

```
./install 01 -m haiku           # ~/.local/bin/javaw -> results/01-generate-javaw--haiku/javaw
./install 12 -m opus -v 2       # takes the same -m and -v as generate
./install 03 -m sonnet -n javaw-js   # another link name, to keep several side by side
./install 05 -e build/javaw     # name the executable when it can't be found automatically
```

`install` asks `generate` for the result directory (`generate --print-dir`), so the path always matches what `generate` created. It looks at the top level for an executable named `javaw`, then `javaw.*`, then a single executable file, and replaces any existing file or link at the destination. Implementations that find sibling files through `$0` instead of the resolved path may break when run through the link.

## Layout

```
javaw-experiments/
  generate                      launcher
  REQUIREMENTS-v1.md            shared requirements, appended to every prompt (default)
  REQUIREMENTS-v2.md            Version 2 requirements, selected with -v 2
  prompts/NN-name.md            the variation (language, options, goal framing)
  results/NN-name[--MODEL][--vN]/ the agent's working directory: the generated tool
    .generate/
      prompt.md                 the composed prompt, exactly as sent to claude
      meta.txt                  prompt, requirements and composed SHA-256s, model, claude and java versions, command, timing, exit status, cost and turns
      transcript.jsonl          full stream-json transcript of the session
      stderr.log
      result.md                 the agent's final message (needs jq)
  results/NN-name.prev-STAMP/   earlier runs archived by --rerun (never deleted)
```

## Prompts

All prompts get the same requirements appended. Prompts 01-12 are one-liners that differ only in the variation.

| # | Prompt | Variation |
|---|---|---|
| 01 | generate-javaw | No language stated: what does the agent pick? |
| 02 | generate-javaw-go | Go |
| 03 | generate-javaw-javascript | JavaScript |
| 04 | generate-javaw-python | Python |
| 05 | generate-javaw-java | Java |
| 06 | generate-javaw-java-jbang | Java with JBang |
| 07 | generate-javaw-java-no-dependencies | Java, no dependencies |
| 08 | generate-javaw-java-no-build | Java, no build |
| 09 | generate-javaw-java-maven-gradle | Java, full Maven or Gradle build |
| 10 | generate-javaw-java-jdk25 | Java with improvements in JDK 25 |
| 11 | generate-javaw-java-compact-source | Java with compact source files and module imports |
| 12 | generate-javaw-shebang | Java as a single source file run directly with a `java --source` shebang |
| 13 | generate-javaw-java-detailed | The most modern Java possible: JDK 28, preview, shebang launch, compact source, module imports including `jdk.incubator.json` (JEP 540), JBang only if a dependency is needed |
| 14 | generate-javaw-goal | Goal framing, no language, no example |
| 15 | generate-javaw-goal-command | Goal plus an example command |
| 16 | generate-javaw-goal-output | Goal plus the expected output file |

Add a prompt by dropping `prompts/NN-name.md` in; numbers must be unique.

## Notes and caveats

| Topic | Note |
|---|---|
| Permissions | The agent runs unattended. Default arguments: `--permission-mode acceptEdits --allowedTools Read,Write,Edit,Glob,Grep,Bash`. Bash is unrestricted, so the agent can run any shell command as you. Override with `JAVAW_CLAUDE_ARGS`. |
| Isolation | Claude Code still loads your user-level `~/.claude/CLAUDE.md`, settings, and any CLAUDE.md in parent directories of `results/`. For a fair comparison, record what was loaded, or pass extra arguments after `--` to restrict it. |
| Reproducibility | A model run is not deterministic. `meta.txt` records the model setting, versions, and prompt hash; the transcript is the real record. |
| Reruns | Never overwrite: `--rerun` moves the old directory aside first. |
| Prompt 15 | The expected-output example is my reading of the "wrapper basics" row in TOOL-PLAN.md. Edit it if you want a different shape. |
| Earlier runs | Results made before the requirements doc existed (the first Haiku run of prompt 01) used the old standalone prompt. Re-run with `--rerun` to archive them and redo under the shared requirements. |
