# Intro scorecard: summary

Generated 2026-10-11T09:14 from 84 recorded runs (36 condition A, 48 condition B). Pooled across models. Verdict rule: supported if the 95% Wilson interval lies entirely on the expected side of the threshold, not supported if entirely on the other side, otherwise inconclusive. "Every" expectations report exceptions instead and are supported only with zero exceptions.

## Verdicts

| ID | Expectation | Observed | 95% interval | Verdict |
|---|---|---|---|---|
| H1.python | Python is produced in at least 50% of runs. | 36/36 (100%) | 90% - 100% | supported |
| H1.scripting | A scripting language (Python, JavaScript, TypeScript or a shell script) is produced in at least two-thirds of runs. | 36/36 (100%) | 90% - 100% | supported |
| H1.compiled | A compiled language other than Java (for example Go or Rust) is produced in at most 25% of runs. | 0/36 (0%) | 0% - 10% | supported |
| H1.java | Java is produced in fewer than 10% of runs. | 0/36 (0%) | 0% - 10% | supported |
| H2.java | Appending "Write it in Java." to the prompt produces Java in every run. | 48/48 (100%) | 0 exception(s) | supported |
| H2.pass | At least 85% of those runs pass the task's conformance tests. | 40/48 (83%) | 70% - 91% | inconclusive |
| H3.distinct | At least four distinct style combinations are produced. | 5 distinct (n=48) | count, no interval | supported |
| H3.max_share | No single style combination accounts for more than two-thirds of runs. | 22/48 (46%) | 33% - 60% | supported |
| H4.startup.bake-off | Startup: every unoptimized Java program starts more slowly than the median Go, Python and Node programs for the same task. [bake-off] | 40/40 (100%) | 0 exception(s) | supported |
| H4.source.bake-off | Source size: the median Java program is more than 25% larger, in tokens, than the median of the smallest competing language for the same task. [bake-off] | 35/40 (88%) | 74% - 95% | supported |
| H4.disk.bake-off | Disk: Java programs that use dependencies occupy more disk space than the median competing program for the same task. [bake-off] | n/a | n/a | inconclusive (n=0) |
| H4.optimization.bake-off | Optimization: with optimization applied, median Java startup is within twice the median Python and Node startup. [bake-off] | 40/40 (100%) | 91% - 100% | supported |
| H4.startup.condA | Startup: every unoptimized Java program starts more slowly than the median Go, Python and Node programs for the same task. [condition A] | 40/40 (100%) | 0 exception(s) | supported |
| H4.source.condA | Source size: the median Java program is more than 25% larger, in tokens, than the median of the smallest competing language for the same task. [condition A] | 38/40 (95%) | 83% - 99% | supported |
| H4.disk.condA | Disk: Java programs that use dependencies occupy more disk space than the median competing program for the same task. [condition A] | n/a | n/a | inconclusive (n=0) |
| H4.optimization.condA | Optimization: with optimization applied, median Java startup is within twice the median Python and Node startup. [condition A] | 40/40 (100%) | 91% - 100% | supported |

## Per-model breakdown (supporting detail)

### haiku (claude-haiku-5-5)

| ID | Expectation | Observed | 95% interval | Verdict |
|---|---|---|---|---|
| H1.python | Python is produced in at least 50% of runs. | 12/12 (100%) | 76% - 100% | supported |
| H1.scripting | A scripting language (Python, JavaScript, TypeScript or a shell script) is produced in at least two-thirds of runs. | 12/12 (100%) | 76% - 100% | supported |
| H1.compiled | A compiled language other than Java (for example Go or Rust) is produced in at most 25% of runs. | 0/12 (0%) | 0% - 24% | supported |
| H1.java | Java is produced in fewer than 10% of runs. | 0/12 (0%) | 0% - 24% | inconclusive |
| H2.java | Appending "Write it in Java." to the prompt produces Java in every run. | 16/16 (100%) | 0 exception(s) | supported |
| H2.pass | At least 85% of those runs pass the task's conformance tests. | 14/16 (88%) | 64% - 97% | inconclusive |
| H3.distinct | At least four distinct style combinations are produced. | 5 distinct (n=16) | count, no interval | supported |
| H3.max_share | No single style combination accounts for more than two-thirds of runs. | 7/16 (44%) | 23% - 67% | inconclusive |
| H4.startup.bake-off | Startup: every unoptimized Java program starts more slowly than the median Go, Python and Node programs for the same task. [bake-off] | 14/14 (100%) | 0 exception(s) | supported |
| H4.source.bake-off | Source size: the median Java program is more than 25% larger, in tokens, than the median of the smallest competing language for the same task. [bake-off] | 14/14 (100%) | 78% - 100% | supported |
| H4.disk.bake-off | Disk: Java programs that use dependencies occupy more disk space than the median competing program for the same task. [bake-off] | n/a | n/a | inconclusive (n=0) |
| H4.optimization.bake-off | Optimization: with optimization applied, median Java startup is within twice the median Python and Node startup. [bake-off] | 14/14 (100%) | 78% - 100% | supported |
| H4.startup.condA | Startup: every unoptimized Java program starts more slowly than the median Go, Python and Node programs for the same task. [condition A] | 14/14 (100%) | 0 exception(s) | supported |
| H4.source.condA | Source size: the median Java program is more than 25% larger, in tokens, than the median of the smallest competing language for the same task. [condition A] | 14/14 (100%) | 78% - 100% | supported |
| H4.disk.condA | Disk: Java programs that use dependencies occupy more disk space than the median competing program for the same task. [condition A] | n/a | n/a | inconclusive (n=0) |
| H4.optimization.condA | Optimization: with optimization applied, median Java startup is within twice the median Python and Node startup. [condition A] | 14/14 (100%) | 78% - 100% | supported |

