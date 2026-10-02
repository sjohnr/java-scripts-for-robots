#!/bin/sh
# Builds build/jarsrc.jar from src/main/java.
set -e
cd "$(dirname "$0")"
rm -rf build/classes
mkdir -p build/classes
javac -nowarn -encoding UTF-8 --release 17 -d build/classes $(find src/main/java -name '*.java')
printf 'Main-Class: jarsrc.Main\n' > build/manifest.txt
jar cfm build/jarsrc.jar build/manifest.txt -C build/classes .
