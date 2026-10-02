#!/bin/sh
# Compile logsum into build/logsum.jar. Run from the project root.
set -eu

cd "$(dirname "$0")"
rm -rf build
mkdir -p build/classes
javac -Xlint:all -d build/classes src/Logsum.java
jar --create --file build/logsum.jar --main-class Logsum -C build/classes .
echo "built build/logsum.jar"
