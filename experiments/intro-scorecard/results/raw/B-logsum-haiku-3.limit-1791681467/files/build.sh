#!/usr/bin/env bash
# Compiles logsum into build/classes. Requires a JDK (javac) on PATH.
set -euo pipefail
cd "$(dirname "$0")"
rm -rf build/classes
mkdir -p build/classes
javac -Xlint:all -d build/classes src/logsum/*.java
