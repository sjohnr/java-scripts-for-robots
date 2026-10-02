#!/bin/sh
# Compiles jarsrc into build/classes. Requires a JDK 17 or newer.
set -e
cd "$(dirname "$0")"
rm -rf build/classes
mkdir -p build/classes
javac --release 17 -Xlint:all -d build/classes src/jarsrc/*.java
