#!/bin/sh
# Builds build/jarsrc.jar from src/.
set -e
cd "$(dirname "$0")"
rm -rf build/classes
mkdir -p build/classes
javac -encoding UTF-8 --release 17 -d build/classes src/Jarsrc.java
printf 'Main-Class: Jarsrc\n' > build/manifest.txt
jar --create --file build/jarsrc.jar --manifest build/manifest.txt -C build/classes .
