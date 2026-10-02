#!/bin/sh
# Compiles logsum into build/logsum.jar.
set -e
cd "$(dirname "$0")"
rm -rf build
mkdir -p build/classes
javac -encoding UTF-8 -d build/classes src/LogSum.java
jar --create --file build/logsum.jar --main-class LogSum -C build/classes .
rm -rf build/classes