### sonnet (claude-sonnet-5-5)

| ID | Expectation | Observed | 95% interval | Verdict |
|---|---|---|---|---|
| H1.python | Python is produced in at least 50% of runs. | 12/12 (100%) | 76% - 100% | supported |
| H1.scripting | A scripting language (Python, JavaScript, TypeScript or a shell script) is produced in at least two-thirds of runs. | 12/12 (100%) | 76% - 100% | supported |
| H1.compiled | A compiled language other than Java (for example Go or Rust) is produced in at most 25% of runs. | 0/12 (0%) | 0% - 24% | supported |
| H1.java | Java is produced in fewer than 10% of runs. | 0/12 (0%) | 0% - 24% | inconclusive |
| H2.java | Appending "Write it in Java." to the prompt produces Java in every run. | 16/16 (100%) | 0 exception(s) | supported |
| H2.pass | At least 85% of those runs pass the task's conformance tests. | 12/16 (75%) | 51% - 90% | inconclusive |
| H3.distinct | At least four distinct style combinations are produced. | 3 distinct (n=16) | count, no interval | not supported |
| H3.max_share | No single style combination accounts for more than two-thirds of runs. | 13/16 (81%) | 57% - 93% | inconclusive |
| H4.startup.bake-off | Startup: every unoptimized Java program starts more slowly than the median Go, Python and Node programs for the same task. [bake-off] | 12/12 (100%) | 0 exception(s) | supported |
| H4.source.bake-off | Source size: the median Java program is more than 25% larger, in tokens, than the median of the smallest competing language for the same task. [bake-off] | 7/12 (58%) | 32% - 81% | inconclusive |
| H4.disk.bake-off | Disk: Java programs that use dependencies occupy more disk space than the median competing program for the same task. [bake-off] | n/a | n/a | inconclusive (n=0) |
| H4.optimization.bake-off | Optimization: with optimization applied, median Java startup is within twice the median Python and Node startup. [bake-off] | 12/12 (100%) | 76% - 100% | supported |
| H4.startup.condA | Startup: every unoptimized Java program starts more slowly than the median Go, Python and Node programs for the same task. [condition A] | 12/12 (100%) | 0 exception(s) | supported |
| H4.source.condA | Source size: the median Java program is more than 25% larger, in tokens, than the median of the smallest competing language for the same task. [condition A] | 12/12 (100%) | 76% - 100% | supported |
| H4.disk.condA | Disk: Java programs that use dependencies occupy more disk space than the median competing program for the same task. [condition A] | n/a | n/a | inconclusive (n=0) |
| H4.optimization.condA | Optimization: with optimization applied, median Java startup is within twice the median Python and Node startup. [condition A] | 12/12 (100%) | 76% - 100% | supported |

### opus (claude-opus-5-5)

| ID | Expectation | Observed | 95% interval | Verdict |
|---|---|---|---|---|
| H1.python | Python is produced in at least 50% of runs. | 12/12 (100%) | 76% - 100% | supported |
| H1.scripting | A scripting language (Python, JavaScript, TypeScript or a shell script) is produced in at least two-thirds of runs. | 12/12 (100%) | 76% - 100% | supported |
| H1.compiled | A compiled language other than Java (for example Go or Rust) is produced in at most 25% of runs. | 0/12 (0%) | 0% - 24% | supported |
| H1.java | Java is produced in fewer than 10% of runs. | 0/12 (0%) | 0% - 24% | inconclusive |
| H2.java | Appending "Write it in Java." to the prompt produces Java in every run. | 16/16 (100%) | 0 exception(s) | supported |
| H2.pass | At least 85% of those runs pass the task's conformance tests. | 14/16 (88%) | 64% - 97% | inconclusive |
| H3.distinct | At least four distinct style combinations are produced. | 2 distinct (n=16) | count, no interval | not supported |
| H3.max_share | No single style combination accounts for more than two-thirds of runs. | 13/16 (81%) | 57% - 93% | inconclusive |
| H4.startup.bake-off | Startup: every unoptimized Java program starts more slowly than the median Go, Python and Node programs for the same task. [bake-off] | 14/14 (100%) | 0 exception(s) | supported |
| H4.source.bake-off | Source size: the median Java program is more than 25% larger, in tokens, than the median of the smallest competing language for the same task. [bake-off] | 14/14 (100%) | 78% - 100% | supported |
| H4.disk.bake-off | Disk: Java programs that use dependencies occupy more disk space than the median competing program for the same task. [bake-off] | n/a | n/a | inconclusive (n=0) |
| H4.optimization.bake-off | Optimization: with optimization applied, median Java startup is within twice the median Python and Node startup. [bake-off] | 14/14 (100%) | 78% - 100% | supported |
| H4.startup.condA | Startup: every unoptimized Java program starts more slowly than the median Go, Python and Node programs for the same task. [condition A] | 14/14 (100%) | 0 exception(s) | supported |
| H4.source.condA | Source size: the median Java program is more than 25% larger, in tokens, than the median of the smallest competing language for the same task. [condition A] | 14/14 (100%) | 78% - 100% | supported |
| H4.disk.condA | Disk: Java programs that use dependencies occupy more disk space than the median competing program for the same task. [condition A] | n/a | n/a | inconclusive (n=0) |
| H4.optimization.condA | Optimization: with optimization applied, median Java startup is within twice the median Python and Node startup. [condition A] | 14/14 (100%) | 78% - 100% | supported |

