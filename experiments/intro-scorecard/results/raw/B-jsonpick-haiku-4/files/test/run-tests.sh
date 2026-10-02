#!/usr/bin/env bash
# Black-box tests for bin/jsonpick: each case checks exit code, stdout and stderr exactly.
set -u

ROOT="$(cd "$(dirname "$0")/.." && pwd)"
JP="$ROOT/bin/jsonpick"
TMP="$(mktemp -d)"
trap 'rm -rf "$TMP"' EXIT

pass=0
fail=0

# check NAME INPUT WANT_EXIT WANT_STDOUT WANT_STDERR ARGS...
check() {
  local name=$1 input=$2 want_code=$3 want_out=$4 want_err=$5
  shift 5
  printf '%s' "$input" > "$TMP/in"
  "$JP" "$@" < "$TMP/in" > "$TMP/out" 2> "$TMP/err"
  local code=$?
  printf '%s' "$want_out" > "$TMP/want_out"
  printf '%s' "$want_err" > "$TMP/want_err"
  if [ "$code" = "$want_code" ] && cmp -s "$TMP/out" "$TMP/want_out" && cmp -s "$TMP/err" "$TMP/want_err"; then
    pass=$((pass + 1))
  else
    fail=$((fail + 1))
    echo "FAIL: $name (exit $code, want $want_code)"
    echo "  stdout: $(od -c < "$TMP/out" | head -3)"
    echo "  want:   $(od -c < "$TMP/want_out" | head -3)"
    echo "  stderr: $(cat "$TMP/err")"
    echo "  want:   $(cat "$TMP/want_err")"
  fi
}

# check_stderr_prefix NAME INPUT WANT_EXIT WANT_STDERR_REGEX ARGS...
# For errors whose reason text is free-form: stdout must be empty, stderr must match the regex.
check_stderr_matches() {
  local name=$1 input=$2 want_code=$3 pattern=$4
  shift 4
  printf '%s' "$input" > "$TMP/in"
  "$JP" "$@" < "$TMP/in" > "$TMP/out" 2> "$TMP/err"
  local code=$?
  if [ "$code" = "$want_code" ] && [ ! -s "$TMP/out" ] && grep -Eq "$pattern" "$TMP/err" \
     && [ "$(wc -l < "$TMP/err")" -eq 1 ]; then
    pass=$((pass + 1))
  else
    fail=$((fail + 1))
    echo "FAIL: $name (exit $code, want $want_code)"
    echo "  stdout: $(cat "$TMP/out")"
    echo "  stderr: $(cat "$TMP/err")"
  fi
}

DOC='{"a":{"b":"hi"},"n":1.50,"big":1e3,"neg":-0.0,"int":100,"t":true,"f":false,"z":null,"arr":[10,20]}'

# --- scalar output ---------------------------------------------------------
check "string value prints raw" "$DOC" 0 $'hi\n' '' a.b
check "leading dot selects root key" "$DOC" 0 $'hi\n' '' .a.b
check "fraction trailing zeros dropped" "$DOC" 0 $'1.5\n' '' n
check "exponent integer prints plain" "$DOC" 0 $'1000\n' '' big
check "negative zero prints 0" "$DOC" 0 $'0\n' '' neg
check "integer unchanged" "$DOC" 0 $'100\n' '' int
check "true" "$DOC" 0 $'true\n' '' t
check "false" "$DOC" 0 $'false\n' '' f
check "null" "$DOC" 0 $'null\n' '' z
check "string with escapes prints unescaped" '{"s":"a\"b\\c\/d\tq"}' 0 $'a"b\\c/d\tq\n' '' s
check "unicode escape prints UTF-8" '{"s":"été"}' 0 $'été\n' '' s
check "raw UTF-8 passes through" '{"s":"héllo ✓"}' 0 $'héllo ✓\n' '' s
check "CRLF whitespace accepted" $'{\r\n  "a": 1\r\n}' 0 $'1\n' '' a
check "stdin via explicit dash" '{"a":2}' 0 $'2\n' '' a -

