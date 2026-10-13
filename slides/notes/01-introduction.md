# Introduction

Experiment design, data and results: `experiments/intro-scorecard/`.

## Method

| Item | Value |
|---|---|
| Tasks | `logsum`, `treesum`, `jsonpick`, `jarsrc` (specs adapted from `experiments/llm-cli-bakeoff/spec/`, with references to languages removed) |
| Models | Haiku, Sonnet, Opus. Results are reported pooled across models; the per-model breakdown is supporting detail only. |
| Condition A (default) | The task prompt with no language or output requirements. 3 runs per task per model (36 runs). |
| Condition B (Java) | The same prompt followed by "Write it in Java." 4 runs per task per model (48 runs). |
| Baselines | Go, Python and Node implementations of the same tasks from the bake-off, plus the non-Java programs produced in condition A. No additional runs. |
| Verdicts | Each expectation is evaluated against a 95% confidence interval (Wilson). It is **supported** if the interval lies entirely on the expected side of the threshold, **not supported** if it lies entirely on the other side, and **inconclusive** otherwise. |

## Hypothesis 1

**Hypothesis**

When asked to build a CLI tool without any output requirements, AI rarely produces Java.

**Expectations**

* Python is produced in at least 50% of runs.
* A scripting language (Python, JavaScript, TypeScript or a shell script) is produced in at least two-thirds of runs.
* A compiled language other than Java (for example Go or Rust) is produced in at most 25% of runs.
* Java is produced in fewer than 10% of runs.

**Definitions**

* Language: the language of the program's entry point.

**Conclusion**

Java is not the default choice for AI when writing CLI tools.

## Hypothesis 2

**Hypothesis**

Asking for Java is sufficient to overcome the model's language bias.

**Expectations**

* Appending "Write it in Java." to the prompt produces Java in every run.
* At least 85% of those runs pass the task's conformance tests.

**Prior evidence**

* Bake-off pilot (`logsum` only, Haiku and Sonnet): 18 of 18 runs produced Java and 17 of 18 passed. That prompt also required a single file, so condition B repeats the measurement without that constraint.

**Conclusion**

The barrier to getting AI to write Java is near zero. The resulting programs vary, however (see Hypothesis 3).

## Hypothesis 3

**Hypothesis**

When asked for Java, the output is significantly inconsistent.

**Expectations**

* At least four distinct style combinations are produced.
* No single style combination accounts for more than two-thirds of runs.

**Definitions**

| Dimension | Values |
|---|---|
| Build | none, Maven, Gradle, JBang, Make, build script |
| Launch | source launch (`java Tool.java`), shebang, `javac` + `java`, jar. For a program run through a wrapper script, the launch the wrapper performs. |
| Wrapper | none, wrapper script |
| Source | classic, compact source |
| Imports | classic, module imports |
| Files | single file, multiple files |
| Dependencies | none, one or more |
| Optimization | none, AOT cache, GraalVM native image |

* Style combination: the pair (build, launch). The remaining dimensions are reported alongside it.

**Discussion point: dependencies**

Dependency use is expected to vary by task and is not evaluated as an expectation. `jsonpick` is the exception worth reporting: the JDK has no JSON parser, so each run either adds a dependency or writes its own parser. The trade-off between the two (source size against disk size and setup) frames Hypothesis 4.

**Conclusion**

The style of generated Java scripts varies from run to run and from model to model, so the same prompt produces unpredictable output.

## Hypothesis 4

**Hypothesis**

Generated Java programs lose to other languages in most performance and cost categories.

**Expectations**

* Startup: every unoptimized Java program starts more slowly than the median Go, Python and Node programs for the same task.
* Source size: the median Java program is more than 25% larger, in tokens, than the median of the smallest competing language for the same task.
* Disk: Java programs that use dependencies occupy more disk space than the median competing program for the same task.
* Optimization: with optimization applied, median Java startup is within twice the median Python and Node startup.

**Definitions**

* Unoptimized: launched as the generated program instructs.
* Optimization: the program packaged as a jar and run with an AOT cache. Applied by the harness, not by the model.
* Source size: tokens in the program's source files, excluding tests and build output.
* Disk: source, build output and resolved dependencies, excluding the language runtime itself.

**Conclusion**

Java does not win any performance or cost category. At best it is comparable to other languages, and only with additional help (optimization, dependencies or extra tokens).