## Condition A: language by task

| Task | python | n |
|---|---|---|
| logsum | 9 | 9 |
| treesum | 9 | 9 |
| jsonpick | 9 | 9 |
| jarsrc | 9 | 9 |

Java share: jarsrc 0/9, other tasks 0/27. Python share: jarsrc 9/9, other tasks 27/27.

## Condition B: style combinations

| Build / launch | Count | Share | Runs |
|---|---|---|---|
| build script / jar | 22 | 46% | B-jarsrc-haiku-1, B-jarsrc-haiku-3, B-jarsrc-opus-1, B-jarsrc-opus-2, B-jarsrc-opus-3, B-jarsrc-opus-4, B-jarsrc-sonnet-2, B-jsonpick-haiku-2, B-jsonpick-opus-4, B-jsonpick-sonnet-3, B-logsum-haiku-3, B-logsum-haiku-4, B-logsum-opus-1, B-logsum-opus-2, B-logsum-opus-3, B-logsum-opus-4, B-treesum-haiku-1, B-treesum-haiku-4, B-treesum-opus-1, B-treesum-opus-2, B-treesum-opus-3, B-treesum-opus-4 |
| none / javac + java | 15 | 31% | B-jarsrc-haiku-2, B-jarsrc-sonnet-1, B-jarsrc-sonnet-3, B-jsonpick-sonnet-1, B-jsonpick-sonnet-2, B-jsonpick-sonnet-4, B-logsum-sonnet-1, B-logsum-sonnet-2, B-logsum-sonnet-3, B-logsum-sonnet-4, B-treesum-haiku-2, B-treesum-sonnet-1, B-treesum-sonnet-2, B-treesum-sonnet-3, B-treesum-sonnet-4 |
| build script / javac + java | 6 | 12% | B-jarsrc-haiku-4, B-jarsrc-sonnet-4, B-jsonpick-haiku-3, B-jsonpick-opus-1, B-jsonpick-opus-2, B-jsonpick-opus-3 |
| Make / jar | 4 | 8% | B-jsonpick-haiku-4, B-logsum-haiku-1, B-logsum-haiku-2, B-treesum-haiku-3 |
| Make / javac + java | 1 | 2% | B-jsonpick-haiku-1 |

All dimensions, per run:

