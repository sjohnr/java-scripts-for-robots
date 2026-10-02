#!/bin/sh
# Compiles src/Jarsrc.java into build/jarsrc.jar (requires a JDK 17+).
set -eu
cd "$(dirname "$0")"
rm -rf build
mkdir -p build/classes
javac --release 17 -Xlint:all -d build/classes src/Jarsrc.java
printf 'Main-Class: Jarsrc\n' > build/manifest.txt
jar --create --file build/jarsrc.jar --manifest build/manifest.txt -C build/classes .
