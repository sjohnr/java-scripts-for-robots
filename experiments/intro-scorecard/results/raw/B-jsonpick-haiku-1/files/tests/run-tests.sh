#!/bin/bash
# Behavioural tests for jsonpick, written against SPEC.md. Run with `make test`.
set -u

here=$(cd "$(dirname "$0")" && pwd)
JP="$here/../bin/jsonpick"
tmp=$(mktemp -d)
trap 'rm -rf "$tmp"' EXIT
pass=0
fail=0

# expect NAME INPUT WANT_STDOUT WANT_STDERR WANT_CODE [ARGS...]
# Compares stdout, stderr and exit code byte-for-byte.
expect() {
  local name=$1 input=$2 want_out=$3 want_err=$4 want_code=$5
  shift 5
  printf '%s' "$input" | "$JP" "$@" >"$tmp/out" 2>"$tmp/err"
  local code=$?
  printf '%s' "$want_out" >"$tmp/want_out"
  printf '%s' "$want_err" >"$tmp/want_err"
  if cmp -s "$tmp/out" "$tmp/want_out" && cmp -s "$tmp/err" "$tmp/want_err" && [ "$code" -eq "$want_code" ]; then
    pass=$((pass + 1))
  else
    fail=$((fail + 1))
    echo "FAIL: $name (exit $code, want $want_code)"
    echo "  stdout: $(sed -n l "$tmp/out")"
    echo "  want:   $(sed -n l "$tmp/want_out")"
    echo "  stderr: $(sed -n l "$tmp/err")"
    echo "  want:   $(sed -n l "$tmp/want_err")"
  fi
}