| Run | Model | Build | Launch | Wrapper | Source | Imports | Files | Dependencies | Optimization | Pass |
|---|---|---|---|---|---|---|---|---|---|---|
| B-jarsrc-haiku-1 | claude-haiku-5-5 | build script | jar | wrapper script | classic | classic | single file | none | none | no |
| B-jarsrc-haiku-2 | claude-haiku-5-5 | none | javac + java | none | classic | classic | multiple files | none | none | yes |
| B-jarsrc-haiku-3 | claude-haiku-5-5 | build script | jar | wrapper script | classic | classic | single file | none | none | yes |
| B-jarsrc-haiku-4 | claude-haiku-5-5 | build script | javac + java | wrapper script | classic | classic | multiple files | none | none | yes |
| B-jarsrc-opus-1 | claude-opus-5-5 | build script | jar | wrapper script | classic | classic | single file | none | none | no |
| B-jarsrc-opus-2 | claude-opus-5-5 | build script | jar | wrapper script | classic | classic | single file | none | none | yes |
| B-jarsrc-opus-3 | claude-opus-5-5 | build script | jar | wrapper script | classic | classic | single file | none | none | yes |
| B-jarsrc-opus-4 | claude-opus-5-5 | build script | jar | wrapper script | classic | classic | single file | none | none | yes |
| B-jarsrc-sonnet-1 | claude-sonnet-5-5 | none | javac + java | wrapper script | classic | classic | single file | none | none | yes |
| B-jarsrc-sonnet-2 | claude-sonnet-5-5 | build script | jar | wrapper script | classic | classic | single file | none | none | yes |
| B-jarsrc-sonnet-3 | claude-sonnet-5-5 | none | javac + java | wrapper script | classic | classic | single file | none | none | yes |
| B-jarsrc-sonnet-4 | claude-sonnet-5-5 | build script | javac + java | wrapper script | classic | classic | single file | none | none | yes |
| B-jsonpick-haiku-1 | claude-haiku-5-5 | Make | javac + java | wrapper script | classic | classic | multiple files | none | none | no |
| B-jsonpick-haiku-2 | claude-haiku-5-5 | build script | jar | none | classic | classic | multiple files | none | none | yes |
| B-jsonpick-haiku-3 | claude-haiku-5-5 | build script | javac + java | wrapper script | classic | classic | multiple files | none | none | yes |
| B-jsonpick-haiku-4 | claude-haiku-5-5 | Make | jar | wrapper script | classic | classic | multiple files | none | none | yes |
| B-jsonpick-opus-1 | claude-opus-5-5 | build script | javac + java | wrapper script | classic | classic | single file | none | none | no |
| B-jsonpick-opus-2 | claude-opus-5-5 | build script | javac + java | wrapper script | classic | classic | single file | none | none | yes |
| B-jsonpick-opus-3 | claude-opus-5-5 | build script | javac + java | wrapper script | classic | classic | single file | none | none | yes |
| B-jsonpick-opus-4 | claude-opus-5-5 | build script | jar | wrapper script | classic | classic | single file | none | none | yes |
| B-jsonpick-sonnet-1 | claude-sonnet-5-5 | none | javac + java | wrapper script | classic | classic | single file | none | none | no |
| B-jsonpick-sonnet-2 | claude-sonnet-5-5 | none | javac + java | wrapper script | classic | classic | single file | none | none | no |
| B-jsonpick-sonnet-3 | claude-sonnet-5-5 | build script | jar | wrapper script | classic | classic | single file | none | none | no |
| B-jsonpick-sonnet-4 | claude-sonnet-5-5 | none | javac + java | wrapper script | classic | classic | single file | none | none | no |
| B-logsum-haiku-1 | claude-haiku-5-5 | Make | jar | none | classic | classic | multiple files | none | none | yes |
| B-logsum-haiku-2 | claude-haiku-5-5 | Make | jar | none | classic | classic | single file | none | none | yes |
| B-logsum-haiku-3 | claude-haiku-5-5 | build script | jar | wrapper script | classic | classic | multiple files | none | none | yes |
| B-logsum-haiku-4 | claude-haiku-5-5 | build script | jar | none | classic | classic | single file | none | none | yes |
| B-logsum-opus-1 | claude-opus-5-5 | build script | jar | wrapper script | classic | classic | single file | none | none | yes |
| B-logsum-opus-2 | claude-opus-5-5 | build script | jar | wrapper script | classic | classic | single file | none | none | yes |
| B-logsum-opus-3 | claude-opus-5-5 | build script | jar | wrapper script | classic | classic | single file | none | none | yes |
| B-logsum-opus-4 | claude-opus-5-5 | build script | jar | wrapper script | classic | classic | single file | none | none | yes |
| B-logsum-sonnet-1 | claude-sonnet-5-5 | none | javac + java | wrapper script | classic | classic | single file | none | none | yes |
| B-logsum-sonnet-2 | claude-sonnet-5-5 | none | javac + java | wrapper script | classic | classic | single file | none | none | yes |
| B-logsum-sonnet-3 | claude-sonnet-5-5 | none | javac + java | wrapper script | classic | classic | single file | none | none | yes |
| B-logsum-sonnet-4 | claude-sonnet-5-5 | none | javac + java | wrapper script | classic | classic | single file | none | none | yes |
| B-treesum-haiku-1 | claude-haiku-5-5 | build script | jar | none | classic | classic | single file | none | none | yes |
| B-treesum-haiku-2 | claude-haiku-5-5 | none | javac + java | none | classic | classic | single file | none | none | yes |
| B-treesum-haiku-3 | claude-haiku-5-5 | Make | jar | wrapper script | classic | classic | single file | none | none | yes |
| B-treesum-haiku-4 | claude-haiku-5-5 | build script | jar | none | classic | classic | single file | none | none | yes |
| B-treesum-opus-1 | claude-opus-5-5 | build script | jar | wrapper script | classic | classic | single file | none | none | yes |
| B-treesum-opus-2 | claude-opus-5-5 | build script | jar | wrapper script | classic | classic | single file | none | none | yes |
| B-treesum-opus-3 | claude-opus-5-5 | build script | jar | wrapper script | classic | classic | single file | none | none | yes |
| B-treesum-opus-4 | claude-opus-5-5 | build script | jar | wrapper script | classic | classic | single file | none | none | yes |
| B-treesum-sonnet-1 | claude-sonnet-5-5 | none | javac + java | wrapper script | classic | classic | single file | none | none | yes |
| B-treesum-sonnet-2 | claude-sonnet-5-5 | none | javac + java | wrapper script | classic | classic | single file | none | none | yes |
| B-treesum-sonnet-3 | claude-sonnet-5-5 | none | javac + java | wrapper script | classic | classic | single file | none | none | yes |
| B-treesum-sonnet-4 | claude-sonnet-5-5 | none | javac + java | wrapper script | classic | classic | single file | none | none | yes |

Dimension totals:

| Dimension | Values |
|---|---|
| build | build script: 28, none: 15, Make: 5 |
| launch | jar: 26, javac + java: 22 |
| wrapper_dim | wrapper script: 40, none: 8 |
| source | classic: 48 |
| imports | classic: 48 |
| files | single file: 40, multiple files: 8 |
| dependencies | none: 48 |
| optimization | none: 48 |

### Sensitivity: Make and build script counted as build "none"

| Build / launch | Count |
|---|---|
| none / jar | 26 |
| none / javac + java | 22 |

| Expectation | Observed | 95% interval | Verdict |
|---|---|---|---|
| At least four distinct style combinations | 2 distinct | count, no interval | not supported |
| No single combination over two-thirds | 26/48 (54%) | 40% - 67% | inconclusive |

## jsonpick in condition B: dependency vs hand-written parser

