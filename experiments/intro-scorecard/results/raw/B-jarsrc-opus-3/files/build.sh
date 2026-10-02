#!/bin/sh
# Compiles jarsrc into build/jarsrc.jar. Run ./jarsrc afterwards.
set -e
cd "$(dirname "$0")"
rm -rf build
mkdir -p build/classes
javac --release 17 -encoding UTF-8 -d build/classes $(find src -name '*.java')
jar --create --file build/jarsrc.jar --main-class jarsrc.Main -C build/classes .