# --- compact container output ------------------------------------------------
check "whole document compact, key order kept" '{"b":1,"a":[1, 2.0,{"x":null}]}' 0 \
  $'{"b":1,"a":[1,2,{"x":null}]}\n' '' ""
check "dot path is the whole document" '{"b":1}' 0 $'{"b":1}\n' '' .
check "subtree compact" "$DOC" 0 $'{"b":"hi"}\n' '' a
check "compact escapes control characters" '{"s":"a\nb\u0001"}' 0 $'{"s":"a\\nb\\u0001"}\n' '' ""
check "duplicate key keeps first position, last value" '{"a":1,"b":2,"a":3}' 0 $'{"a":3,"b":2}\n' '' ""
check "empty containers" '{"o":{},"l":[]}' 0 $'{"o":{},"l":[]}\n' '' ""

# --- path syntax -------------------------------------------------------------
check "array index" "$DOC" 0 $'20\n' '' arr[1]
check "chained indexes and keys" '{"a":{"b":[[1,{"c":"deep"}]]}}' 0 $'deep\n' '' a.b[0][1].c
check "root array index" '[7,8]' 0 $'8\n' '' '[1]'
check "empty path is root" '{"a":1}' 0 $'{"a":1}\n' '' ""

# --- not found: exit 1 -------------------------------------------------------
check "missing key" "$DOC" 1 '' $'error: path not found: a.zzz\n' a.zzz
check "index out of range" "$DOC" 1 '' $'error: path not found: arr[2]\n' arr[2]
check "huge index out of range" "$DOC" 1 '' $'error: path not found: arr[99999999999999999999999]\n' arr[99999999999999999999999]
check "indexing into object" "$DOC" 1 '' $'error: path not found: a[0]\n' a[0]
check "keying into array" "$DOC" 1 '' $'error: path not found: arr.x\n' arr.x
check "keying into scalar" "$DOC" 1 '' $'error: path not found: n.x\n' n.x

# --- invalid path: exit 2 ----------------------------------------------------
check_stderr_matches "empty key segment" "$DOC" 2 '^error: invalid path: a\.\.b$' 'a..b'
check_stderr_matches "trailing dot" "$DOC" 2 '^error: invalid path: a\.$' 'a.'
check_stderr_matches "non-digit index" "$DOC" 2 '^error: invalid path: arr\[x\]$' 'arr[x]'
check_stderr_matches "negative index" "$DOC" 2 '^error: invalid path: arr\[-1\]$' 'arr[-1]'
check_stderr_matches "unclosed bracket" "$DOC" 2 '^error: invalid path: arr\[0$' 'arr[0'
check_stderr_matches "key after index without dot" "$DOC" 2 '^error: invalid path: arr\[0\]x$' 'arr[0]x'
check_stderr_matches "bracket after dot" "$DOC" 2 '^error: invalid path: arr\.\[0\]$' 'arr.[0]'

# --- malformed JSON: exit 2 --------------------------------------------------
check_stderr_matches "unterminated object" '{"a":1' 2 '^error: invalid JSON: .* at line 1, column 7$' a
check_stderr_matches "trailing comma" '{"a":1,}' 2 '^error: invalid JSON: expected a string key at line 1, column 8$' a
check_stderr_matches "trailing content" '1 2' 2 '^error: invalid JSON: .* at line 1, column 3$' ""
check_stderr_matches "leading zero" '01' 2 '^error: invalid JSON: .* at line 1, column 2$' ""
check_stderr_matches "bare word" 'nul' 2 '^error: invalid JSON: .* at line 1, column 1$' ""
check_stderr_matches "error on line 2" $'{\n  "a": x\n}' 2 '^error: invalid JSON: .* at line 2, column 8$' a
check_stderr_matches "raw newline in string" $'{"a":"x\ny"}' 2 '^error: invalid JSON: .* at line 1, column 8$' a
check_stderr_matches "bad escape" '{"a":"\q"}' 2 '^error: invalid JSON: invalid escape .* at line 1, column 7$' a
check_stderr_matches "empty input" '' 2 '^error: invalid JSON: unexpected end of input at line 1, column 1$' ""
check_stderr_matches "invalid UTF-8 bytes" $'{"a":"\xff"}' 2 '^error: invalid JSON: input is not valid UTF-8 at byte offset 6$' a
check_stderr_matches "deep nesting rejected" "$(printf '%.0s[' $(seq 1 10001))" 2 '^error: invalid JSON: nesting deeper' ""

