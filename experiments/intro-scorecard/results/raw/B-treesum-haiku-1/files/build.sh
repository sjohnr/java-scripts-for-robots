#!/bin/sh
# Compile Treesum.java into build/treesum.jar (runnable with: java -jar build/treesum.jar).
set -eu
cd "$(dirname "$0")"
rm -rf build
mkdir -p build/classes
javac --release 17 -Xlint:all -d build/classes Treesum.java
jar --create --file build/treesum.jar --main-class Treesum -C build/classes .
echo "built build/treesum.jar"
