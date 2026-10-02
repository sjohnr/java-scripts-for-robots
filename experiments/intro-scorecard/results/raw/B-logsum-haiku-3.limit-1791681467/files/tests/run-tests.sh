#!/usr/bin/env bash
# End-to-end checks for logsum: stdout, stderr and exit code must match exactly.
# Run ./build.sh first.
set -uo pipefail
cd "$(dirname "$0")/.."
LOGSUM="$PWD/bin/logsum"
tmp="$(mktemp -d)"
trap 'rm -rf "$tmp"' EXIT
pass=0
fail=0

# check NAME EXIT STDOUT STDERR STDIN ARGS...
# STDOUT, STDERR and STDIN are passed as exact strings (printf %s semantics).
check() {
  local name=$1 want_code=$2 want_out=$3 want_err=$4 input=$5
  shift 5
  printf '%s' "$input" | "$LOGSUM" "$@" >"$tmp/out" 2>"$tmp/err"
  local code=$?
  local ok=1
  [[ $code -eq $want_code ]] || { ok=0; echo "  exit: want $want_code got $code"; }
  printf '%s' "$want_out" | cmp -s - "$tmp/out" || { ok=0; echo "  stdout differs:"; diff <(printf '%s' "$want_out") "$tmp/out" | sed 's/^/    /'; }
  printf '%s' "$want_err" | cmp -s - "$tmp/err" || { ok=0; echo "  stderr differs:"; diff <(printf '%s' "$want_err") "$tmp/err" | sed 's/^/    /'; }
  if [[ $ok -eq 1 ]]; then
    pass=$((pass + 1))
  else
    fail=$((fail + 1))
    echo "FAIL: $name"
  fi
}

LOG=$'2026-01-01T10:00:00 INFO server started\n'
LOG+=$'2026-01-01T10:00:01 DEBUG cache warm\n'
LOG+=$'2026-01-01T10:00:02 WARN disk 91% full\n'
LOG+=$'2026-01-01T10:00:03 ERROR timeout after 30 seconds\n'
LOG+=$'2026-01-01T10:00:04 ERROR timeout after 45 seconds\n'
LOG+=$'2026-01-01T10:00:05 ERROR user 3f2a9c1b not found\n'
LOG+=$'2026-01-01T10:00:06 ERROR user 7d1e0a44 not found\n'
LOG+=$'2026-01-01T10:00:07 ERROR user 7d1e0a44 not found\n'
LOG+=$'garbage line\n'
LOG+=$'2026-01-01T10:00:08 info lowercase level skipped\n'
LOG+=$'2026-02-30T00:00:00 INFO impossible date skipped\n'
LOG+=$'2026-01-01T10:00:09 ERROR spaced   message   \n'

BASIC_OUT=$'total: 9\nby level: DEBUG=1 INFO=1 WARN=1 ERROR=6\nfirst: 2026-01-01T10:00:00\nlast: 2026-01-01T10:00:09\ntop errors:\n  3 user 3f2a9c1b not found\n  2 timeout after 30 seconds\n  1 spaced   message\n'

EMPTY_OUT=$'total: 0\nby level: DEBUG=0 INFO=0 WARN=0 ERROR=0\nfirst: n/a\nlast: n/a\ntop errors:\n'

check "stdin default" 0 "$BASIC_OUT" "" "$LOG"
check "stdin via dash" 0 "$BASIC_OUT" "" "$LOG" -
printf '%s' "$LOG" >"$tmp/app.log"
check "file argument" 0 "$BASIC_OUT" "" "" "$tmp/app.log"

check "since and level filters" 0 \
  $'total: 4\nby level: DEBUG=0 INFO=0 WARN=0 ERROR=4\nfirst: 2026-01-01T10:00:05\nlast: 2026-01-01T10:00:09\ntop errors:\n  3 user 3f2a9c1b not found\n  1 spaced   message\n' \
  "" "$LOG" --since 2026-01-01T10:00:05 --level ERROR

check "top 1" 0 \
  $'total: 9\nby level: DEBUG=1 INFO=1 WARN=1 ERROR=6\nfirst: 2026-01-01T10:00:00\nlast: 2026-01-01T10:00:09\ntop errors:\n  3 user 3f2a9c1b not found\n' \
  "" "$LOG" --top 1

check "top 0" 0 \
  $'total: 9\nby level: DEBUG=1 INFO=1 WARN=1 ERROR=6\nfirst: 2026-01-01T10:00:00\nlast: 2026-01-01T10:00:09\ntop errors:\n' \
  "" "$LOG" --top=0

check "empty input" 0 "$EMPTY_OUT" "" ""

check "ties sort by message ascending" 0 \
  $'total: 4\nby level: DEBUG=0 INFO=0 WARN=0 ERROR=4\nfirst: 2026-01-01T10:00:00\nlast: 2026-01-01T10:00:03\ntop errors:\n  2 beta\n  1 alpha\n  1 zeta\n' \
  $'2026-01-01T10:00:00 ERROR zeta\n2026-01-01T10:00:01 ERROR alpha\n2026-01-01T10:00:02 ERROR beta\n2026-01-01T10:00:03 ERROR beta\n' \
  "" 

check "normalization: uuid, numbers, hex" 0 \
  $'total: 5\nby level: DEBUG=0 INFO=0 WARN=0 ERROR=5\nfirst: 2026-01-01T10:00:00\nlast: 2026-01-01T10:00:04\ntop errors:\n  2 req 123e4567-e89b-12d3-a456-426614174000 failed\n  2 retry 3 of 10\n  1 hash deadbeef42 seen\n' \
  $'2026-01-01T10:00:00 ERROR req 123e4567-e89b-12d3-a456-426614174000 failed\n2026-01-01T10:00:01 ERROR req 123E4567-E89B-12D3-A456-426614174001 failed\n2026-01-01T10:00:02 ERROR retry 3 of 10\n2026-01-01T10:00:03 ERROR retry 4 of 10\n2026-01-01T10:00:04 ERROR hash deadbeef42 seen\n' \
  ""

check "CRLF input" 0 \
  $'total: 2\nby level: DEBUG=0 INFO=1 WARN=0 ERROR=1\nfirst: 2026-01-01T10:00:00\nlast: 2026-01-01T10:00:01\ntop errors:\n  1 boom\n' \
  $'2026-01-01T10:00:00 ERROR boom\r\n2026-01-01T10:00:01 INFO ok\r\n' \
  ""

check "help" 0 "$(sed -n '/^Usage/,$p' src/logsum/Main.java >/dev/null; "$LOGSUM" --help)"$'\n' "" "" --help

check "unknown flag" 2 "" $'unknown flag: --bogus\n' "" --bogus
check "bad since" 2 "" $'error: invalid value for --since: 2026-13-01T00:00:00\n' "" --since 2026-13-01T00:00:00
check "bad level" 2 "" $'error: invalid value for --level: info\n' "" --level info
check "bad top" 2 "" $'error: invalid value for --top: -1\n' "" --top -1
check "missing value" 2 "" $'error: missing value for --since\n' "" --since
check "missing file" 2 "" $'error: cannot read /nonexistent/x.log\n' "" /nonexistent/x.log

echo "passed: $pass, failed: $fail"
[[ $fail -eq 0 ]]
