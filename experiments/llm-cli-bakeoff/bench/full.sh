#!/usr/bin/env bash
# Full benchmark sweep -- reproduces every number in REPORT.md's Phase 3
# tables. Takes several minutes. Requires all Java build artifacts to
# already exist (run bench/build-java-modes.sh for each CLI first) and
# small fixtures generated (scripts/gen_fixtures.py small fixtures/small).
set -euo pipefail
ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
cd "$ROOT"

: "${JAVA_HOME:?set JAVA_HOME to a JDK 25 install}"
FIX=fixtures/small
mkdir -p bench/results/raw

echo "== noop startup floor (all languages + all Java modes) =="
D=tools/noop
hyperfine --warmup 3 --min-runs 30 --export-json bench/results/raw/noop-startup.json \
  -n "go" "$D/go/noop" \
  -n "python" "python3 $D/python/tool.py" \
  -n "node" "node $D/node/tool.mjs" \
  -n "java-src" "$JAVA_HOME/bin/java $D/java/classic/Tool.java" \
  -n "java-compact" "$JAVA_HOME/bin/java $D/java/compact/Tool.java" \
  -n "java-class" "$JAVA_HOME/bin/java -cp $D/java/build/classes Tool" \
  -n "java-jar" "$JAVA_HOME/bin/java -jar $D/java/build/noop.jar" \
  -n "java-jar-tuned" "$JAVA_HOME/bin/java -XX:TieredStopAtLevel=1 -XX:+UseSerialGC -XX:-UsePerfData -jar $D/java/build/noop.jar" \
  -n "java-aot" "$JAVA_HOME/bin/java -XX:AOTCache=$D/java/build/noop.aot -jar $D/java/build/noop.jar" \
  -n "java-cds-classic" "$JAVA_HOME/bin/java -XX:SharedArchiveFile=$D/java/build/noop.jsa -jar $D/java/build/noop.jar" \
  -n "java-native" "$D/java/build/noop-native"

echo "== jarsrc, small fixture =="
D=tools/jarsrc
hyperfine --warmup 3 --min-runs 30 --export-json bench/results/raw/jarsrc-small.json \
  -n "go" "$D/go/jarsrc find **.Main --root $FIX/jarsrc" \
  -n "python" "python3 $D/python/tool.py find **.Main --root $FIX/jarsrc" \
  -n "node" "node $D/node/tool.mjs find **.Main --root $FIX/jarsrc" \
  -n "java-jar" "$JAVA_HOME/bin/java -jar $D/java/build/jarsrc.jar find **.Main --root $FIX/jarsrc" \
  -n "java-jar-tuned" "$JAVA_HOME/bin/java -XX:TieredStopAtLevel=1 -XX:+UseSerialGC -XX:-UsePerfData -jar $D/java/build/jarsrc.jar find **.Main --root $FIX/jarsrc" \
  -n "java-aot" "$JAVA_HOME/bin/java -XX:AOTCache=$D/java/build/jarsrc.aot -jar $D/java/build/jarsrc.jar find **.Main --root $FIX/jarsrc" \
  -n "java-native" "$D/java/build/jarsrc-native find **.Main --root $FIX/jarsrc"

echo "== logsum, small fixture =="
D=tools/logsum
hyperfine --warmup 3 --min-runs 30 --export-json bench/results/raw/logsum-small.json \
  -n "go" "$D/go/logsum $FIX/logsum.log" \
  -n "python" "python3 $D/python/tool.py $FIX/logsum.log" \
  -n "node" "node $D/node/tool.mjs $FIX/logsum.log" \
  -n "java-jar" "$JAVA_HOME/bin/java -jar $D/java/build/logsum.jar $FIX/logsum.log" \
  -n "java-aot" "$JAVA_HOME/bin/java -XX:AOTCache=$D/java/build/logsum.aot -jar $D/java/build/logsum.jar $FIX/logsum.log"

echo "== treesum, small fixture =="
D=tools/treesum
hyperfine --warmup 3 --min-runs 30 --export-json bench/results/raw/treesum-small.json \
  -n "go" "$D/go/treesum $FIX/treesum" \
  -n "python" "python3 $D/python/tool.py $FIX/treesum" \
  -n "node" "node $D/node/tool.mjs $FIX/treesum" \
  -n "java-jar" "$JAVA_HOME/bin/java -jar $D/java/build/treesum.jar $FIX/treesum" \
  -n "java-aot" "$JAVA_HOME/bin/java -XX:AOTCache=$D/java/build/treesum.aot -jar $D/java/build/treesum.jar $FIX/treesum"

echo "== jsonpick, small fixture =="
D=tools/jsonpick
hyperfine --warmup 3 --min-runs 30 --export-json bench/results/raw/jsonpick-small.json \
  -n "go" "$D/go/jsonpick meta.count $FIX/jsonpick.json" \
  -n "python" "python3 $D/python/tool.py meta.count $FIX/jsonpick.json" \
  -n "node" "node $D/node/tool.mjs meta.count $FIX/jsonpick.json" \
  -n "java-jar" "$JAVA_HOME/bin/java -jar $D/java/build/jsonpick.jar meta.count $FIX/jsonpick.json" \
  -n "java-aot" "$JAVA_HOME/bin/java -XX:AOTCache=$D/java/build/jsonpick.aot -jar $D/java/build/jsonpick.jar meta.count $FIX/jsonpick.json" \
  -n "java-jbang-warm" "jbang $D/java/jbang/tool.java meta.count $FIX/jsonpick.json"

echo "== agent session cost: 50x sequential jarsrc find =="
D=tools/jarsrc
hyperfine --warmup 2 --min-runs 10 --export-json bench/results/raw/agent-session-50x.json \
  -n "go-50x" "for i in \$(seq 50); do $D/go/jarsrc find **.Main --root $FIX/jarsrc >/dev/null; done" \
  -n "python-50x" "for i in \$(seq 50); do python3 $D/python/tool.py find **.Main --root $FIX/jarsrc >/dev/null; done" \
  -n "node-50x" "for i in \$(seq 50); do node $D/node/tool.mjs find **.Main --root $FIX/jarsrc >/dev/null; done" \
  -n "java-jar-50x" "for i in \$(seq 50); do $JAVA_HOME/bin/java -jar $D/java/build/jarsrc.jar find **.Main --root $FIX/jarsrc >/dev/null; done" \
  -n "java-aot-50x" "for i in \$(seq 50); do $JAVA_HOME/bin/java -XX:AOTCache=$D/java/build/jarsrc.aot -jar $D/java/build/jarsrc.jar find **.Main --root $FIX/jarsrc >/dev/null; done" \
  -n "java-native-50x" "for i in \$(seq 50); do $D/java/build/jarsrc-native find **.Main --root $FIX/jarsrc >/dev/null; done"

echo "done -- raw JSON under bench/results/raw/"
