#!/bin/sh
# Tests for javaw. Run: ./test.sh   (needs only a JDK; uses /bin/sh, and dash if present)
JAVAW=$(CDPATH= cd -P "$(dirname "$0")" && pwd -P)/javaw
T=$(mktemp -d) && trap 'rm -rf "$T"' EXIT
pass=0 fail=0

assert_eq() { # name expected actual
  if [ "$2" = "$3" ]; then pass=$((pass + 1))
  else fail=$((fail + 1)); printf 'FAIL %s\n  expected: %s\n  actual:   %s\n' "$1" "$2" "$3"; fi
}
assert_contains() { # name needle haystack
  case $3 in *"$2"*) pass=$((pass + 1)) ;;
  *) fail=$((fail + 1)); printf 'FAIL %s\n  expected to contain: %s\n  actual: %s\n' "$1" "$2" "$3" ;; esac
}
assert_not_contains() {
  case $3 in *"$2"*) fail=$((fail + 1)); printf 'FAIL %s: should not contain %s\n' "$1" "$2" ;;
  *) pass=$((pass + 1)) ;; esac
}

# Example program: prints its args and the app.version system property.
mkdir -p "$T/app" "$T/elsewhere"
cat > "$T/app/Hello.java" <<'EOF'
public class Hello {
    public static void main(String[] args) {
        System.out.println("hello " + String.join(" ", args) + " v" + System.getProperty("app.version", "1"));
    }
}
EOF
cd "$T/app" || exit 1

# --- Default names (kebab-case) -------------------------------------------
mkdir names && cd names || exit 1
touch FooBar.java HTTPServer.java example1_assert.java Example1.java Foo__Bar.java my-tool.jar Tool.java
"$JAVAW" FooBar.java HTTPServer.java example1_assert.java Example1.java my-tool.jar >/dev/null
assert_eq "kebab names" "run-example1 run-example1-assert run-foo-bar run-http-server run-my-tool" "$(echo run-*)"
out=$("$JAVAW" --force FooBar.java Foo__Bar.java 2>&1); assert_contains "duplicate targets refused" "several inputs would write" "$out"
rm run-foo-bar && "$JAVAW" Foo__Bar.java >/dev/null; assert_eq "Foo__Bar collapses" "yes" "$([ -x run-foo-bar ] && echo yes)"
"$JAVAW" Tool.java >/dev/null
out=$("$JAVAW" --jar Tool.java 2>&1); assert_contains "tool.jar and Tool.java collide on run-tool" "already exists" "$out"
assert_contains "jar input launches with -jar" 'exec java -jar "$dir/my-tool.jar" "$@"' "$(cat run-my-tool)"
cd .. || exit 1

# --- Source launch, permissions, minimal content -------------------------
"$JAVAW" Hello.java >/dev/null
assert_eq "executable bit" "yes" "$([ -x run-hello ] && echo yes)"
w=$(cat run-hello)
assert_eq "shebang" "#!/bin/sh" "$(head -n 1 run-hello)"
assert_contains "source exec line" 'exec java "$dir/Hello.java" "$@"' "$w"
assert_not_contains "minimal: no -cp" "-cp" "$w"
assert_not_contains "minimal: no version check" "version" "$w"
assert_not_contains "POSIX: no [[" "[[" "$w"
assert_not_contains "POSIX: no readlink -f" "readlink -f" "$w"
assert_not_contains "POSIX: no realpath" "realpath" "$w"
assert_eq "sh -n parses" "0" "$(sh -n run-hello; echo $?)"
assert_eq "plain run" "hello a b v1" "$(./run-hello a b)"
command -v dash >/dev/null && assert_eq "runs under dash" "hello x v1" "$(dash ./run-hello x)"

# --- Overwriting ----------------------------------------------------------
out=$("$JAVAW" Hello.java 2>&1); rc=$?
assert_eq "refuse overwrite exit code" "1" "$rc"
assert_contains "refuse overwrite message" "already exists" "$out"
"$JAVAW" --force Hello.java >/dev/null; assert_eq "--force overwrites" "0" "$?"

