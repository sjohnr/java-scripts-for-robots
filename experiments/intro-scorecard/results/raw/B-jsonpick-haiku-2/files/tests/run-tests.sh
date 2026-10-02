#!/usr/bin/env bash
# Black-box tests for jsonpick. Run ./build.sh first.
set -u

here=$(cd "$(dirname "$0")/.." && pwd)
jar="$here/build/jsonpick.jar"
tmp=$(mktemp -d)
trap 'rm -rf "$tmp"' EXIT
pass=0
fail=0

# check NAME STDIN WANT_EXIT WANT_STDOUT WANT_STDERR [ARGS...]
# Expected stdout/stderr are given without their final newline; one is appended when non-empty.
check() {
  local name=$1 input=$2 want_code=$3 want_out=$4 want_err=$5
  shift 5
  printf '%s' "$input" | java -jar "$jar" "$@" >"$tmp/out" 2>"$tmp/err"
  local code=$?
  printf '%s' "$want_out" >"$tmp/want_out"
  [ -n "$want_out" ] && printf '\n' >>"$tmp/want_out"
  printf '%s' "$want_err" >"$tmp/want_err"
  [ -n "$want_err" ] && printf '\n' >>"$tmp/want_err"
  if [ "$code" = "$want_code" ] && cmp -s "$tmp/out" "$tmp/want_out" && cmp -s "$tmp/err" "$tmp/want_err"; then
    pass=$((pass + 1))
  else
    fail=$((fail + 1))
    echo "FAIL: $name (exit $code, want $want_code)"
    echo "  stdout: $(cat -v "$tmp/out")"
    echo "  want:   $(cat -v "$tmp/want_out")"
    echo "  stderr: $(cat -v "$tmp/err")"
    echo "  want:   $(cat -v "$tmp/want_err")"
  fi
}

# ---- default mode: values -------------------------------------------------

check "string prints raw, no quotes" '{"a":{"b":"hi"}}' 0 'hi' '' a.b
check "root dot selects document" '{"a":{"b":"hi"}}' 0 '{"a":{"b":"hi"}}' '' .
check "empty path selects document" '{"a":1}' 0 '{"a":1}' '' ""
check "leading dot is allowed" '{"a":{"b":"hi"}}' 0 'hi' '' .a.b
check "integer" '{"n":42}' 0 '42' '' n
check "trailing zeros dropped" '{"n":1.50}' 0 '1.5' '' n
check "integer-valued decimal has no point" '{"n":2.000}' 0 '2' '' n
check "exponent expanded" '{"n":1e3}' 0 '1000' '' n
check "negative exponent" '{"n":1.5E-2}' 0 '0.015' '' n
check "negative zero" '{"n":-0.0}' 0 '0' '' n
check "large integer kept exact" '{"n":12345678901234567890123}' 0 '12345678901234567890123' '' n
check "true" '{"b":true}' 0 'true' '' b
check "false" '{"b":false}' 0 'false' '' b
check "null" '{"b":null}' 0 'null' '' b
check "array index" '[10,20,30]' 0 '20' '' [1]
check "chained indexes and keys" '{"a":[[0,{"c":"x"}]]}' 0 'x' '' 'a[0][1].c'
check "array printed as compact JSON" '{"a":[1,"two",{"three":null}]}' 0 '[1,"two",{"three":null}]' '' a
check "object keys keep document order" '{"z":1,"a":2}' 0 '{"z":1,"a":2}' '' .
check "empty object and array" '{"a":{},"b":[]}' 0 '{"a":{},"b":[]}' '' .
check "nested string escaped in compact output" '{"s":"a\"b\\c\nd\u0001é"}' 0 '{"s":"a\"b\\c\nd\u0001é"}' '' .
check "surrogate pair decodes to UTF-8" '{"s":"😀"}' 0 '😀' '' s
check "whitespace and newlines in input" $'{\n  "a" : [ 1 ,\n 2 ]\n}\n' 0 '2' '' a[1]
check "scalar document" '"just a string"' 0 'just a string' '' .
check "dash reads stdin" '{"a":7}' 0 '7' '' a -
printf '{"a":7}' >"$tmp/doc.json"
check "file argument" '' 0 '7' '' a "$tmp/doc.json"
check "options may precede PATH" '{"a":[1,2]}' 0 'array[2] of number' '' --shape a
check "leading dot before index" '[10,20]' 0 '20' '' .[1]
check "-- ends options so PATH may start with dash" '{"-a":5}' 0 '5' '' -- -a
check "UTF-8 key lookup" '{"ключ":"значение"}' 0 'значение' '' ключ

# ---- default mode: path not found (exit 1) ---------------------------------

check "missing key" '{"a":1}' 1 '' 'error: path not found: x' x
check "index out of range" '{"a":[1]}' 1 '' 'error: path not found: a[5]' 'a[5]'
check "index beyond any integer range" '[1]' 1 '' 'error: path not found: [99999999999999999999999]' '[99999999999999999999999]'
check "index into non-array" '{"a":{}}' 1 '' 'error: path not found: a[0]' 'a[0]'
check "key into non-object" '{"a":1}' 1 '' 'error: path not found: a.b' 'a.b'
check "key through null" '{"a":null}' 1 '' 'error: path not found: a.b' 'a.b'
check "index into scalar root" '5' 1 '' 'error: path not found: [0]' '[0]'

# ---- usage and input errors (exit 2) ----------------------------------------

