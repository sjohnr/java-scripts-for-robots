#!/bin/sh
# Builds javaw, generates a wrapper for the example, and runs it the tricky ways.
set -eu
here=$(CDPATH= cd -- "$(dirname -- "$0")" && pwd -P)
tmp=$(mktemp -d)
trap 'rm -rf "$tmp"' EXIT
(cd "$here" && go build -o "$tmp/javaw" .)
mkdir -p "$tmp/app" "$tmp/bin" "$tmp/elsewhere" "$tmp/decoy/app"
cp "$here/examples/HelloWorld.java" "$tmp/app/"
cd "$tmp/app" && ../javaw --min-java 11 HelloWorld.java && head -2 run-hello-world
ln -s ../app/run-hello-world "$tmp/bin/hello"
ln -s hello "$tmp/bin/hello2"
check() { label=$1; want=$2; shift 2
  got=$("$@") && [ "$got" = "$want" ] && echo "ok   $label: $got" || { echo "FAIL $label: $got"; exit 1; }; }
cd "$tmp/elsewhere"
check "different directory" "Hello, a! version=1" ../app/run-hello-world a
check "relative symlink" "Hello, b! version=1" ../bin/hello b
check "symlink chain" "Hello, c! version=1" ../bin/hello2 c
check "JAVA_TOOL_OPTIONS" "Hello, d! version=2" env JAVA_TOOL_OPTIONS=-Dapp.version=2 ../bin/hello2 d
cd "$tmp"
check "CDPATH" "Hello, e! version=1" env CDPATH="$tmp/decoy" app/run-hello-world e
