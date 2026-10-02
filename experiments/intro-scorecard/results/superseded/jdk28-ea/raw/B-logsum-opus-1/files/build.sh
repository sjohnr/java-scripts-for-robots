#!/bin/sh
# Compiles src/Logsum.java into logsum.jar (requires JDK 17+).
set -e
cd "$(dirname "$0")"
rm -rf build
mkdir -p build/classes
javac --release 17 -d build/classes src/Logsum.java
jar --create --file logsum.jar --main-class Logsum -C build/classes .
rm -rf build
