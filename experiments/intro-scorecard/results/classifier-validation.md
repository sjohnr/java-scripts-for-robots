# Classifier validation

24 of 24 cases match the hand-assigned labels on every dimension.

| Case | Run command | Language | Build | Launch | Wrapper | Source | Imports | Files | Deps | Optimization | Match |
|---|---|---|---|---|---|---|---|---|---|---|---|
| bakeoff logsum classic (javac + java) | `javac -d build/classes Tool.java && java -cp build/classes Tool` | java | none | javac + java | none | classic | classic | single file | none | none | yes |
| bakeoff treesum classic (jar) | `java -jar build/treesum.jar` | java | none | jar | none | classic | classic | single file | none | none | yes |
| bakeoff jarsrc compact | `java Tool.java` | java | none | source launch | none | compact source | module imports | single file | none | none | yes |
| bakeoff jarsrc multi | `java Tool.java` | java | none | source launch | none | classic | classic | multiple files | none | none | yes |
| bakeoff noop compact | `java Tool.java` | java | none | source launch | none | compact source | classic | single file | none | none | yes |
| bakeoff jsonpick jbang (jbang cmd) | `jbang tool.java` | java | JBang | source launch | none | classic | classic | single file | one or more | none | yes |
| bakeoff jsonpick jbang (shebang) | `./tool.java` | java | JBang | shebang | none | classic | classic | single file | one or more | none | yes |
| bakeoff jarsrc shebang | `./jarsrc` | java | none | shebang | none | classic | classic | single file | none | none | yes |
| bakeoff jarsrc AOT cache | `java -XX:AOTCache=build/jarsrc.aot -jar build/jarsrc.jar` | java | none | jar | none | classic | classic | single file | none | AOT cache | yes |
| bakeoff jarsrc native image | `./jarsrc-native` | java | none | native binary | none | classic | classic | single file | none | GraalVM native image | yes |
| bakeoff logsum go | `./logsum` | go |  |  |  |  |  |  |  |  | yes |
| bakeoff logsum python | `python3 tool.py` | python |  |  |  |  |  |  |  |  | yes |
| bakeoff logsum node | `node tool.mjs` | javascript |  |  |  |  |  |  |  |  | yes |
| javaw 02 go | `./javaw` | go |  |  |  |  |  |  |  |  | yes |
| javaw 03 javascript | `./javaw` | javascript |  |  |  |  |  |  |  |  | yes |
| javaw 04 python | `./javaw` | python |  |  |  |  |  |  |  |  | yes |
| javaw 05 java (sh wrapper) | `./javaw` | java | none | source launch | wrapper script | classic | classic | single file | none | none | yes |
| javaw 05 java (java cmd) | `java Javaw.java` | java | none | source launch | none | classic | classic | single file | none | none | yes |
| javaw 06 jbang | `jbang javaw.java` | java | JBang | source launch | none | classic | classic | single file | none | none | yes |
| javaw 11 compact (java cmd) | `java Javaw.java` | java | none | source launch | none | compact source | module imports | single file | none | none | yes |
| javaw 11 compact (sh wrapper) | `./javaw` | java | none | source launch | wrapper script | compact source | module imports | single file | none | none | yes |
| javaw 12 shebang | `./javaw` | java | none | shebang | none | classic | classic | single file | none | none | yes |
| javaw 13 detailed | `./javaw` | java | none | shebang | none | compact source | module imports | single file | none | none | yes |
| javaw 13 detailed v2 | `./javaw` | java | none | shebang | none | compact source | module imports | single file | none | none | yes |
