#!/bin/sh
# Compiles treesum into build/treesum.jar. Requires a JDK (javac and jar) on PATH.
set -eu

cd "$(dirname "$0")"
rm -rf build
mkdir -p build/classes
javac -d build/classes src/treesum/Treesum.java
jar --create --file build/treesum.jar --main-class treesum.Treesum -C build/classes .
echo "built build/treesum.jar"
