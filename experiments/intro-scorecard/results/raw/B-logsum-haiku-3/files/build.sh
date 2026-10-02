#!/bin/sh
# Compiles logsum into build/logsum.jar. Requires a JDK 17+ on PATH.
set -eu
cd "$(dirname "$0")"
rm -rf build
mkdir -p build/classes
javac --release 17 -Xlint:all -d build/classes src/main/java/logsum/*.java
jar --create --file build/logsum.jar --main-class logsum.Main -C build/classes .
