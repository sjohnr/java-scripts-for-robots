# Outline

## Structure at a glance

| Segment                 | Minutes | Content                                                     |
|-------------------------|---------|-------------------------------------------------------------|
| 1. Hook and origin      | 5       | The abstract's question, then your story                    |
| 2. Yesterday            | 5       | Java scripting before JEP 330                               |
| 3. Today: what works    | 12      | Launch modes, startup numbers, live demo                    |
| 4. Today: what it costs | 8       | AOT cache size, the stale-cache bug, JSON gap               |
| 5. Model matters        | 6       | Your Netflix experience, backed by the Sonnet vs Haiku data |
| 6. Tomorrow             | 5       | JEP 540, the ja project, your JDK wishlist                  |
| 7. Close                | 4       | Scorecard recap, Monday checklist, questions                |

### Hook and origin (about 5 min)

| Beat                              | Content                                                                                                |
|-----------------------------------|--------------------------------------------------------------------------------------------------------|
| Open with the abstract's question | Ask AI to build a CLI, it writes Python. Fact about AI or fact about Java?                             |
| Your story                        | Building small CLIs for agents, they came back in Python, fast, and you wondered how far Java could go |
| Promise                           | Measured, not vibes: four languages, real numbers, an honest scorecard including where Java loses      |

### Yesterday (about 5 min)

| Beat              | Content                                                                         |
|-------------------|---------------------------------------------------------------------------------|
| The old story     | A Java "script" meant a build file and a compile step                           |
| The turning point | JEP 330 (JDK 11): java Tool.java, no explicit javac                             |
| Extended          | JEP 458 (JDK 22): a directory of files, still no build step                     |
| Bridge            | That closed the ceremony gap. Did it close the performance gap? That's "today." |

### Today: what works (about 12 min)

| Beat               | Content                                                                                                            |
|--------------------|--------------------------------------------------------------------------------------------------------------------|
| The bake-off       | Same 5 tools, 4 languages, 227/227 conformance before any benchmark                                                |
| Live demo 1        | time java Tool.java vs time java -jar vs AOT-cached jar, same program three ways                                   |
| Startup numbers    | Go and native at 1-2ms, packaged Java (jar, tuned, AOT) at 16-19ms, Python at 12.7ms, source-launch at 168ms+      |
| The honest framing | Naive script mode is 80-150x slower than Go. Package it and cache it, and it's in Node's tier and close to Python. |
| Code size          | Java ties or wins on 3 of 5 tools using records, sealed interfaces, and pattern matching                           |
| JEP call-outs      | 512 and 511 (compact source, module imports), both final in JDK 25                                                 |

### Today: what it costs (about 8 min)

| Beat                  | Content                                                                                                                                                            |
|-----------------------|--------------------------------------------------------------------------------------------------------------------------------------------------------------------|
| Live demo 2           | Break the AOT cache on stage: rebuild the jar, rerun with -Xlog:aot, show the silent fallback that's slower than no cache at all, then rebuild and recover         |
| The lesson            | A cache mismatch degrades silently. No crash, no error, just quietly slower.                                                                                       |
| Disk cost             | A 10MB+ AOT cache for an 800-byte jar, mostly JDK class data, not app code                                                                                         |
| JSON gap              | No JSON in the JDK. Hand-rolled costs about double the tokens of Python or Node. JBang plus Jackson closes it, at the cost of a dependency and a noisy cold start. |
| Source-launch and AOT | They don't mix. AOT indexes a classpath; source-launch never touches one.                                                                                          |

### Model matters (about 6 min)

| Beat                    | Content                                                                                                                                                                                                                         |
|-------------------------|---------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------|
| Your Netflix experience | Model choice affects instruction-following, told as your own observation                                                                                                                                                        |
| The experiment          | Same steering snippet, Haiku vs Sonnet, scored against a 10-rule compliance checklist                                                                                                                                           |
| What held up            | Bare "write it in Java" got both models to pass, every time                                                                                                                                                                     |
| What didn't replicate   | Haiku got worse with more steering; Sonnet didn't                                                                                                                                                                               |
| The real finding        | The snippet's filename instruction was ambiguous (implied by an example, not stated). Sonnet complied with an explicit instruction and missed an implicit one, every time. That's the lesson: steer explicitly, not by example. |
| The honest caveat       | Small sample, say so on stage                                                                                                                                                                                                   |

### Tomorrow (about 5 min)

| Beat          | Content                                                                                                                                         |
|---------------|-------------------------------------------------------------------------------------------------------------------------------------------------|
| JEP 540       | Simple JSON API, incubating in a future JDK, mention its module and status as of your last check                                                |
| ja            | Your coworker Danny's project: dependency declaration from module-info.java, no JBang needed. Name it, don't demo it, since it's not your work. |
| Your wishlist | Whatever you personally want to see land: AOT for source-launch, JSON in java.base rather than incubator, others you pick                       |

### Close (about 4 min)

| Beat             | Content                                                                                                  |
|------------------|----------------------------------------------------------------------------------------------------------|
| Scorecard        | One slide: done, costs-you, still-missing, as three short columns                                        |
| Monday checklist | 3-4 things someone can try this week: java Tool.java, the AOT one-step command, a compact-source rewrite |
| Questions        |                                                                                                          |
