#!/bin/sh
# Black-box tests for logsum. Run from anywhere after `make jar`.
set -u

ROOT=$(cd "$(dirname "$0")/.." && pwd)
BIN="$ROOT/bin/logsum"
FIX="$ROOT/test/fixtures"
TMP=$(mktemp -d)
trap 'rm -rf "$TMP"' EXIT

pass=0
fail=0

# check NAME EXPECTED_EXIT EXPECTED_STDOUT_FILE EXPECTED_STDERR_FILE -- COMMAND...
check() {
    name=$1; want_code=$2; want_out=$3; want_err=$4
    shift 4
    [ "$1" = "--" ] && shift
    "$@" >"$TMP/out" 2>"$TMP/err" </dev/null
    code=$?
    ok=1
    [ "$code" = "$want_code" ] || { echo "FAIL $name: exit $code, want $want_code"; ok=0; }
    cmp -s "$TMP/out" "$want_out" || { echo "FAIL $name: stdout differs"; diff "$want_out" "$TMP/out" | sed 's/^/    /'; ok=0; }
    cmp -s "$TMP/err" "$want_err" || { echo "FAIL $name: stderr differs"; diff "$want_err" "$TMP/err" | sed 's/^/    /'; ok=0; }
    if [ "$ok" = 1 ]; then pass=$((pass + 1)); else fail=$((fail + 1)); fi
}

# Helper: write literal text to a file.
expect() { printf '%s' "$2" >"$1"; }

# Expected files
expect "$TMP/e_empty" ""
expect "$TMP/e_none" ""

expect "$TMP/e_basic" "total: 11
by level: DEBUG=1 INFO=2 WARN=1 ERROR=7
first: 2024-01-01T10:00:00
last: 2024-01-01T10:00:19
top errors:
  2 connection to 10.0.0.5 failed
  2 request 3f2a9c1b8d7e failed
  2 timeout after 30 seconds
  1 bad  spacing here
"

expect "$TMP/e_error_top2" "total: 7
by level: DEBUG=0 INFO=0 WARN=0 ERROR=7
first: 2024-01-01T10:00:11
last: 2024-01-01T10:00:17
top errors:
  2 connection to 10.0.0.5 failed
  2 request 3f2a9c1b8d7e failed
"

expect "$TMP/e_since" "total: 6
by level: DEBUG=0 INFO=1 WARN=0 ERROR=5
first: 2024-01-01T10:00:13
last: 2024-01-01T10:00:19
top errors:
  2 request 3f2a9c1b8d7e failed
  2 timeout after 30 seconds
  1 bad  spacing here
"

expect "$TMP/e_top0" "total: 11
by level: DEBUG=1 INFO=2 WARN=1 ERROR=7
first: 2024-01-01T10:00:00
last: 2024-01-01T10:00:19
top errors:
"

expect "$TMP/e_zero_total" "total: 0
by level: DEBUG=0 INFO=0 WARN=0 ERROR=0
first: n/a
last: n/a
top errors:
"

expect "$TMP/e_err_since" "error: invalid value for --since: 2024-02-30T00:00:00
"
expect "$TMP/e_err_level" "error: invalid value for --level: error
"
expect "$TMP/e_err_top" "error: invalid value for --top: -1
"
expect "$TMP/e_unknown" "unknown flag: --bogus
"
expect "$TMP/e_missing" "error: cannot read /no/such/file.log
"
expect "$TMP/e_missing_value" "error: missing value for --top
"

# --help must list every flag and exit 0.
"$BIN" --help >"$TMP/help" 2>"$TMP/help_err"; help_code=$?
if [ "$help_code" = 0 ] && [ ! -s "$TMP/help_err" ] \
    && grep -q -- '--since' "$TMP/help" && grep -q -- '--level' "$TMP/help" \
    && grep -q -- '--top' "$TMP/help" && grep -q 'FILE' "$TMP/help"; then
    pass=$((pass + 1))
else
    echo "FAIL --help: exit $help_code or missing flags"; fail=$((fail + 1))
fi

check "basic file" 0 "$TMP/e_basic" "$TMP/e_empty" -- "$BIN" "$FIX/basic.log"
check "basic stdin" 0 "$TMP/e_basic" "$TMP/e_empty" -- sh -c "cat '$FIX/basic.log' | '$BIN'"
check "basic dash" 0 "$TMP/e_basic" "$TMP/e_empty" -- sh -c "cat '$FIX/basic.log' | '$BIN' -"
check "level ERROR, top 2" 0 "$TMP/e_error_top2" "$TMP/e_empty" -- "$BIN" "$FIX/basic.log" --level ERROR --top 2
check "since" 0 "$TMP/e_since" "$TMP/e_empty" -- "$BIN" "$FIX/basic.log" --since 2024-01-01T10:00:13
check "top 0" 0 "$TMP/e_top0" "$TMP/e_empty" -- "$BIN" "$FIX/basic.log" --top 0
check "empty input" 0 "$TMP/e_zero_total" "$TMP/e_empty" -- sh -c "'$BIN' </dev/null"
check "filter matches nothing" 0 "$TMP/e_zero_total" "$TMP/e_empty" -- "$BIN" "$FIX/basic.log" --since 2030-01-01T00:00:00
check "bad since" 2 "$TMP/e_none" "$TMP/e_err_since" -- "$BIN" "$FIX/basic.log" --since 2024-02-30T00:00:00
check "bad level" 2 "$TMP/e_none" "$TMP/e_err_level" -- "$BIN" "$FIX/basic.log" --level error
check "bad top" 2 "$TMP/e_none" "$TMP/e_err_top" -- "$BIN" "$FIX/basic.log" --top -1
check "unknown flag" 2 "$TMP/e_none" "$TMP/e_unknown" -- "$BIN" --bogus
check "missing file" 2 "$TMP/e_none" "$TMP/e_missing" -- "$BIN" /no/such/file.log
check "missing flag value" 2 "$TMP/e_none" "$TMP/e_missing_value" -- "$BIN" --top

echo "passed: $pass, failed: $fail"
[ "$fail" = 0 ]
