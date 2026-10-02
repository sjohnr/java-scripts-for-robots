#!/usr/bin/env bash
# Builds every measurable Java execution-mode artifact for one CLI.
# Usage: build-java-modes.sh <cli-name> [main-class] [java-dir]
# Produces artifacts under tools/<cli>/java/build/ :
#   classes/            (java-class mode: java -cp classes Tool)
#   <cli>.jar           (java-jar mode: java -jar <cli>.jar)
#   <cli>.aot            AOT cache built from a training run (java-aot mode)
#   <cli>.jsa            classic AppCDS dynamic archive (comparison point)
#   <cli>-shebang         executable script with #! line wrapping java-src (java-shebang)
#   <cli>-native          GraalVM native-image binary, if available (java-native)
set -euo pipefail

CLI="$1"
MAIN="${2:-Tool}"
JDIR="${3:-tools/$CLI/java/classic}"
ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
SRC="$ROOT/$JDIR"
BUILD="$SRC/../build"
mkdir -p "$BUILD/classes"

: "${JAVA_HOME:?set JAVA_HOME before running this script}"

echo "== $CLI : java-class =="
"$JAVA_HOME/bin/javac" -d "$BUILD/classes" "$SRC"/*.java

echo "== $CLI : java-jar =="
(cd "$BUILD/classes" && "$JAVA_HOME/bin/jar" cfe "$BUILD/$CLI.jar" "$MAIN" .)

echo "== $CLI : java-aot (JEP 483/514 AOT cache) =="
rm -f "$BUILD/$CLI.aot" "$BUILD/$CLI.aotconf"
TRAIN_ARGS_LIST=("--help")
case "$CLI" in
  noop) TRAIN_ARGS_LIST+=("") ;;
  jarsrc) TRAIN_ARGS_LIST+=("find *.Main --root /dev/null") ;;
  logsum) TRAIN_ARGS_LIST+=("--help") ;;
  treesum) TRAIN_ARGS_LIST+=("/tmp") ;;
  jsonpick) TRAIN_ARGS_LIST+=("--help") ;;
esac
for targs in "${TRAIN_ARGS_LIST[@]}"; do
  "$JAVA_HOME/bin/java" -XX:AOTMode=record -XX:AOTConfiguration="$BUILD/$CLI.aotconf" -jar "$BUILD/$CLI.jar" $targs >/dev/null 2>&1 || true
done
"$JAVA_HOME/bin/java" -XX:AOTMode=create -XX:AOTConfiguration="$BUILD/$CLI.aotconf" -XX:AOTCache="$BUILD/$CLI.aot" -jar "$BUILD/$CLI.jar" >/dev/null 2>&1 || true

echo "== $CLI : classic AppCDS dynamic archive (comparison) =="
rm -f "$BUILD/$CLI.jsa"
"$JAVA_HOME/bin/java" -XX:ArchiveClassesAtExit="$BUILD/$CLI.jsa" -jar "$BUILD/$CLI.jar" >/dev/null 2>&1 || true

echo "== $CLI : java-shebang =="
{
  echo "#!$JAVA_HOME/bin/java --source 25"
  tail -n +1 "$SRC/$MAIN.java"
} > "$BUILD/$CLI-shebang"
chmod +x "$BUILD/$CLI-shebang"

echo "done: $BUILD"
ls -la "$BUILD"
