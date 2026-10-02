#!/bin/sh
# Compile jsonpick into ./build
set -e
cd "$(dirname "$0")"
rm -rf build
mkdir -p build
javac -encoding UTF-8 -d build src/JsonPick.java
