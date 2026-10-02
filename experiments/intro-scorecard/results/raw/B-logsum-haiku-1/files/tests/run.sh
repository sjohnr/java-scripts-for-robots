#!/bin/sh
# Golden tests for logsum. Usage: sh tests/run.sh build/logsum.jar
set -u

JAR=${1:?usage: sh tests/run.sh JAR}
DIR=$(cd "$(dirname "$0")" && pwd)
FIXTURE=$DIR/fixtures/sample.log
TMP=$(mktemp -d) || exit 1
trap 'rm -rf "$TMP"' EXIT
NAME=
FAILURES=0

fail() {
    echo "FAIL $NAME: $1"
    FAILURES=$((FAILURES + 1))
}

# run NAME EXIT_CODE STDIN_FILE ARGS...: runs logsum, capturing stdout and stderr.
run() {
    NAME=$1
    want_code=$2
    input=$3
    shift 3
    java -jar "$JAR" "$@" < "$input" > "$TMP/out" 2> "$TMP/err"
    code=$?
    [ "$code" -eq "$want_code" ] || fail "exit $code, want $want_code"
}

# expect_out / expect_err: compare captured output with the heredoc on stdin.
expect_out() {
    cat > "$TMP/want"
    cmp -s "$TMP/want" "$TMP/out" || { fail "stdout differs"; diff "$TMP/want" "$TMP/out"; }
}
expect_err() {
    cat > "$TMP/want"
    cmp -s "$TMP/want" "$TMP/err" || { fail "stderr differs"; diff "$TMP/want" "$TMP/err"; }
}

run "default options" 0 /dev/null "$FIXTURE"
expect_out <<'EOF'
total: 11
by level: DEBUG=1 INFO=1 WARN=1 ERROR=8
first: 2024-03-01T10:00:00
last: 2024-03-01T10:06:01
top errors:
  3 request 42 failed: timeout after 3000 ms
  1 alpha happened
  1 connection 3f9a2b1c refused
  1 disk  full   on /dev/sda1
  1 user 0a1b2c3d-0000-4000-8000-00000000abcd missing
EOF
expect_err </dev/null

run "stdin via dash, --top 2" 0 "$FIXTURE" --top 2 -
expect_out <<'EOF'
total: 11
by level: DEBUG=1 INFO=1 WARN=1 ERROR=8
first: 2024-03-01T10:00:00
last: 2024-03-01T10:06:01
top errors:
  3 request 42 failed: timeout after 3000 ms
  1 alpha happened
EOF
expect_err </dev/null

# --since is inclusive (10:02:30 itself is kept); request 42/77 are excluded.
run "--since inclusive, --level ERROR on stdin" 0 "$FIXTURE" --level ERROR --since=2024-03-01T10:02:30
expect_out <<'EOF'
total: 6
by level: DEBUG=0 INFO=0 WARN=0 ERROR=6
first: 2024-03-01T10:02:30
last: 2024-03-01T10:06:01
top errors:
  1 alpha happened
  1 connection 3f9a2b1c refused
  1 disk  full   on /dev/sda1
  1 request 9 failed: timeout after 3000 ms
  1 user 0a1b2c3d-0000-4000-8000-00000000abcd missing
EOF
expect_err </dev/null

run "--level WARN leaves no ERROR lines" 0 /dev/null --level WARN "$FIXTURE"
expect_out <<'EOF'
total: 1
by level: DEBUG=0 INFO=0 WARN=1 ERROR=0
first: 2024-03-01T10:02:00
last: 2024-03-01T10:02:00
top errors:
EOF
expect_err </dev/null

run "empty input" 0 /dev/null -
expect_out <<'EOF'
total: 0
by level: DEBUG=0 INFO=0 WARN=0 ERROR=0
first: n/a
last: n/a
top errors:
EOF
expect_err </dev/null

run "filter removes everything" 0 "$FIXTURE" --since 2025-01-01T00:00:00 -
expect_out <<'EOF'
total: 0
by level: DEBUG=0 INFO=0 WARN=0 ERROR=0
first: n/a
last: n/a
top errors:
EOF
expect_err </dev/null

run "--help" 0 /dev/null --help
expect_err </dev/null
for flag in FILE --since --level --top; do
    grep -q -- "$flag" "$TMP/out" || fail "help does not mention $flag"
done

run "unknown flag" 2 /dev/null --bogus "$FIXTURE"
expect_out </dev/null
expect_err <<'EOF'
unknown flag: --bogus
EOF

run "invalid --since" 2 /dev/null --since 2024-02-30T00:00:00 "$FIXTURE"
expect_out </dev/null
expect_err <<'EOF'
error: invalid value for --since: 2024-02-30T00:00:00
EOF

run "invalid --level (case-sensitive)" 2 /dev/null --level info "$FIXTURE"
expect_out </dev/null
expect_err <<'EOF'
error: invalid value for --level: info
EOF

run "invalid --top" 2 /dev/null --top 0 "$FIXTURE"
expect_out </dev/null
expect_err <<'EOF'
error: invalid value for --top: 0
EOF

run "missing file" 2 /dev/null /no/such/file.log
expect_out </dev/null
expect_err <<'EOF'
error: cannot read /no/such/file.log
EOF

run "missing flag value" 2 /dev/null "$FIXTURE" --since
expect_out </dev/null
expect_err <<'EOF'
error: missing value for --since
EOF

run "two files" 2 /dev/null "$FIXTURE" "$FIXTURE"
expect_out </dev/null
expect_err <<EOF
error: unexpected argument: $FIXTURE
EOF

if [ "$FAILURES" -eq 0 ]; then
    echo "all tests passed"
else
    echo "$FAILURES failure(s)"
    exit 1
fi