# --- --shape -----------------------------------------------------------------
SHAPE_DOC='{"a":[1,2],"b":{"c":"x"},"d":[],"e":{},"f":null,"g":true,"h":[[1]]}'
check "shape default depth 2" "$SHAPE_DOC" 0 \
  $'object{a: array[2] of number, b: object{c: string}, d: array[0], e: object{}, f: null, g: boolean, h: array[1] of array[1]}\n' \
  '' "" --shape
check "shape depth 0 on object" "$SHAPE_DOC" 0 $'object{7 keys}\n' '' "" --shape --depth 0
check "shape depth 1" '{"a":[1,2],"b":{"c":"x"}}' 0 $'object{a: array[2], b: object{1 keys}}\n' '' "" --shape --depth 1
check "shape depth 0 on array" '[1]' 0 $'array[1]\n' '' "" --shape --depth 0
check "shape depth=N form" '{"a":{"b":{"c":1}}}' 0 $'object{a: object{b: object{1 keys}}}\n' '' "" --shape --depth=2
check "shape of subtree" "$SHAPE_DOC" 0 $'object{c: string}\n' '' b --shape
check "shape of scalar" "$SHAPE_DOC" 0 $'number\n' '' a[0] --shape
check "shape of empty object at depth 0" '{}' 0 $'object{0 keys}\n' '' "" --shape --depth 0
check "shape of empty array" '[]' 0 $'array[0]\n' '' "" --shape --depth 5
check "shape keeps document key order" '{"z":1,"a":"s"}' 0 $'object{z: number, a: string}\n' '' "" --shape
check "shape missing path" "$SHAPE_DOC" 1 '' $'error: path not found: nope\n' nope --shape

# --- usage errors: exit 2 ----------------------------------------------------
check "unknown flag" '{}' 2 '' $'unknown flag: --bogus\n' "" --bogus
check "unknown short flag" '{}' 2 '' $'unknown flag: -x\n' "" -x
check "unknown flag with value" '{}' 2 '' $'unknown flag: --shape=1\n' "" --shape=1
check_stderr_matches "missing PATH" '{}' 2 '^error: missing PATH argument$'
check_stderr_matches "too many positionals" '{}' 2 '^error: unexpected argument: c$' a b c
check_stderr_matches "depth without value" '{}' 2 '^error: --depth requires a value$' "" --depth
check_stderr_matches "negative depth" '{}' 2 '^error: invalid --depth value: -1$' "" --depth -1
check_stderr_matches "non-numeric depth" '{}' 2 '^error: invalid --depth value: x$' "" --depth=x
check_stderr_matches "missing file" '' 2 '^error: cannot read file: /nonexistent/x\.json$' a /nonexistent/x.json

# --- file argument -----------------------------------------------------------
printf '%s' '{"a":"from file"}' > "$TMP/doc.json"
check "reads FILE argument" '' 0 $'from file\n' '' a "$TMP/doc.json"

# --- help --------------------------------------------------------------------
help_out="$("$JP" --help 2>"$TMP/help_err"; echo "exit=$?")"
help_err="$(cat "$TMP/help_err")"
for flag in PATH FILE --shape --depth; do
  if [[ "$help_out" == *"$flag"* ]]; then
    pass=$((pass + 1))
  else
    fail=$((fail + 1))
    echo "FAIL: --help does not mention $flag"
  fi
done
if [[ "$help_out" == *"exit=0" && -z "$help_err" ]]; then
  pass=$((pass + 1))
else
  fail=$((fail + 1))
  echo "FAIL: --help must exit 0 with empty stderr"
fi

echo "passed: $pass, failed: $fail"
[ "$fail" -eq 0 ]