| Run | Parser | Dependencies | Source tokens | Disk | Pass |
|---|---|---|---|---|---|
| B-jsonpick-haiku-1 | hand-written | none | 5246 | 47.6 KB | no |
| B-jsonpick-haiku-2 | hand-written | none | 4540 | 53.5 KB | yes |
| B-jsonpick-haiku-3 | hand-written | none | 4900 | 44.3 KB | yes |
| B-jsonpick-haiku-4 | hand-written | none | 4961 | 68.8 KB | yes |
| B-jsonpick-opus-1 | hand-written | none | 4771 | 41.6 KB | no |
| B-jsonpick-opus-2 | hand-written | none | 5626 | 48.7 KB | yes |
| B-jsonpick-opus-3 | hand-written | none | 5231 | 47.9 KB | yes |
| B-jsonpick-opus-4 | hand-written | none | 5535 | 81.1 KB | yes |
| B-jsonpick-sonnet-1 | hand-written | none | 4012 | 34.3 KB | no |
| B-jsonpick-sonnet-2 | hand-written | none | 4199 | 36.1 KB | no |
| B-jsonpick-sonnet-3 | hand-written | none | 3890 | 41.8 KB | no |
| B-jsonpick-sonnet-4 | hand-written | none | 4066 | 33.5 KB | no |
| median (hand-written, n=12) | | | 4835.5 | 46.0 KB | |

## Hypothesis 4 comparisons

Medians per task. Startup in ms (`--help`, hyperfine, run as the program instructs). Java rows: passing Java programs from both conditions; AOT = harness-packaged jar with an AOT cache.

### logsum

| Set | Language | n | Startup median | Source tokens median | Disk median |
|---|---|---|---|---|---|
| bake-off | go | 1 | 1.5 | 2052 | 3.0 MB |
| bake-off | javascript | 1 | 20.7 | 1341 | 4.5 KB |
| bake-off | python | 1 | 13.0 | 1485 | 5.6 KB |
| condition A | python | 9 | 14.6 | 1457 | 5.6 KB |
| generated | java | 12 | 28.2 | 2642.5 | 26.4 KB |
| generated | java (AOT) | 12 | 20.3 | | |

### treesum

| Set | Language | n | Startup median | Source tokens median | Disk median |
|---|---|---|---|---|---|
| bake-off | go | 1 | 1.4 | 1899 | 2.6 MB |
| bake-off | javascript | 1 | 20.7 | 1409 | 4.6 KB |
| bake-off | python | 1 | 13.0 | 1393 | 5.4 KB |
| condition A | python | 9 | 14.0 | 1372 | 5.3 KB |
| generated | java | 12 | 26.3 | 2517.5 | 36.2 KB |
| generated | java (AOT) | 12 | 19.5 | | |

### jsonpick

| Set | Language | n | Startup median | Source tokens median | Disk median |
|---|---|---|---|---|---|
| bake-off | go | 1 | 1.5 | 4455 | 3.8 MB |
| bake-off | javascript | 1 | 20.7 | 1519 | 5.2 KB |
| bake-off | python | 1 | 13.9 | 1630 | 6.5 KB |
| condition A | python | 7 | 16.1 | 1961 | 8.9 KB |
| generated | java | 6 | 25.7 | 5096.0 | 51.1 KB |
| generated | java (AOT) | 6 | 19.7 | | |

### jarsrc

| Set | Language | n | Startup median | Source tokens median | Disk median |
|---|---|---|---|---|---|
| bake-off | go | 1 | 1.5 | 3492 | 3.4 MB |
| bake-off | javascript | 1 | 21.4 | 3701 | 12.8 KB |
| bake-off | python | 1 | 19.5 | 2741 | 10.6 KB |
| condition A | python | 9 | 20.0 | 2302 | 10.3 KB |
| generated | java | 10 | 26.8 | 3761.0 | 44.0 KB |
| generated | java (AOT) | 10 | 19.4 | | |

## Runs

