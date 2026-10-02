#!/bin/sh
# Compile logsum into build/logsum.jar.
set -e
cd "$(dirname "$0")"
rm -rf build/classes
mkdir -p build/classes
javac -encoding UTF-8 -d build/classes src/Logsum.java
jar --create --file build/logsum.jar --main-class Logsum -C build/classes .
