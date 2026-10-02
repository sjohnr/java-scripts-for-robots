#!/bin/sh
# Compile treesum into out/treesum.jar.
set -e
cd "$(dirname "$0")"
rm -rf out/classes
mkdir -p out/classes
javac --release 17 -encoding UTF-8 -d out/classes src/Treesum.java
jar --create --file out/treesum.jar --main-class Treesum -C out/classes .
