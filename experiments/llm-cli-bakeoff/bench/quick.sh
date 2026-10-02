#!/usr/bin/env bash
# Quick startup-floor comparison (~2 minutes). For the full sweep used to
# produce REPORT.md's tables, see full.sh.
set -euo pipefail
ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
cd "$ROOT"

: "${JAVA_HOME:?set JAVA_HOME to a JDK 25 install, e.g. \$HOME/.sdkman/candidates/java/25.0.4-tem}"

command -v hyperfine >/dev/null || { echo "hyperfine not found (brew install hyperfine)"; exit 1; }

D=tools/noop
hyperfine --warmup 2 --min-runs 15 \
  -n "go" "$D/go/noop" \
  -n "python" "python3 $D/python/tool.py" \
  -n "node" "node $D/node/tool.mjs" \
  -n "java-jar" "$JAVA_HOME/bin/java -jar $D/java/build/noop.jar" \
  -n "java-aot" "$JAVA_HOME/bin/java -XX:AOTCache=$D/java/build/noop.aot -jar $D/java/build/noop.jar" \
  -n "java-src (uncached)" "$JAVA_HOME/bin/java $D/java/classic/Tool.java"
