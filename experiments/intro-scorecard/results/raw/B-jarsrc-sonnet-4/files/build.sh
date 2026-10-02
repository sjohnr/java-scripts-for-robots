#!/bin/sh
d=$(cd "$(dirname "$0")" && pwd)
mkdir -p "$d/out" && javac -d "$d/out" "$d/src/Jarsrc.java"
