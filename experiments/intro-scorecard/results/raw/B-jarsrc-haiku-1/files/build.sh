#!/bin/sh
# Compiles src/ into build/jarsrc.jar (Java 17+, no external dependencies).
set -eu
cd "$(dirname "$0")"
rm -rf build
mkdir -p build/classes
javac --release 17 -Xlint:all -d build/classes $(find src -name '*.java')
jar --create --file build/jarsrc.jar --main-class jarsrc.Jarsrc -C build/classes .
