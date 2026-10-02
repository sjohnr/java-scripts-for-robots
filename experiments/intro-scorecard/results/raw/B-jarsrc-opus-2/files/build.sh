#!/bin/sh
# Compiles jarsrc into build/jarsrc.jar.
set -e
cd "$(dirname "$0")"
rm -rf build/classes
mkdir -p build/classes
javac -nowarn -encoding UTF-8 -d build/classes $(find src -name '*.java')
jar --create --file build/jarsrc.jar --main-class jarsrc.Main -C build/classes .
