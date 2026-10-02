#!/usr/bin/env bash
# ./compare.sh <cli> <args...>
# Runs every available implementation of <cli> side by side: output
# equality (diffed against the first implementation that runs), timing
# (single-shot, not a benchmark -- use bench/ for real numbers), and a
# tiktoken-proxy token count of stdout.
set -uo pipefail
ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
CLI="${1:?usage: ./compare.sh <cli> <args...>}"
shift
ARGS=("$@")

: "${JAVA_HOME:=$HOME/.sdkman/candidates/java/25.0.4-tem}"

D="$ROOT/tools/$CLI"
declare -a NAMES CMDS
add() { NAMES+=("$1"); shift; CMDS+=("$*"); }

[ -x "$D/go/$CLI" ] && add "go" "$D/go/$CLI"
[ -f "$D/python/tool.py" ] && add "python" "python3 $D/python/tool.py"
[ -f "$D/node/tool.mjs" ] && add "node" "node $D/node/tool.mjs"
[ -f "$D/java/build/$CLI.jar" ] && add "java-jar" "$JAVA_HOME/bin/java -jar $D/java/build/$CLI.jar"
[ -f "$D/java/build/$CLI.aot" ] && add "java-aot" "$JAVA_HOME/bin/java -XX:AOTCache=$D/java/build/$CLI.aot -jar $D/java/build/$CLI.jar"
[ -x "$D/java/build/$CLI-native" ] && add "java-native" "$D/java/build/$CLI-native"

if [ "${#NAMES[@]}" -eq 0 ]; then
  echo "no implementations found for CLI '$CLI' under $D" >&2
  exit 1
fi

REF_OUT=""
echo "=== $CLI ${ARGS[*]} ==="
for i in "${!NAMES[@]}"; do
  name="${NAMES[$i]}"
  cmd="${CMDS[$i]}"
  t0=$(python3 -c 'import time; print(time.time())')
  out=$(eval "$cmd" "$(printf '%q ' "${ARGS[@]}")" 2>/tmp/compare_stderr_$$)
  code=$?
  t1=$(python3 -c 'import time; print(time.time())')
  ms=$(python3 -c "print(f'{($t1-$t0)*1000:.1f}')")
  err=$(cat /tmp/compare_stderr_$$; rm -f /tmp/compare_stderr_$$)
  tokens=$(python3 -c "
import sys
try:
    import tiktoken
    enc = tiktoken.get_encoding('o200k_base')
    print(len(enc.encode(sys.argv[1])))
except Exception:
    print('n/a')
" "$out")

  if [ -z "$REF_OUT" ]; then
    REF_OUT="$out"
    match="(reference)"
  elif [ "$out" == "$REF_OUT" ]; then
    match="match"
  else
    match="DIFFERS"
  fi

  printf "%-14s exit=%-3s time=%7sms tokens=%-6s output=%s\n" "$name" "$code" "$ms" "$tokens" "$match"
  if [ -n "$err" ]; then
    printf "  stderr: %s\n" "$err"
  fi
done
