## Top-up assessment (Step 6)

| Inconclusive expectation | Could 24 more runs resolve it? | Proposed |
|---|---|---|
| H2.pass (40/48 = 83%, interval 70% - 91%, threshold 85%) | No. Supported needs a lower bound of at least 85%: even 24/24 more passes gives 64/72, interval 80% - 94%. Not supported needs an upper bound below 85%: at most 55/72, i.e. 15/24 or fewer passes in the top-up, against 83% observed. All 8 failures are one misread spec line (`object{<N keys>}` in jsonpick, 6 runs; `unknown flag:` prefix in jarsrc, 2 runs) | No top-up |
| H4.disk (both competitor sets) | No. It applies only to Java programs with dependencies, and none of the 48 Java programs declared one (including all 12 jsonpick runs, which all wrote their own JSON parser). 0/48 gives an upper bound of 7.4% for dependency use, so 24 more runs would likely add none | No top-up; reported as untestable in this sample |