# --- Relocation scenarios ---------------------------------------------------
cd "$T/elsewhere" || exit 1
assert_eq "from another directory" "hello x v1" "$("$T/app/run-hello" x)"
assert_eq "relative path from another dir" "hello x v1" "$(../app/run-hello x)"
ln -s ../app/run-hello link1 && ln -s link1 link2
assert_eq "relative symlink" "hello s v1" "$(./link1 s)"
assert_eq "symlink chain of two" "hello c v1" "$(./link2 c)"
assert_eq "JAVA_TOOL_OPTIONS" "hello j v2" "$(JAVA_TOOL_OPTIONS=-Dapp.version=2 ./link2 j 2>/dev/null)"
mkdir -p "$T/decoy/app"     # CDPATH would send "cd app" here
assert_eq "CDPATH exported" "hello cd v1" "$(cd "$T" && CDPATH="$T/decoy" && export CDPATH && app/run-hello cd)"
mkdir -p "$T/-dash" && cp "$T/app/Hello.java" "$T/-dash/" && (cd "$T/-dash" && "$JAVAW" Hello.java >/dev/null)
assert_eq "path starting with -" "hello d v1" "$(cd "$T" && sh -- -dash/run-hello d)"
mkdir "$T/moved" && cp -p "$T/app/run-hello" "$T/moved/renamed" && cp "$T/app/Hello.java" "$T/moved/"
assert_eq "moved and renamed with sibling" "hello m v1" "$("$T/moved/renamed" m)"
assert_eq "argument with spaces" "hello a  b v1" "$("$T/app/run-hello" 'a  b')"
cd "$T/app" || exit 1

# --- Output naming --------------------------------------------------------
mkdir out
"$JAVAW" -o out/ Hello.java >/dev/null; assert_eq "-o dir/" "yes" "$([ -x out/run-hello ] && echo yes)"
"$JAVAW" -o out/custom Hello.java >/dev/null; assert_eq "-o file" "yes" "$([ -x out/custom ] && echo yes)"
assert_eq "wrapper in subdir finds program" "hello o v1" "$(out/custom o)"
cp Hello.java Other.java
out=$("$JAVAW" -o single Hello.java Other.java 2>&1)
assert_contains "-o with several inputs must be dir" "must be a directory" "$out"
"$JAVAW" --output=out Hello.java Other.java --force >/dev/null
assert_eq "several inputs into existing dir" "yes" "$([ -x out/run-hello ] && [ -x out/run-other ] && echo yes)"

# --- Jar, classpath, jvm-opts, java-home ----------------------------------
mkdir -p jar/classes && javac -d jar/classes Hello.java && printf 'Main-Class: Hello\n' > jar/m.txt &&
  jar cfm jar/hello.jar jar/m.txt -C jar/classes . || exit 1
"$JAVAW" --jar jar/hello.jar --jvm-opts "-Dapp.version=3 -Xmx64m" -o run-jar >/dev/null
assert_contains "jar exec line" 'exec java -Dapp.version=3 -Xmx64m -jar "$dir/jar/hello.jar" "$@"' "$(cat run-jar)"
assert_eq "jar runs from elsewhere" "hello j v3" "$(cd / && "$T/app/run-jar" j)"
"$JAVAW" --classpath "lib/a.jar:lib/b c.jar" -o run-cp Hello.java >/dev/null
assert_contains "classpath recorded as-is" "-cp 'lib/a.jar:lib/b c.jar' \"\$dir/Hello.java\"" "$(cat run-cp)"
assert_eq "classpath wrapper runs" "hello cp v1" "$(./run-cp cp)"
"$JAVAW" --java-home "$(dirname "$(dirname "$(command -v javac)")")" -o run-home Hello.java >/dev/null
assert_contains "java-home exec" "/bin/java \"\$dir/Hello.java\"" "$(cat run-home)"
assert_eq "java-home runs" "hello h v1" "$(./run-home h)"

