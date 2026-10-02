#!/bin/sh
# Compiles src/Jsonpick.java into build/jsonpick.jar.
set -e
DIR=$(cd "$(dirname "$0")" && pwd)
rm -rf "$DIR/build"
mkdir -p "$DIR/build/classes"
javac -encoding UTF-8 -d "$DIR/build/classes" "$DIR/src/Jsonpick.java"
jar --create --file "$DIR/build/jsonpick.jar" --main-class Jsonpick -C "$DIR/build/classes" .
