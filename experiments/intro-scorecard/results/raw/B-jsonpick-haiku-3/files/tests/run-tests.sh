#!/bin/sh
# Behavioural tests for jsonpick. Usage: tests/run-tests.sh [path-to-jsonpick]
#
# Expectations are literal. A stdout or stderr value is compared exactly, with a trailing
# newline added, unless it is NONE (empty). A value starting with "~" only has to contain
# the rest of the value.
set -u

ROOT=$(cd "$(dirname "$0")/.." && pwd)
JP=${1:-$ROOT/build/jsonpick}
WORK=$(mktemp -d)
trap 'rm -rf "$WORK"' EXIT
pass=0
fail=0

# field_ok WANT FILE: true if FILE matches the expectation WANT.
field_ok() {
    case $1 in
        '~'*)
            grep -qF -- "${1#\~}" "$2"
            ;;
        *)
            if [ "$1" = NONE ]; then
                : > "$WORK/want_field"
            else
                printf '%s\n' "$1" > "$WORK/want_field"
            fi
            cmp -s "$2" "$WORK/want_field"
            ;;
    esac
}

# check NAME STDIN EXIT STDOUT STDERR ARGS...
check() {
    name=$1; input=$2; want_exit=$3; want_out=$4; want_err=$5
    shift 5
    printf '%s' "$input" > "$WORK/stdin"
    "$JP" "$@" < "$WORK/stdin" > "$WORK/out" 2> "$WORK/err"
    got_exit=$?

    ok=1
    [ "$got_exit" = "$want_exit" ] || ok=0
    field_ok "$want_out" "$WORK/out" || ok=0
    field_ok "$want_err" "$WORK/err" || ok=0

    if [ "$ok" = 1 ]; then
        pass=$((pass + 1))
    else
        fail=$((fail + 1))
        echo "FAIL: $name (exit $got_exit, want $want_exit)"
        echo "  stdout: $(od -An -c < "$WORK/out" | tr -s ' ')"
        echo "  stderr: $(cat "$WORK/err")"
    fi
}

DOC='{"a":{"b":"hi"},"n":[1.50,1e3,-0,100,0.1e-2,12345678901234567890,-1.5E+2,0.0],"t":true,"f":false,"z":null}'

# --- default mode: value printing ---
check "string at path" "$DOC" 0 'hi' NONE a.b
check "number canonical forms" "$DOC" 0 '1.5' NONE n[0]
check "number 1e3 is integer" "$DOC" 0 '1000' NONE n[1]
check "negative zero" "$DOC" 0 '0' NONE n[2]
check "integer unchanged" "$DOC" 0 '100' NONE n[3]
check "small exponent" "$DOC" 0 '0.001' NONE n[4]
check "big integer exact" "$DOC" 0 '12345678901234567890' NONE n[5]
check "negative exponent form" "$DOC" 0 '-150' NONE n[6]
check "zero with fraction" "$DOC" 0 '0' NONE n[7]
check "true" "$DOC" 0 'true' NONE t
check "false" "$DOC" 0 'false' NONE f
check "null" "$DOC" 0 'null' NONE z
check "object compact" "$DOC" 0 '{"b":"hi"}' NONE a
check "whole document, empty path" "$DOC" 0 '{"a":{"b":"hi"},"n":[1.5,1000,0,100,0.001,12345678901234567890,-150,0],"t":true,"f":false,"z":null}' NONE ""
check "dot selects root" '[1,2]' 0 '[1,2]' NONE .
check "leading dot" '{"a":1}' 0 '1' NONE .a
check "chained index" '[[1,{"c":true}]]' 0 'true' NONE "[0][1].c"
check "index then dot" '{"x":[{"y":"ok"}]}' 0 'ok' NONE x[0].y
check "top-level scalar" '42' 0 '42' NONE ""
check "raw string characters" '{"s":"a\"b\\cé\nZ"}' 0 'a"b\cé
Z' NONE s
check "surrogate pair" '{"e":"😀"}' 0 "$(printf '\360\237\230\200')" NONE e
check "utf-8 input passes through" '{"k":"日本"}' 0 '日本' NONE k
check "duplicate key: last wins" '{"a":1,"a":2}' 0 '2' NONE a
check "duplicate keys kept in compact output" '{"a":1,"a":2}' 0 '{"a":1,"a":2}' NONE ""
check "compact keeps order and escapes" '{"b":1,"a":[1,{"x":null}],"c":"q\"\u0001/"}' 0 '{"b":1,"a":[1,{"x":null}],"c":"q\"\u0001/"}' NONE ""
check "compact string escapes" '{"s":"\u0001\t"}' 0 '{"s":"\u0001\t"}' NONE ""
check "whitespace in document" ' {
  "a" : [ 1 ,
  2 ] } ' 0 '[1,2]' NONE a

