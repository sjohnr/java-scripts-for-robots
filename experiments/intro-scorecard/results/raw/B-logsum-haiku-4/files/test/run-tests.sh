#!/usr/bin/env bash
# Behavioural tests for logsum, checked against SPEC.md. Run from anywhere.
set -u

cd "$(dirname "$0")/.."
JAR=build/logsum.jar
SAMPLE=test/sample.log
[ -f "$JAR" ] || ./build.sh >/dev/null || exit 1

WORK=$(mktemp -d)
trap 'rm -rf "$WORK"' EXIT
pass=0
failures=0

# Writes TEXT plus one trailing LF to FILE, or an empty FILE when TEXT is empty.
text_file() {
  if [ -n "$1" ]; then printf '%s\n' "$1" >"$2"; else : >"$2"; fi
}

# run NAME WANT_EXIT WANT_STDOUT WANT_STDERR STDIN_FILE ARGS...
run() {
  local name=$1 want_code=$2 want_out=$3 want_err=$4 stdin=$5
  shift 5
  text_file "$want_out" "$WORK/want.out"
  text_file "$want_err" "$WORK/want.err"
  java -jar "$JAR" "$@" <"$stdin" >"$WORK/got.out" 2>"$WORK/got.err"
  local code=$?
  local ok=1
  [ "$code" -eq "$want_code" ] || ok=0
  cmp -s "$WORK/want.out" "$WORK/got.out" || ok=0
  cmp -s "$WORK/want.err" "$WORK/got.err" || ok=0
  # Output rules: no trailing whitespace, no CR, LF-terminated.
  if grep -q '[[:space:]]$' "$WORK/got.out" "$WORK/got.err" || grep -q $'\r' "$WORK/got.out" "$WORK/got.err"; then
    ok=0
    echo "  (output has trailing whitespace or CR)"
  fi
  if [ "$ok" -eq 1 ]; then
    pass=$((pass + 1))
  else
    failures=$((failures + 1))
    echo "FAIL: $name (exit $code, want $want_code)"
    echo "--- want stdout / got stdout"
    diff "$WORK/want.out" "$WORK/got.out"
    echo "--- want stderr / got stderr"
    diff "$WORK/want.err" "$WORK/got.err"
  fi
}

DEFAULT_OUT=$(cat <<'EOF'
total: 7
by level: DEBUG=1 INFO=1 WARN=1 ERROR=4
first: 2024-05-01T10:00:00
last: 2024-05-01T10:00:08
top errors:
  2 request 1234 failed for user 42
  1 conn 3f2a9c1b reset
  1 connection to db 2024-05-01 lost
EOF
)

FILTERED_ERROR_OUT=$(cat <<'EOF'
total: 3
by level: DEBUG=0 INFO=0 WARN=0 ERROR=3
first: 2024-05-01T10:00:03
last: 2024-05-01T10:00:08
top errors:
  1 conn 3f2a9c1b reset
  1 connection to db 2024-05-01 lost
  1 request 5678 failed for user 99
EOF
)

# Fixture for UUID / hex / number normalization, tie ordering, and whitespace handling.
NORM_LOG="$WORK/norm.log"
printf '%s\n' \
  '2024-06-01T00:00:00 ERROR id 123e4567-e89b-12d3-a456-426614174000 missing' \
  '2024-06-01T00:00:01 ERROR id 987fcdeb-51a2-43f7-9abc-def012345678 missing' \
  '2024-06-01T00:00:02 ERROR blob deadbeef12 corrupt' \
  '2024-06-01T00:00:03 ERROR blob 0badf00d corrupt' \
  '2024-06-01T00:00:04 ERROR retry 12345 after 99' \
  '2024-06-01T00:00:05 ERROR retry 67890 after 7' \
  '2024-06-01T00:00:06 ERROR   spaced  out   ' \
  '2024-06-01T00:00:07 ERROR    ' >"$NORM_LOG"

