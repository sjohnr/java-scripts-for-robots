#!/bin/sh
# Compiles jsonpick into build/classes. Requires JDK 17+.
set -e
DIR=$(cd "$(dirname "$0")" && pwd)
mkdir -p "$DIR/build/classes"
javac -encoding UTF-8 -d "$DIR/build/classes" "$DIR/src/JsonPick.java"