# --- Minimum Java version -------------------------------------------------
"$JAVAW" --min-java 8 -o run-min8 Hello.java >/dev/null
assert_eq "min-java satisfied (with JAVA_TOOL_OPTIONS)" "hello m v2" "$(JAVA_TOOL_OPTIONS=-Dapp.version=2 ./run-min8 m 2>/dev/null)"
"$JAVAW" --min-java 999 -o run-min999 Hello.java >/dev/null
out=$(./run-min999 2>&1); rc=$?
assert_eq "min-java too old exit" "1" "$rc"
assert_contains "min-java too old message" "run-min999: Java 999 or newer is required, found" "$out"
fake() { # fake JDK whose java prints a JAVA_TOOL_OPTIONS notice and the given version line
  mkdir -p "$T/$1/bin"
  printf '#!/bin/sh\necho "Picked up JAVA_TOOL_OPTIONS: -Dx=version \\"99\\"" >&2\necho %s >&2\necho ran\n' "'$2'" > "$T/$1/bin/java"
  chmod +x "$T/$1/bin/java"
}
fake jdk8 'java version "1.8.0_392"'
"$JAVAW" --java-home "$T/jdk8" --min-java 9 -o run-old Hello.java >/dev/null
out=$(./run-old 2>&1)
assert_contains "1.8.0_x treated as 8" "Java 9 or newer is required, found 1.8.0_392 ($T/jdk8/bin/java)" "$out"
"$JAVAW" --java-home "$T/jdk8" --min-java 8 -o run-ok8 Hello.java >/dev/null
assert_eq "1.8.0_x satisfies 8" "ran" "$(./run-ok8 2>/dev/null)"
fake jdk28 'openjdk version "28-ea" 2027-03-23'
"$JAVAW" --java-home "$T/jdk28/" --min-java 28 -o run-ea Hello.java >/dev/null
assert_eq "28-ea treated as 28" "ran" "$(./run-ea 2>/dev/null)"
fake jdkbad 'garbage'
"$JAVAW" --java-home "$T/jdkbad" --min-java 8 -o run-bad Hello.java >/dev/null
assert_contains "unparseable version" "Java 8 or newer is required, found no usable Java" "$(./run-bad 2>&1)"
"$JAVAW" --java-home "$T/nope" --min-java 8 -o run-missing Hello.java >/dev/null
out=$(./run-missing 2>&1)
assert_eq "missing java is a clear one-line error" "run-missing: Java 8 or newer is required, found no usable Java ($T/nope/bin/java)" "$out"

# --- Header is a verbatim, re-runnable command ---------------------------
mkdir regen && cp Hello.java regen/ && cd regen || exit 1
"$JAVAW" --jvm-opts "-Dapp.version='it''s' -Xss1m" --min-java 11 -o "my tool" Hello.java >/dev/null
cmd=$(sed -n '3s/^#   //p' "my tool")
assert_eq "header quoted" "javaw --jvm-opts '-Dapp.version='\\''it'\\'''\\''s'\\'' -Xss1m' --min-java 11 -o 'my tool' Hello.java" "$cmd"
mv "my tool" first
(javaw() { "$JAVAW" "$@"; }; eval "$cmd") >/dev/null
assert_eq "regenerated wrapper identical" "" "$(diff first "my tool")"
assert_eq "quoted jvm opt reaches program" "hello q vits" "$(./first q)"
cd .. || exit 1

# --- CLI errors -----------------------------------------------------------
out=$("$JAVAW" 2>&1); rc=$?
assert_eq "missing input exit" "2" "$rc"; assert_contains "missing input msg" "missing input" "$out"
out=$("$JAVAW" --nope Hello.java 2>&1); assert_contains "unknown option" "unknown option: --nope" "$out"
out=$("$JAVAW" Missing.java 2>&1); assert_contains "missing file" "no such file: Missing.java" "$out"
out=$("$JAVAW" --min-java x Hello.java 2>&1); assert_contains "bad min-java" "expects a number" "$out"
out=$("$JAVAW" Hello.java -o 2>&1); assert_contains "option without value" "-o requires a value" "$out"
assert_contains "--help" "Usage: javaw" "$("$JAVAW" --help)"

echo "$pass passed, $fail failed"
[ "$fail" -eq 0 ]