check "unknown flag" '{}' 2 '' 'unknown flag: --bogus' --bogus
check "unknown short flag" '{}' 2 '' 'unknown flag: -x' -x .
check "--shape with a value" '{}' 2 '' 'error: --shape does not take a value' --shape=1 .
check "missing PATH" '{}' 2 '' 'error: PATH is required'
check "too many positionals" '{}' 2 '' 'error: unexpected argument: c' a b c
check "--depth without value" '{}' 2 '' 'error: --depth requires a value' . --depth
check "--depth negative" '{}' 2 '' 'error: --depth must be a non-negative integer' . --depth -1
check "--depth not a number" '{}' 2 '' 'error: --depth must be a non-negative integer' . --depth x
check "invalid path: empty segment" '{"a":{"b":1}}' 2 '' 'error: invalid path: a..b' 'a..b'
check "invalid path: trailing dot" '{"a":1}' 2 '' 'error: invalid path: a.' 'a.'
check "invalid path: unclosed index" '{"a":[1]}' 2 '' 'error: invalid path: a[0' 'a[0'
check "invalid path: non-numeric index" '{"a":[1]}' 2 '' 'error: invalid path: a[x]' 'a[x]'
check "invalid path: negative index" '{"a":[1]}' 2 '' 'error: invalid path: a[-1]' 'a[-1]'
check "invalid path: text after index" '{"a":[1]}' 2 '' 'error: invalid path: a[0]b' 'a[0]b'
check "invalid JSON: bad value" '{"a":}' 2 '' 'error: invalid JSON: line 1, column 6: unexpected character '\''}'\''' a
check "invalid JSON: missing comma on line 3" $'{\n  "a": 1\n  "b": 2}' 2 '' 'error: invalid JSON: line 3, column 3: expected '\'','\'' or '\''}'\'' but found '\''"'\''' a
check "invalid JSON: trailing content" '{} x' 2 '' 'error: invalid JSON: line 1, column 4: unexpected content after value' .
check "invalid JSON: leading zero" '01' 2 '' 'error: invalid JSON: line 1, column 2: unexpected content after value' .
check "invalid JSON: empty input" '' 2 '' 'error: invalid JSON: line 1, column 1: unexpected end of input' .
check "invalid JSON: unterminated string" '"abc' 2 '' 'error: invalid JSON: line 1, column 5: unterminated string' .
check "invalid JSON: raw control character" $'"a\tb"' 2 '' 'error: invalid JSON: line 1, column 3: unescaped control character in string' .
check "invalid JSON: lone surrogate" '"\ud800"' 2 '' 'error: invalid JSON: line 1, column 8: unpaired surrogate in string' .
check "invalid UTF-8 input" $'{"a":"\xff"}' 2 '' 'error: invalid JSON: invalid UTF-8 at byte offset 6' a
check "file does not exist" '' 2 '' 'error: cannot read file: /nonexistent/jsonpick-test.json' a /nonexistent/jsonpick-test.json

# ---- --shape --------------------------------------------------------------

doc='{"a":{"b":1,"c":[1,2,3]},"d":"x"}'
check "shape default depth 2" "$doc" 0 'object{a: object{b: number, c: array[3]}, d: string}' '' . --shape
check "shape depth 0 counts keys" "$doc" 0 'object{2 keys}' '' . --shape --depth 0
check "shape depth 1" "$doc" 0 'object{a: object{2 keys}, d: string}' '' . --shape --depth 1
check "shape of subtree" "$doc" 0 'array[3] of number' '' a.c --shape
check "shape of subtree at depth 0" "$doc" 0 'array[3]' '' a.c --shape --depth 0
check "shape depth=N form" "$doc" 0 'object{a: object{b: number, c: array[3] of number}, d: string}' '' . --depth=3 --shape
check "shape of empty array" '[]' 0 'array[0]' '' . --shape --depth 5
check "shape of empty object" '{}' 0 'object{}' '' . --shape
check "shape of nested arrays" '[[1]]' 0 'array[1] of array[1] of number' '' . --shape --depth 3
check "shape stops at budget" '[[[1]]]' 0 'array[1] of array[1] of array[1]' '' . --shape --depth 2
check "shape of single-key object" '{"k":1}' 0 'object{k: number}' '' . --shape
check "shape of string" '"x"' 0 'string' '' . --shape
check "shape of null" 'null' 0 'null' '' . --shape
check "shape of boolean" 'true' 0 'boolean' '' . --shape
check "shape of number" '1.5' 0 'number' '' . --shape
check "shape only reads path, path missing" "$doc" 1 '' 'error: path not found: q' q --shape
check "shape with invalid JSON" '{' 2 '' 'error: invalid JSON: line 1, column 2: unexpected end of input, expected string key' . --shape

# ---- help -----------------------------------------------------------------

help_out=$(java -jar "$jar" --help </dev/null 2>"$tmp/help_err")
help_code=$?
if [ "$help_code" = 0 ] && [ ! -s "$tmp/help_err" ] \
    && for word in PATH FILE --shape --depth; do case $help_out in *"$word"*) ;; *) exit 1 ;; esac; done; then
  pass=$((pass + 1))
else
  fail=$((fail + 1))
  echo "FAIL: --help lists flags with exit 0 and empty stderr (exit $help_code)"
fi

echo "passed: $pass, failed: $fail"
[ "$fail" = 0 ]
