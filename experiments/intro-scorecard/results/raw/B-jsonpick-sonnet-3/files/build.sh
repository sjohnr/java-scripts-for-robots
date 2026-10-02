#!/bin/sh
# Builds jsonpick.jar and the ./jsonpick launcher.
set -e
d=$(cd "$(dirname "$0")" && pwd)
mkdir -p "$d/out"
javac -d "$d/out" "$d/src/Jsonpick.java"
printf 'Main-Class: Jsonpick\n' > "$d/out/manifest.txt"
jar cfm "$d/jsonpick.jar" "$d/out/manifest.txt" -C "$d/out" .
cat > "$d/jsonpick" <<'L'
#!/bin/sh
exec java -Xshare:auto -XX:TieredStopAtLevel=1 -jar "$(dirname "$0")/jsonpick.jar" "$@"
L
chmod +x "$d/jsonpick"