| Run | Model ID | Turns | Output tokens | Cost (USD) | Wall (s) | Language | Pass | Run command | Limits | Review flags |
|---|---|---|---|---|---|---|---|---|---|---|
| A-jarsrc-sonnet-1 | claude-sonnet-5-5 | 5 | 6058 | 0.11 | 40.7 | python | yes | `./jarsrc` | - | - |
| B-treesum-opus-3 | claude-opus-5-5 | 7 | 8389 | 0.31 | 73.2 | java | yes | `./treesum` | - | - |
| A-treesum-haiku-3 | claude-haiku-5-5 | 7 | 19194 | 0.02 | 82.5 | python | yes | `./treesum` | - | - |
| B-logsum-haiku-1 | claude-haiku-5-5 | 18 | 46501 | 0.04 | 185.7 | java | yes | `java -jar build/logsum.jar` | - | - |
| A-treesum-sonnet-2 | claude-sonnet-5-5 | 8 | 4210 | 0.09 | 30.1 | python | yes | `./treesum` | - | - |
| A-jarsrc-opus-2 | claude-opus-5-5 | 7 | 11367 | 0.39 | 99.1 | python | yes | `./jarsrc` | - | - |
| B-jsonpick-haiku-4 | claude-haiku-5-5 | 22 | 48364 | 0.04 | 198.0 | java | yes | `./bin/jsonpick` | - | - |
| A-treesum-opus-3 | claude-opus-5-5 | 7 | 5376 | 0.21 | 48.6 | python | yes | `./treesum` | - | - |
| B-jarsrc-sonnet-4 | claude-sonnet-5-5 | 6 | 9421 | 0.17 | 62.6 | java | yes | `./jarsrc` | - | - |
| B-treesum-opus-2 | claude-opus-5-5 | 9 | 9015 | 0.32 | 81.0 | java | yes | `./treesum` | - | - |
| A-jsonpick-haiku-2 | claude-haiku-5-5 | 14 | 26301 | 0.02 | 115.5 | python | yes | `./jsonpick` | - | - |
| B-jarsrc-haiku-1 | claude-haiku-5-5 | 11 | 43007 | 0.04 | 179.9 | java | no | `./jarsrc` | - | - |
| B-logsum-haiku-4 | claude-haiku-5-5 | 10 | 38189 | 0.03 | 156.8 | java | yes | `java -jar build/logsum.jar` | - | - |
| B-jsonpick-haiku-3 | claude-haiku-5-5 | 24 | 55496 | 0.05 | 229.0 | java | yes | `build/jsonpick` | - | - |
| B-logsum-haiku-2 | claude-haiku-5-5 | 10 | 31505 | 0.03 | 132.3 | java | yes | `java -jar build/logsum.jar` | - | - |
| B-logsum-opus-1 | claude-opus-5-5 | 9 | 10141 | 0.34 | 123.3 | java | yes | `./logsum` | - | - |
| A-jarsrc-sonnet-2 | claude-sonnet-5-5 | 6 | 6589 | 0.12 | 44.5 | python | yes | `./jarsrc` | - | - |
| A-jarsrc-haiku-3 | claude-haiku-5-5 | 7 | 27411 | 0.02 | 111.8 | python | yes | `./jarsrc` | - | - |
| B-jsonpick-opus-4 | claude-opus-5-5 | 10 | 17000 | 0.56 | 142.4 | java | yes | `./jsonpick` | - | - |
| B-jsonpick-sonnet-1 | claude-sonnet-5-5 | 7 | 9560 | 0.17 | 56.8 | java | no | `./jsonpick` | - | - |
| B-jarsrc-opus-1 | claude-opus-5-5 | 8 | 13983 | 0.48 | 121.0 | java | no | `./jarsrc` | - | - |
| B-treesum-haiku-1 | claude-haiku-5-5 | 11 | 32041 | 0.03 | 131.4 | java | yes | `java -jar build/treesum.jar` | - | - |
| A-jarsrc-opus-1 | claude-opus-5-5 | 8 | 10823 | 0.38 | 124.8 | python | yes | `./jarsrc` | - | - |
| B-jarsrc-sonnet-3 | claude-sonnet-5-5 | 4 | 7898 | 0.14 | 50.3 | java | yes | `./jarsrc` | - | - |
| B-treesum-sonnet-3 | claude-sonnet-5-5 | 4 | 4790 | 0.09 | 34.5 | java | yes | `./treesum` | - | - |
| A-logsum-haiku-1 | claude-haiku-5-5 | 10 | 22473 | 0.02 | 105.9 | python | yes | `./logsum` | - | - |
| A-logsum-haiku-2 | claude-haiku-5-5 | 9 | 28692 | 0.02 | 121.2 | python | yes | `./logsum` | - | - |
| B-jarsrc-sonnet-2 | claude-sonnet-5-5 | 6 | 10571 | 0.18 | 69.6 | java | yes | `./jarsrc` | - | - |
| A-jsonpick-opus-2 | claude-opus-5-5 | 8 | 9788 | 0.35 | 87.2 | python | yes | `./jsonpick` | - | - |
| A-logsum-haiku-3 | claude-haiku-5-5 | 14 | 19435 | 0.02 | 87.4 | python | yes | `./logsum` | - | - |
| B-jarsrc-haiku-2 | claude-haiku-5-5 | 16 | 46840 | 0.04 | 192.5 | java | yes | `javac -d out src/jarsrc/*.java && java -cp out jarsrc.Main` | - | - |
| A-logsum-opus-2 | claude-opus-5-5 | 6 | 6378 | 0.24 | 61.4 | python | yes | `./logsum` | - | - |
| B-treesum-haiku-4 | claude-haiku-5-5 | 9 | 25832 | 0.02 | 109.3 | java | yes | `java -jar build/treesum.jar` | - | - |
| B-jarsrc-opus-3 | claude-opus-5-5 | 8 | 11923 | 0.42 | 100.0 | java | yes | `./jarsrc` | - | - |
| B-logsum-sonnet-3 | claude-sonnet-5-5 | 5 | 4459 | 0.09 | 30.2 | java | yes | `./logsum` | - | - |
| B-jsonpick-haiku-1 | claude-haiku-5-5 | 22 | 43400 | 0.04 | 187.1 | java | no | `bin/jsonpick` | - | - |
| A-treesum-haiku-1 | claude-haiku-5-5 | 10 | 18147 | 0.02 | 76.0 | python | yes | `./treesum` | - | - |
| B-jarsrc-sonnet-1 | claude-sonnet-5-5 | 4 | 8532 | 0.15 | 54.9 | java | yes | `./jarsrc` | - | - |
| B-jsonpick-sonnet-3 | claude-sonnet-5-5 | 5 | 8755 | 0.15 | 51.1 | java | no | `./jsonpick` | - | - |
| B-jsonpick-sonnet-2 | claude-sonnet-5-5 | 6 | 10163 | 0.18 | 63.6 | java | no | `./jsonpick` | - | - |
| A-logsum-opus-3 | claude-opus-5-5 | 6 | 6354 | 0.24 | 64.5 | python | yes | `./logsum` | - | - |
| B-treesum-sonnet-1 | claude-sonnet-5-5 | 4 | 4178 | 0.08 | 26.9 | java | yes | `./treesum` | - | - |
| A-treesum-sonnet-3 | claude-sonnet-5-5 | 5 | 2908 | 0.07 | 21.5 | python | yes | `./treesum` | - | - |
| B-jsonpick-haiku-2 | claude-haiku-5-5 | 19 | 49437 | 0.04 | 196.6 | java | yes | `java -jar build/jsonpick.jar` | - | - |
| B-treesum-haiku-2 | claude-haiku-5-5 | 9 | 25805 | 0.02 | 108.3 | java | yes | `javac -d out src/treesum/Main.java && java -cp out treesum.Main` | - | - |
| B-treesum-sonnet-2 | claude-sonnet-5-5 | 4 | 4131 | 0.08 | 27.4 | java | yes | `./treesum` | - | - |
| B-treesum-sonnet-4 | claude-sonnet-5-5 | 4 | 4340 | 0.08 | 28.3 | java | yes | `./treesum` | - | - |
| A-logsum-opus-1 | claude-opus-5-5 | 6 | 6493 | 0.22 | 78.5 | python | yes | `./logsum` | - | - |
| A-treesum-haiku-2 | claude-haiku-5-5 | 9 | 19139 | 0.02 | 77.2 | python | yes | `./treesum` | - | - |
| A-jsonpick-haiku-1 | claude-haiku-5-5 | 8 | 21976 | 0.02 | 92.3 | python | yes | `./jsonpick` | - | - |
| B-treesum-haiku-3 | claude-haiku-5-5 | 14 | 29047 | 0.03 | 125.5 | java | yes | `./bin/treesum` | - | - |
| A-jsonpick-opus-3 | claude-opus-5-5 | 15 | 12044 | 0.45 | 111.1 | python | yes | `./jsonpick` | - | - |
| A-jsonpick-sonnet-2 | claude-sonnet-5-5 | 6 | 5389 | 0.10 | 38.4 | python | yes | `./jsonpick` | - | - |
| B-jarsrc-haiku-3 | claude-haiku-5-5 | 17 | 40008 | 0.04 | 170.8 | java | yes | `./jarsrc` | - | - |
| B-jarsrc-opus-4 | claude-opus-5-5 | 8 | 11080 | 0.42 | 105.6 | java | yes | `./jarsrc` | - | - |
| B-jarsrc-opus-2 | claude-opus-5-5 | 10 | 16871 | 0.56 | 154.5 | java | yes | `./jarsrc` | - | - |
| B-logsum-opus-2 | claude-opus-5-5 | 7 | 8389 | 0.31 | 78.6 | java | yes | `./logsum` | - | - |
| B-logsum-opus-3 | claude-opus-5-5 | 5 | 9529 | 0.33 | 84.7 | java | yes | `./logsum` | - | - |
| B-logsum-sonnet-1 | claude-sonnet-5-5 | 4 | 5339 | 0.09 | 33.7 | java | yes | `./logsum` | - | - |
| A-jarsrc-haiku-1 | claude-haiku-5-5 | 14 | 35017 | 0.03 | 142.1 | python | yes | `./jarsrc` | - | - |
| A-logsum-sonnet-2 | claude-sonnet-5-5 | 5 | 3493 | 0.08 | 25.4 | python | yes | `./logsum` | - | - |
| B-logsum-haiku-3 | claude-haiku-5-5 | 16 | 32574 | 0.03 | 144.4 | java | yes | `bin/logsum` | - | - |
| B-jsonpick-opus-1 | claude-opus-5-5 | 8 | 13957 | 0.48 | 120.9 | java | no | `./jsonpick` | - | - |
| A-jsonpick-opus-1 | claude-opus-5-5 | 6 | 7425 | 0.27 | 67.8 | python | yes | `./jsonpick` | - | - |
| B-jsonpick-opus-2 | claude-opus-5-5 | 6 | 15174 | 0.51 | 128.1 | java | yes | `./jsonpick` | - | - |
| B-jsonpick-sonnet-4 | claude-sonnet-5-5 | 6 | 9048 | 0.16 | 55.4 | java | no | `./jsonpick` | - | - |
| A-jarsrc-sonnet-3 | claude-sonnet-5-5 | 5 | 6344 | 0.12 | 44.5 | python | yes | `./jarsrc` | - | - |
| B-logsum-sonnet-2 | claude-sonnet-5-5 | 4 | 5245 | 0.10 | 34.6 | java | yes | `./logsum` | - | - |
| B-jsonpick-opus-3 | claude-opus-5-5 | 8 | 14990 | 0.50 | 127.3 | java | yes | `./jsonpick` | - | - |
| A-logsum-sonnet-3 | claude-sonnet-5-5 | 4 | 3470 | 0.08 | 24.7 | python | yes | `./logsum` | - | - |
| B-jarsrc-haiku-4 | claude-haiku-5-5 | 16 | 39995 | 0.04 | 173.8 | java | yes | `./bin/jarsrc` | - | - |
| B-treesum-opus-1 | claude-opus-5-5 | 5 | 8157 | 0.29 | 69.2 | java | yes | `./treesum` | - | - |
| A-jarsrc-haiku-2 | claude-haiku-5-5 | 14 | 32147 | 0.03 | 134.0 | python | yes | `./jarsrc` | - | - |
| A-jsonpick-haiku-3 | claude-haiku-5-5 | 11 | 28556 | 0.02 | 121.3 | python | yes | `./jsonpick` | - | - |
| A-jarsrc-opus-3 | claude-opus-5-5 | 8 | 12456 | 0.44 | 109.1 | python | yes | `./jarsrc` | - | - |
| A-treesum-sonnet-1 | claude-sonnet-5-5 | 4 | 3310 | 0.07 | 22.4 | python | yes | `./treesum` | - | - |
| A-jsonpick-sonnet-3 | claude-sonnet-5-5 | 5 | 5215 | 0.10 | 37.6 | python | no | `./jsonpick` | - | - |
| A-logsum-sonnet-1 | claude-sonnet-5-5 | 4 | 3597 | 0.06 | 25.7 | python | yes | `./logsum` | - | - |
| B-logsum-sonnet-4 | claude-sonnet-5-5 | 4 | 5030 | 0.10 | 32.2 | java | yes | `./logsum` | - | - |
| B-logsum-opus-4 | claude-opus-5-5 | 5 | 8071 | 0.29 | 69.7 | java | yes | `./logsum` | - | - |
| A-jsonpick-sonnet-1 | claude-sonnet-5-5 | 6 | 5957 | 0.11 | 43.8 | python | no | `./jsonpick` | - | - |
| B-treesum-opus-4 | claude-opus-5-5 | 5 | 6905 | 0.25 | 62.1 | java | yes | `./treesum` | - | - |
| A-treesum-opus-1 | claude-opus-5-5 | 6 | 5373 | 0.21 | 50.3 | python | yes | `./treesum` | - | - |
| A-treesum-opus-2 | claude-opus-5-5 | 5 | 4715 | 0.19 | 44.4 | python | yes | `./treesum` | - | - |

