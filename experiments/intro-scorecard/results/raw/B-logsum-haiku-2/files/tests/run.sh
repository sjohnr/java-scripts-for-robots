#!/bin/sh
# Usage: sh tests/run.sh path/to/logsum.jar
# Runs logsum against small fixtures and checks stdout, stderr and exit code.
JAR="$1"
TMP=$(mktemp -d)
trap 'rm -rf "$TMP"' EXIT
pass=0
fail=0

run() { java -jar "$JAR" "$@"; }

# check NAME EXPECTED_CODE EXPECTED_STDOUT_FILE EXPECTED_STDERR (string) -- args...
check() {
    name=$1; want_code=$2; want_out=$3; want_err=$4
    shift 4
    run "$@" >"$TMP/out" 2>"$TMP/err"
    code=$?
    ok=1
    [ "$code" = "$want_code" ] || { echo "FAIL $name: exit $code, want $want_code"; ok=0; }
    cmp -s "$TMP/out" "$want_out" || { echo "FAIL $name: stdout differs"; diff "$want_out" "$TMP/out"; ok=0; }
    [ "$(cat "$TMP/err")" = "$want_err" ] || { echo "FAIL $name: stderr was: $(cat "$TMP/err")"; ok=0; }
    if [ $ok = 1 ]; then pass=$((pass+1)); else fail=$((fail+1)); fi
}

cat >"$TMP/log" <<'LOG'
2026-01-01T10:00:00 INFO server started
2026-01-01T10:00:01 DEBUG  cache  warm
2026-01-01T10:00:02 ERROR request 3f2a9c1e failed
garbage line
2026-01-01T10:00:03 WARN disk 91 percent
2026-01-01T10:00:04 ERROR request 77 failed
2026-01-01T10:00:05 ERROR connection to db lost
2026-01-02T09:00:00 bogus INFO nope
2026-13-01T10:00:00 INFO bad date
2026-01-02T11:00:00 ERROR request 9001 failed
2026-01-02T11:00:01 ERROR 550e8400-e29b-41d4-a716-446655440000 timeout
2026-01-02T11:00:02 ERROR 550e8400-e29b-41d4-a716-446655440111 timeout
2026-01-02T11:00:03 ERROR deadbeef1 crashed
2026-01-02T11:00:04 ERROR connection to db lost
LOG

cat >"$TMP/want_default" <<'WANT'
total: 11
by level: DEBUG=1 INFO=1 WARN=1 ERROR=8
first: 2026-01-01T10:00:00
last: 2026-01-02T11:00:04
top errors:
  2 550e8400-e29b-41d4-a716-446655440000 timeout
  2 connection to db lost
  2 request 77 failed
  1 deadbeef1 crashed
  1 request 3f2a9c1e failed
WANT
printf '' >"$TMP/want_none"

# Summary of a file: "request 77"/"request 9001" share a group (<num>), the hex id is its own group,
# UUIDs collapse to <uuid>, and malformed lines are skipped.
check default 0 "$TMP/want_default" "" "$TMP/log"
# Same input on stdin via "-" and via no argument.
check stdin_dash 0 "$TMP/want_default" "" - <"$TMP/log"
check stdin_none 0 "$TMP/want_default" "" <"$TMP/log"

# --top limits the list; 0 leaves an empty list but keeps the header.
printf 'total: 11\nby level: DEBUG=1 INFO=1 WARN=1 ERROR=8\nfirst: 2026-01-01T10:00:00\nlast: 2026-01-02T11:00:04\ntop errors:\n' >"$TMP/want_top0"
check top0 0 "$TMP/want_top0" "" "$TMP/log" --top 0
printf 'total: 11\nby level: DEBUG=1 INFO=1 WARN=1 ERROR=8\nfirst: 2026-01-01T10:00:00\nlast: 2026-01-02T11:00:04\ntop errors:\n  2 550e8400-e29b-41d4-a716-446655440000 timeout\n' >"$TMP/want_top1"
check top1 0 "$TMP/want_top1" "" --top=1 "$TMP/log"

# --since is inclusive and filters before statistics.
printf 'total: 5\nby level: DEBUG=0 INFO=0 WARN=0 ERROR=5\nfirst: 2026-01-02T11:00:00\nlast: 2026-01-02T11:00:04\ntop errors:\n  2 550e8400-e29b-41d4-a716-446655440000 timeout\n  1 connection to db lost\n  1 deadbeef1 crashed\n  1 request 9001 failed\n' >"$TMP/want_since"
check since 0 "$TMP/want_since" "" "$TMP/log" --since 2026-01-02T11:00:00

# --level keeps only that level; no ERROR lines leaves an empty list.
printf 'total: 1\nby level: DEBUG=0 INFO=0 WARN=1 ERROR=0\nfirst: 2026-01-01T10:00:03\nlast: 2026-01-01T10:00:03\ntop errors:\n' >"$TMP/want_warn"
check level_warn 0 "$TMP/want_warn" "" "$TMP/log" --level WARN

# Empty input: zero counts, n/a bounds, empty list.
printf 'total: 0\nby level: DEBUG=0 INFO=0 WARN=0 ERROR=0\nfirst: n/a\nlast: n/a\ntop errors:\n' >"$TMP/want_empty"
check empty 0 "$TMP/want_empty" "" "$TMP/log" --level DEBUG --since 2030-01-01T00:00:00

# --help goes to stdout, exit 0.
run --help >"$TMP/help" 2>"$TMP/help_err"; hc=$?
if [ $hc = 0 ] && [ ! -s "$TMP/help_err" ] && grep -q -- --since "$TMP/help" && grep -q -- --level "$TMP/help" && grep -q -- --top "$TMP/help" && grep -q -- '--help' "$TMP/help"; then
    pass=$((pass+1)); else echo "FAIL help"; fail=$((fail+1)); fi

# Usage errors: exit 2, message on stderr, nothing on stdout.
check unknown_flag 2 "$TMP/want_none" "unknown flag: --verbose" "$TMP/log" --verbose
check bad_since 2 "$TMP/want_none" "error: invalid value for --since: 2026-01-01" "$TMP/log" --since 2026-01-01
check bad_level 2 "$TMP/want_none" "error: invalid value for --level: info" "$TMP/log" --level info
check bad_top 2 "$TMP/want_none" "error: invalid value for --top: -1" "$TMP/log" --top -1
check missing_file 2 "$TMP/want_none" "error: cannot read $TMP/nope.log" "$TMP/nope.log"

echo "passed: $pass  failed: $fail"
[ $fail = 0 ]
