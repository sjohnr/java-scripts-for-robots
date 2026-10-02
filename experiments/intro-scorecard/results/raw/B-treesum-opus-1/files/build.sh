#!/bin/sh
# Compile treesum into build-out/treesum.jar.
set -e
here=$(cd "$(dirname "$0")" && pwd)
rm -rf "$here/build-out"
mkdir -p "$here/build-out/classes"
javac -encoding UTF-8 --release 17 -d "$here/build-out/classes" "$here"/src/treesum/*.java
jar --create --file "$here/build-out/treesum.jar" --main-class treesum.Main -C "$here/build-out/classes" .
