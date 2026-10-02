#!/bin/sh
# Compiles jsonpick into build/jsonpick.jar. Requires a JDK 17+ on the PATH.
set -eu

cd "$(dirname "$0")"
rm -rf build
mkdir -p build/classes
javac --release 17 -Xlint:all -d build/classes src/jsonpick/*.java
printf 'Main-Class: jsonpick.Main\n' > build/manifest.txt
jar --create --file build/jsonpick.jar --manifest build/manifest.txt -C build/classes .
echo "built build/jsonpick.jar"