Generation cost (notional, list price): $13.88. Follow-up turns (excluded): $0.63.

## Top-up assessment (Step 6)

| Inconclusive expectation | Could 24 more runs resolve it? | Proposed |
|---|---|---|
| H2.pass (40/48 = 83%, interval 70% - 91%, threshold 85%) | No. Supported needs a lower bound of at least 85%: even 24/24 more passes gives 64/72, interval 80% - 94%. Not supported needs an upper bound below 85%: at most 55/72, i.e. 15/24 or fewer passes in the top-up, against 83% observed. All 8 failures are one misread spec line (`object{<N keys>}` in jsonpick, 6 runs; `unknown flag:` prefix in jarsrc, 2 runs) | No top-up |
| H4.disk (both competitor sets) | No. It applies only to Java programs with dependencies, and none of the 48 Java programs declared one (including all 12 jsonpick runs, which all wrote their own JSON parser). 0/48 gives an upper bound of 7.4% for dependency use, so 24 more runs would likely add none | No top-up; reported as untestable in this sample |

## Threats to validity

| Threat | Detail | Effect on the verdicts |
|---|---|---|
| N per cell | 3 runs per task and model in condition A, 4 in condition B (84 in total). Pooled N is 36 (A) and 48 (B); per-model and per-task cells are too small for their own verdicts | Wilson intervals are wide; a proportion needs to be extreme to clear a threshold. The per-model tables are supporting detail only |
| Prompt wording | One fixed sentence per condition, no system-prompt changes. "Build the `<cli>` command-line tool described in SPEC.md" may itself suggest a script-like deliverable | Results describe this prompt, not every way of asking |
| Spec edits | Language and approach hints removed (`tasks/CHANGES.md`); everything the cases check kept verbatim | Some residual cues remain by design (jarsrc is about jars and `.java` files; treesum's ignore list names ecosystem directories) |
| Spec lines that are easy to misread | `object{<N keys>}` (golden `object{2 keys}`, 8 runs printed `object{2}`, Python and Java alike) and `unknown flag: <flag>` (golden has no `error:` prefix; 2 Java runs added one). Comparison is byte-exact, as in the bake-off | Lowers the pass rate (Hypothesis 2) for reasons unrelated to language; all 10 failing runs fail only one of these two cases |
| Isolation limits | User settings, memory and MCP servers excluded; built-in skills and plugins remain; network allowed; the agent sees everything installed on the machine (`results/environment.md`) | Models run at default effort, not the user's setting |
| Installed toolchains | JDK 25 GA (pinned by the harness), Python 3.13, Node 24, Go 1.27, JBang on PATH; no Maven, Gradle, Rust, uv, Bun or Deno | Could steer choices (for example away from Maven/Gradle in condition B) |
| Run command | Taken from a follow-up turn and normalized (placeholders and a trailing `--help` removed; every change recorded in `measure.json`) | A wrong reading would cause a false conformance failure; all normalized commands were checked by hand |
| Parallel generation | Runs 13-84 (attempts) ran 4 at a time (decision D8) | Per-run wall time is inflated; no effect on code, conformance, size or benchmarks |
| Usage-limit retries | 5 attempts cut off by the Pro session limit were retried (decision D9); the cut-off attempts are kept as `results/raw/*.limit-*` | A retried run is a fresh sample of the same cell |
| Tokenizer proxy | Source size uses tiktoken `o200k_base`, not Claude's tokenizer | Ratios between languages are more reliable than absolute counts |
| Machine load during benchmarks | Benchmarks ran sequentially after generation, on the author's laptop with normal background activity; load average recorded at the start of every run's benchmark (`load_avg_at_bench_start`) and hyperfine's spread kept | Small absolute differences (a few ms) are within noise; the Java vs. Python gap is far larger |
| Disk definition | Project files + per-run caches, excluding runtime downloads and the Go compiler cache (decision D6) | Go and Java-with-dependencies totals depend on this choice; breakdowns are in `measure.json` |
| Hypothesis 4 rules | Median expectations judged as shares against 50%; startup and disk as "every" (decisions D5 and "Decided" table) | Documented interpretation; other readings could change borderline verdicts |
