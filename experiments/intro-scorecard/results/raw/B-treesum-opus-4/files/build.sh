#!/bin/sh
# Compile treesum into build-out/treesum.jar
set -e
cd "$(dirname "$0")"
rm -rf build-out/classes
mkdir -p build-out/classes
javac --release 17 -encoding UTF-8 -d build-out/classes src/Treesum.java
jar --create --file build-out/treesum.jar --main-class Treesum -C build-out/classes .
