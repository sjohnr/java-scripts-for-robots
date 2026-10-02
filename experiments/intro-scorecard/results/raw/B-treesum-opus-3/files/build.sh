#!/bin/sh
# Compiles treesum into out/treesum.jar.
set -e
here="$(cd "$(dirname "$0")" && pwd)"
rm -rf "$here/out/classes"
mkdir -p "$here/out/classes"
javac --release 17 -encoding UTF-8 -d "$here/out/classes" "$here/src/Treesum.java"
jar --create --file "$here/out/treesum.jar" --main-class Treesum -C "$here/out/classes" .
