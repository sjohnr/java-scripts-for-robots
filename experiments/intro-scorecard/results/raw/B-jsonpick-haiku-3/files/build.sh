#!/bin/sh
# Compiles jsonpick into build/classes and writes a launcher at build/jsonpick.
set -eu

ROOT="$(cd "$(dirname "$0")" && pwd)"
CLASSES="$ROOT/build/classes"

rm -rf "$CLASSES"
mkdir -p "$CLASSES"
javac --release 17 -Xlint:all -d "$CLASSES" "$ROOT"/src/jsonpick/*.java

cat > "$ROOT/build/jsonpick" <<EOF
#!/bin/sh
exec java -cp "$CLASSES" jsonpick.Main "\$@"
EOF
chmod +x "$ROOT/build/jsonpick"
echo "built $ROOT/build/jsonpick"
