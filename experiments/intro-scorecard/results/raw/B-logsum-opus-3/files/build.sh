#!/bin/sh
# Builds build/logsum.jar from src/.
set -e
cd "$(dirname "$0")"
rm -rf build/classes
mkdir -p build/classes
javac --release 17 -d build/classes $(find src -name '*.java')
jar --create --file build/logsum.jar --main-class logsum.Main -C build/classes .
