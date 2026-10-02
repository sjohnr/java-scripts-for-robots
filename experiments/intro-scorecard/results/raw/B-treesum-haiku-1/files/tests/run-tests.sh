#!/bin/sh
# Black-box tests for treesum against SPEC.md. Run ./build.sh first.
set -u
here="$(cd "$(dirname "$0")" && pwd)"
jar="$here/../build/treesum.jar"
work="$(mktemp -d)"
trap 'rm -rf "$work"' EXIT
ts() { java -jar "$jar" "$@"; }
fail=0
pass=0

check() { # name expected_exit expected_stdout_file expected_stderr_file actual_exit stdout_file stderr_file
    name=$1; want_code=$2; want_out=$3; want_err=$4; got_code=$5; got_out=$6; got_err=$7
    ok=1
    [ "$got_code" = "$want_code" ] || { echo "FAIL $name: exit $got_code, want $want_code"; ok=0; }
    cmp -s "$want_out" "$got_out" || { echo "FAIL $name: stdout differs"; diff "$want_out" "$got_out" | head -20; ok=0; }
    cmp -s "$want_err" "$got_err" || { echo "FAIL $name: stderr differs"; diff "$want_err" "$got_err" | head -5; ok=0; }
    if [ $ok = 1 ]; then pass=$((pass + 1)); else fail=$((fail + 1)); fi
}

# --- fixture -------------------------------------------------------------
fx="$work/fx"
mkdir -p "$fx/src/util" "$fx/docs/build" "$fx/node_modules" "$fx/build" "$fx/.git" "$fx/target"
printf 'hello\nworld\n' > "$fx/a.txt"          # 12 bytes, loc 2
printf 'x' > "$fx/b.TXT"                       # 1 byte, loc 1 (no trailing newline)
printf 'one\ntwo' > "$fx/README"               # 7 bytes, loc 2
printf 'node_modules\n' > "$fx/.gitignore"     # 13 bytes, loc 1, ext ""
printf 'class A {\n}\n' > "$fx/src/Main.java"  # 12 bytes, loc 2
: > "$fx/src/util/helper.py"                   # 0 bytes, loc 0
printf 'a\n\n\n' > "$fx/src/util/deep.java"    # 4 bytes, loc 3
printf '# g\n' > "$fx/docs/guide.md"           # 4 bytes, loc 1
printf 'x\n' > "$fx/node_modules/ignored.js"
printf 'x\n' > "$fx/build/out.class"
printf 'x\n' > "$fx/docs/build/notes.md"
printf 'x\n' > "$fx/.git/HEAD"
printf 'x\n' > "$fx/target/x.jar"
ln -s a.txt "$fx/link.txt"                     # symlinks: not followed, not counted
ln -s src "$fx/linkdir"

# --- default run ---------------------------------------------------------
cat > "$work/exp.out" <<'EOF'
files: 8
dirs: 3
by extension:
  (none)  files=2 loc=3
  java  files=2 loc=5
  txt  files=2 loc=3
  md  files=1 loc=1
  py  files=1 loc=0
largest files:
  13 .gitignore
  12 a.txt
  12 src/Main.java
  7 README
  4 docs/guide.md
  4 src/util/deep.java
  1 b.TXT
  0 src/util/helper.py
EOF
: > "$work/empty"
ts "$fx" > "$work/o" 2> "$work/e"; code=$?
check "default" 0 "$work/exp.out" "$work/empty" "$code" "$work/o" "$work/e"

# --- relative DIR, trailing-equals form, folding -------------------------
cat > "$work/exp2.out" <<'EOF'
files: 8
dirs: 3
by extension:
  (none)  files=2 loc=3
  java  files=2 loc=5
  other  files=4 loc=4
largest files:
  13 .gitignore
  12 a.txt
EOF
(cd "$work" && ts fx --top=2 --ext-limit 2) > "$work/o" 2> "$work/e"; code=$?
check "folded" 0 "$work/exp2.out" "$work/empty" "$code" "$work/o" "$work/e"

# --- --ext-limit 0 folds everything -------------------------------------
cat > "$work/exp3.out" <<'EOF'
files: 8
dirs: 3
by extension:
  other  files=8 loc=12
largest files:
EOF
ts "$fx" --ext-limit 0 --top 0 > "$work/o" 2> "$work/e"; code=$?
check "ext-limit-0" 0 "$work/exp3.out" "$work/empty" "$code" "$work/o" "$work/e"

# --- help ----------------------------------------------------------------
ts --help > "$work/o" 2> "$work/e"; code=$?
grep -q -- '--top' "$work/o" && grep -q -- '--ext-limit' "$work/o" && grep -q 'node_modules' "$work/o" \
    && grep -q -- '--help' "$work/o" || { echo "FAIL help: missing flags or ignore list"; fail=$((fail + 1)); }
[ "$code" = 0 ] && [ ! -s "$work/e" ] && pass=$((pass + 1)) || { echo "FAIL help: exit $code or stderr output"; fail=$((fail + 1)); }

# --- usage and path errors (exit 2) -------------------------------------
printf 'unknown flag: --bogus\n' > "$work/exp_bogus.err"
ts --bogus > "$work/o" 2> "$work/e"; code=$?
check "unknown-flag" 2 "$work/empty" "$work/exp_bogus.err" "$code" "$work/o" "$work/e"

printf 'error: cannot read nope\n' > "$work/exp_nope.err"
ts nope > "$work/o" 2> "$work/e"; code=$?
check "missing-dir" 2 "$work/empty" "$work/exp_nope.err" "$code" "$work/o" "$work/e"

printf 'error: cannot read %s\n' "$fx/a.txt" > "$work/exp_file.err"
ts "$fx/a.txt" > "$work/o" 2> "$work/e"; code=$?
check "dir-is-file" 2 "$work/empty" "$work/exp_file.err" "$code" "$work/o" "$work/e"

ts "$fx" --top x > "$work/o" 2> "$work/e"; code=$?
[ "$code" = 2 ] && [ ! -s "$work/o" ] && [ -s "$work/e" ] && pass=$((pass + 1)) \
    || { echo "FAIL bad-top: exit $code"; fail=$((fail + 1)); }

ts "$fx" --ext-limit > "$work/o" 2> "$work/e"; code=$?
[ "$code" = 2 ] && [ ! -s "$work/o" ] && pass=$((pass + 1)) \
    || { echo "FAIL missing-value: exit $code"; fail=$((fail + 1)); }

echo "passed: $pass  failed: $fail"
[ "$fail" = 0 ]