NORM_OUT=$(cat <<'EOF'
total: 7
by level: DEBUG=0 INFO=0 WARN=0 ERROR=7
first: 2024-06-01T00:00:00
last: 2024-06-01T00:00:06
top errors:
  2 blob deadbeef12 corrupt
  2 id 123e4567-e89b-12d3-a456-426614174000 missing
  2 retry 12345 after 99
  1   spaced  out
EOF
)

EMPTY_OUT=$(cat <<'EOF'
total: 0
by level: DEBUG=0 INFO=0 WARN=0 ERROR=0
first: n/a
last: n/a
top errors:
EOF
)

# --- success paths -------------------------------------------------------
run "file argument" 0 "$DEFAULT_OUT" "" /dev/null "$SAMPLE"
run "stdin via dash" 0 "$DEFAULT_OUT" "" "$SAMPLE" -
run "stdin when FILE omitted" 0 "$DEFAULT_OUT" "" "$SAMPLE"
run "--top 1" 0 "$(printf '%s\n' "$DEFAULT_OUT" | head -n 6)" "" /dev/null "$SAMPLE" --top 1
run "--top 0 keeps header" 0 "$(printf '%s\n' "$DEFAULT_OUT" | head -n 5)" "" /dev/null "$SAMPLE" --top 0
run "--since and --level" 0 "$FILTERED_ERROR_OUT" "" /dev/null "$SAMPLE" --level ERROR --since 2024-05-01T10:00:03
run "inline --flag=value form" 0 "$FILTERED_ERROR_OUT" "" /dev/null "$SAMPLE" --level=ERROR --since=2024-05-01T10:00:03 --top=5
run "level with no errors" 0 "total: 1
by level: DEBUG=0 INFO=1 WARN=0 ERROR=0
first: 2024-05-01T10:00:00
last: 2024-05-01T10:00:00
top errors:" "" /dev/null "$SAMPLE" --level INFO
run "filter removes everything" 0 "$EMPTY_OUT" "" /dev/null "$SAMPLE" --since 2030-01-01T00:00:00
run "normalization, ordering, whitespace" 0 "$NORM_OUT" "" "$NORM_LOG" "$NORM_LOG"
run "empty input" 0 "$EMPTY_OUT" "" /dev/null
run "-- ends flags" 0 "$DEFAULT_OUT" "" /dev/null -- "$SAMPLE"

# --- usage errors (exit 2) -----------------------------------------------
run "unknown flag" 2 "" "unknown flag: --bogus" /dev/null --bogus "$SAMPLE"
run "bad --since" 2 "" "error: invalid value for --since: 2024-13-01T00:00:00" /dev/null --since 2024-13-01T00:00:00
run "impossible --since date" 2 "" "error: invalid value for --since: 2024-02-30T00:00:00" /dev/null --since 2024-02-30T00:00:00
run "bad --level" 2 "" "error: invalid value for --level: info" /dev/null --level info
run "bad --top" 2 "" "error: invalid value for --top: -1" /dev/null --top -1
run "missing --top value" 2 "" "error: missing value for --top" /dev/null --top
run "extra positional" 2 "" "error: unexpected argument: other.log" /dev/null "$SAMPLE" other.log
run "missing file" 2 "" "error: cannot read /nonexistent/none.log" /dev/null /nonexistent/none.log

# --- --help (exit 0, must list every flag) --------------------------------
help_out="$WORK/help.out"
java -jar "$JAR" --help >"$help_out" 2>"$WORK/help.err"
help_code=$?
help_ok=1
[ "$help_code" -eq 0 ] || help_ok=0
[ -s "$WORK/help.err" ] && help_ok=0
for flag in FILE --since --level --top --help; do
  grep -q -- "$flag" "$help_out" || { help_ok=0; echo "  (help is missing $flag)"; }
done
if [ "$help_ok" -eq 1 ]; then
  pass=$((pass + 1))
else
  failures=$((failures + 1))
  echo "FAIL: --help"
fi

echo "passed: $pass  failed: $failures"
[ "$failures" -eq 0 ]
