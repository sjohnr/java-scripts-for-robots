#!/bin/sh
# Builds build/jarsrc.jar; run the tool with ./jarsrc
set -e
cd "$(dirname "$0")"
mkdir -p build/classes
javac -encoding UTF-8 -d build/classes src/Jarsrc.java
printf 'Main-Class: Jarsrc\n' > build/manifest.txt
jar cfm build/jarsrc.jar build/manifest.txt -C build/classes .
