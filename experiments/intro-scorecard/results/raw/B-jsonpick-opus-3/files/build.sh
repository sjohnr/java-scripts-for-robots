#!/bin/sh
# Compile jsonpick into ./build
set -e
DIR=$(cd "$(dirname "$0")" && pwd)
mkdir -p "$DIR/build"
javac -encoding UTF-8 -d "$DIR/build" "$DIR/src/JsonPick.java"