# expect_err NAME INPUT WANT_CODE REGEX [ARGS...]
# For cases where the stderr reason text is free-form: stdout must be empty and stderr must match REGEX.
expect_err() {
  local name=$1 input=$2 want_code=$3 regex=$4
  shift 4
  [ $# -eq 0 ] && set -- a
  printf '%s' "$input" | "$JP" "$@" >"$tmp/out" 2>"$tmp/err"
  local code=$?
  if [ ! -s "$tmp/out" ] && [ "$code" -eq "$want_code" ] && grep -Eq "$regex" "$tmp/err" \
      && [ "$(wc -l <"$tmp/err")" -eq 1 ]; then
    pass=$((pass + 1))
  else
    fail=$((fail + 1))
    echo "FAIL: $name (exit $code, want $want_code)"
    echo "  stdout: $(sed -n l "$tmp/out")"
    echo "  stderr: $(sed -n l "$tmp/err")"
  fi
}

printf '{"a":[1]}' >"$tmp/doc.json"

# --- Values -----------------------------------------------------------------
expect "string value" '{"a":"hi"}' $'hi\n' '' 0 a
expect "string escapes printed raw" '{"a":"x\"y\\z\nw"}' $'x"y\\z\nw\n' '' 0 a
expect "unicode printed as UTF-8" '{"a":"héllo ☃"}' $'héllo ☃\n' '' 0 a
expect "surrogate pair decoded" '{"a":"😀"}' $'😀\n' '' 0 a
expect "integer" '{"a":100}' $'100\n' '' 0 a
expect "trailing zeros dropped" '{"a":1.50}' $'1.5\n' '' 0 a
expect "exponent expanded" '{"a":1e3}' $'1000\n' '' 0 a
expect "negative zero" '{"a":-0.0}' $'0\n' '' 0 a
expect "small fraction" '{"a":0.000001}' $'0.000001\n' '' 0 a
expect "negative fraction with exponent" '{"a":-12.5e-1}' $'-1.25\n' '' 0 a
expect "true" '{"a":true}' $'true\n' '' 0 a
expect "null" '{"a":null}' $'null\n' '' 0 a
expect "container compact, document order" '{ "b" : 1 , "a" : [1, 2.0, {"x": null}] }' \
  $'{"b":1,"a":[1,2,{"x":null}]}\n' '' 0 .
expect "empty object" '{"a":{},"b":[]}' $'{}\n' '' 0 a
expect "empty array" '{"a":{},"b":[]}' $'[]\n' '' 0 b
expect "control characters escaped in compact output" '{"a":["\u0001\t"]}' \
  $'["\\u0001\\t"]\n' '' 0 a
expect "duplicate key: last value wins" '{"a":1,"a":2}' $'2\n' '' 0 a
expect "CRLF and surrounding whitespace" $'\r\n {"a":1}\r\n' $'1\n' '' 0 a

# --- Paths ------------------------------------------------------------------
nested='{"a":{"b":[[1,2],{"c":"deep"}]}}'
expect "mixed key and index path" "$nested" $'deep\n' '' 0 a.b[1].c
expect "chained indexes" "$nested" $'2\n' '' 0 a.b[0][1]
expect "empty path is root" '[1,"x"]' $'[1,"x"]\n' '' 0 ""
expect "dot path is root" '[1]' $'[1]\n' '' 0 .
expect "leading dot" '{"a":5}' $'5\n' '' 0 .a
expect "root array index" '[7,8]' $'8\n' '' 0 "[1]"
expect "index 0 of root array with leading index" '[[9]]' $'[9]\n' '' 0 "[0]"

# --- Not found (exit 1) -----------------------------------------------------
expect "missing key" '{"a":1}' '' $'error: path not found: b\n' 1 b
expect "index out of range" '[1]' '' $'error: path not found: [1]\n' 1 "[1]"
expect "huge index" '[1]' '' $'error: path not found: [99999999999]\n' 1 "[99999999999]"
expect "index into object" '{"a":1}' '' $'error: path not found: [0]\n' 1 "[0]"
expect "key into array" '[1]' '' $'error: path not found: a\n' 1 a
expect "key into scalar" '{"a":1}' '' $'error: path not found: a.b\n' 1 a.b
expect "key into null" '{"a":null}' '' $'error: path not found: a.b\n' 1 a.b
expect "not found under --shape" '{"a":1}' '' $'error: path not found: z\n' 1 z --shape

# --- Shape ------------------------------------------------------------------
doc='{"a":[{"x":1,"y":"s"}],"b":null}'
expect "shape default depth 2" "$doc" $'object{a: array[1] of object{2}, b: null}\n' '' 0 . --shape
expect "shape depth 3" "$doc" \
  $'object{a: array[1] of object{x: number, y: string}, b: null}\n' '' 0 . --shape --depth 3
expect "shape depth 0" "$doc" $'object{2}\n' '' 0 . --shape --depth 0
expect "shape --depth=N form" "$doc" $'object{a: array[1], b: null}\n' '' 0 . --shape --depth=1
expect "shape empty array" '{"a":[]}' $'array[0]\n' '' 0 a --shape
expect "shape empty array at depth 0" '{"a":[1,2,3]}' $'array[3]\n' '' 0 a --shape --depth 0
expect "shape empty object" '{"a":{}}' $'object{}\n' '' 0 a --shape
expect "shape empty object at depth 0" '{"a":{}}' $'object{0}\n' '' 0 a --shape --depth 0
expect "shape scalar" '{"a":"x"}' $'string\n' '' 0 a --shape
expect "shape nested arrays" '[[1]]' $'array[1] of array[1] of number\n' '' 0 . --shape

# --- Usage errors (exit 2) --------------------------------------------------
expect "unknown long flag" '{}' '' $'unknown flag: --foo\n' 2 --foo a
expect "unknown short flag" '{}' '' $'unknown flag: -x\n' 2 -x a
expect "missing PATH" '{}' '' $'error: missing required argument: PATH\n' 2 --shape
expect "extra positional" '' '' $'error: unexpected argument: c\n' 2 a b c
expect "--depth without value" '{}' '' $'error: --depth requires a value\n' 2 a --depth
expect "negative --depth" '{}' '' $'error: --depth must be a non-negative integer: -1\n' 2 a --shape --depth -1
expect "non-numeric --depth" '{}' '' $'error: --depth must be a non-negative integer: x\n' 2 a --shape --depth x
expect "--shape with value" '{}' '' $'error: --shape takes no value\n' 2 a --shape=1
expect "file not found" '' '' "error: cannot read file: $tmp/nope.json"$'\n' 2 a "$tmp/nope.json"
for bad in 'a..b' 'a.' 'a[' 'a[x]' 'a[]' 'a[0]b' 'a.[0]' '..a'; do
  expect "malformed path $bad" '{}' '' "error: invalid path: $bad"$'\n' 2 "$bad"
done
expect_err "empty input" '' 2 '^error: invalid JSON: line 1, column 1: unexpected end of input$'
expect_err "unclosed object" '{' 2 '^error: invalid JSON: line [0-9]+, column [0-9]+: .+$'
expect_err "trailing content" '1 2' 2 '^error: invalid JSON: line 1, column 3: unexpected content.*$'
expect_err "leading zero" '01' 2 '^error: invalid JSON: line 1, column 2: .+$'
expect_err "trailing comma" '[1,]' 2 '^error: invalid JSON: line 1, column 4: .+$'
expect_err "bad literal" 'tru' 2 '^error: invalid JSON: line 1, column 1: .+$'
expect_err "unterminated string" '"abc' 2 '^error: invalid JSON: line 1, column 5: .+$'
expect_err "raw control char in string" $'"a\x01"' 2 '^error: invalid JSON: line 1, column 3: .+$'
expect_err "bad escape" '"\x"' 2 '^error: invalid JSON: line 1, column 3: .+$'
expect_err "bad unicode escape" '"\u12G4"' 2 '^error: invalid JSON: line 1, column 6: .+$'
expect_err "error on line 3" $'{\n"a":\n}' 2 '^error: invalid JSON: line 3, column 1: .+$'
expect_err "invalid UTF-8" $'{"a":"\xff"}' 2 '^error: invalid JSON: invalid UTF-8 at byte offset 6$'
expect_err "nesting too deep" "$(printf '%1001s' '' | tr ' ' '[')" 2 '^error: invalid JSON: .*nesting too deep.*$'

deep_open=$(printf '%1000s' '' | tr ' ' '[')
deep_close=$(printf '%1000s' '' | tr ' ' ']')
expect "1000 levels of nesting accepted" "$deep_open$deep_close" "$deep_open$deep_close"$'\n' '' 0 ""

# --- Input sources and option handling --------------------------------------
expect "file argument" '' $'[1]\n' '' 0 a "$tmp/doc.json"
expect "dash means stdin" '{"a":1}' $'1\n' '' 0 a -
expect "-- ends options" '{"-a":3}' $'3\n' '' 0 -- -a

# --- Help -------------------------------------------------------------------
help=$("$JP" --help </dev/null; echo "exit=$?")
if [[ "$help" == *"exit=0" ]] && [[ "$help" == *PATH* ]] && [[ "$help" == *FILE* ]] \
    && [[ "$help" == *--shape* ]] && [[ "$help" == *--depth* ]] && [[ "$help" == *--help* ]]; then
  pass=$((pass + 1))
else
  fail=$((fail + 1))
  echo "FAIL: --help lists flags and exits 0"
fi

echo "passed: $pass, failed: $fail"
[ "$fail" -eq 0 ]