# --- path not found: exit 1 ---
check "missing key" '{"a":1}' 1 NONE '~error: path not found: x.y' x.y
check "index out of range" '[1]' 1 NONE '~error: path not found: [1]' "[1]"
check "huge index not found" '[1]' 1 NONE '~error: path not found: [99999999999999999999999]' "[99999999999999999999999]"
check "index into object" '{"a":1}' 1 NONE '~error: path not found: [0]' "[0]"
check "key into array" '[1]' 1 NONE '~error: path not found: a' a
check "key into scalar" '{"a":1}' 1 NONE '~error: path not found: a.b' a.b

# --- usage and input errors: exit 2 ---
check "invalid JSON position" '{' 2 NONE '~invalid JSON: unexpected end of input at line 1, column 2' ""
check "invalid JSON second line" '{
  "a" 1}' 2 NONE "~invalid JSON: expected ':' but found '1' at line 2, column 7" ""
check "trailing content" '1 2' 2 NONE '~invalid JSON: unexpected trailing content' ""
check "leading zero" '01' 2 NONE '~invalid JSON: unexpected trailing content' ""
check "empty input" '' 2 NONE '~invalid JSON: unexpected end of input' ""
check "bad escape" '"\x"' 2 NONE '~invalid JSON: invalid escape' ""
check "trailing comma" '[1,]' 2 NONE "~invalid JSON: unexpected character ']'" ""
check "invalid utf-8" "$(printf '\377')" 2 NONE '~invalid UTF-8 at byte offset 0' ""
check "unknown flag" '{}' 2 NONE 'unknown flag: --bogus' a --bogus
check "unknown flag with value" '{}' 2 NONE 'unknown flag: --bogus' a --bogus=1
check "missing PATH" '{}' 2 NONE '~missing required argument'
check "too many arguments" '{}' 2 NONE '~unexpected argument: x' a - x
check "invalid path" '{}' 2 NONE 'error: invalid path: a..b' a..b
check "trailing dot path" '{}' 2 NONE 'error: invalid path: a.' a.
check "bad index syntax" '{}' 2 NONE 'error: invalid path: a[x]' 'a[x]'
check "unterminated index" '{}' 2 NONE 'error: invalid path: a[0' 'a[0'
check "missing depth value" '{}' 2 NONE 'error: --depth requires a value' a --depth
check "non-numeric depth" '{}' 2 NONE 'error: invalid --depth value: x' a --shape --depth x
check "negative depth" '{}' 2 NONE 'error: invalid --depth value: -1' a --shape --depth -1
check "unreadable file" '' 2 NONE '~cannot read input' a /nonexistent/file.json

# --- stdin and FILE argument ---
check "explicit dash reads stdin" '{"a":3}' 0 '3' NONE a -
printf '%s' '{"a":4}' > "$WORK/file.json"
check "FILE argument" '' 0 '4' NONE a "$WORK/file.json"

# --- --help ---
check "help lists --shape" '' 0 '~--shape' NONE --help
check "help lists --depth" '' 0 '~--depth' NONE --help
check "help lists PATH and FILE" '' 0 '~FILE' NONE --help

# --- --shape ---
SHAPE_DOC='{"a":"x","b":[1,2,3],"c":{"d":{"e":1}},"f":[],"g":{},"h":[[true]],"i":null}'
check "shape default depth 2" "$SHAPE_DOC" 0 'object{a: string, b: array[3] of number, c: object{d: object{1 keys}}, f: array[0], g: object{}, h: array[1] of array[1], i: null}' NONE --shape ""
check "shape depth 0" "$SHAPE_DOC" 0 'object{7 keys}' NONE --shape --depth 0 ""
check "shape depth 1" "$SHAPE_DOC" 0 'object{a: string, b: array[3], c: object{1 keys}, f: array[0], g: object{0 keys}, h: array[1], i: null}' NONE --shape --depth 1 ""
check "shape depth 0 on array" '[1,2]' 0 'array[2]' NONE --shape --depth 0 ""
check "shape of subtree" "$SHAPE_DOC" 0 'object{d: object{e: number}}' NONE --shape c
check "shape of scalar" "$SHAPE_DOC" 0 'string' NONE --shape a
check "shape of empty array" "$SHAPE_DOC" 0 'array[0]' NONE --shape f
check "shape depth 0 empty object" "$SHAPE_DOC" 0 'object{0 keys}' NONE --shape --depth 0 g
check "shape depth= form" "$SHAPE_DOC" 0 'object{d: object{1 keys}}' NONE --shape --depth=1 c
check "shape path not found" "$SHAPE_DOC" 1 NONE '~path not found' --shape nope
check "shape invalid JSON" '[' 2 NONE '~invalid JSON' --shape ""

echo "passed: $pass, failed: $fail"
[ "$fail" -eq 0 ]
