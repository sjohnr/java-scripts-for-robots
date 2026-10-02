#!/bin/sh
# End-to-end tests for jarsrc. Builds a fixture tree in a temp dir and checks stdout/stderr/exit.
set -u
HERE="$(cd "$(dirname "$0")" && pwd)"
BIN="$HERE/../jarsrc"
T="$(mktemp -d)"
trap 'rm -rf "$T"' EXIT
FAIL=0

mkjar() { # mkjar <group/path> <artifact> <version> <file:content>...
  g="$1"; a="$2"; v="$3"; shift 3
  src="$T/src-$a-$v"; mkdir -p "$src"
  for spec in "$@"; do
    f="${spec%%=*}"; c="${spec#*=}"
    mkdir -p "$src/$(dirname "$f")"; printf "$c" > "$src/$f"
  done
  mkdir -p "$T/root/$g/$a/$v"
  (cd "$src" && jar --create --file "$T/root/$g/$a/$v/$a-$v-sources.jar" .)
}

mkjar com/example/lib widgets 1.9.0 'com/example/lib/Widget.java=old\n'
mkjar com/example/lib widgets 1.10.0 'com/example/lib/Widget.java=l1\r\nl2  \nl3\nl4\n' 'com/example/lib/util/Helper.java=h\n'
mkjar com/example/lib widgets 1.0.0-beta 'com/example/lib/Widget.java=beta\n'
mkjar com/example/lib widgets 1.0.0 'com/example/lib/Widget.java=zero\n'
mkjar com/example/lib gadgets 2.0 'com/example/lib/Gadget.java=g\n' 'com/example/lib/Shared.java=s\n'
mkjar org/other thing 1 'com/example/lib/Shared.java=s2\n' 'org/other/Wicket.java=w\n'

check() { # check <name> <expected-exit> <expected-stdout> <expected-stderr> -- args...
  name="$1"; ee="$2"; eo="$3"; er="$4"; shift 5
  "$BIN" --root "$T/root" "$@" >"$T/out" 2>"$T/err"; ec=$?
  if [ -n "$eo" ] && [ "$eo" != "-" ]; then printf "$eo\n" >"$T/want-out"; else : >"$T/want-out"; fi
  if [ -n "$er" ]; then printf '%s\n' "$er" >"$T/want-err"; else : >"$T/want-err"; fi
  if [ "$ec" != "$ee" ] || { [ "$eo" != "-" ] && ! cmp -s "$T/out" "$T/want-out"; } || ! cmp -s "$T/err" "$T/want-err"; then
    echo "FAIL $name: exit=$ec stdout=[$(cat "$T/out")] stderr=[$(cat "$T/err")]"; FAIL=1
  else
    echo "ok   $name"
  fi
}

check find-exact 0 'com.example.lib.Widget com.example.lib:widgets:1.0.0\ncom.example.lib.Widget com.example.lib:widgets:1.0.0-beta\ncom.example.lib.Widget com.example.lib:widgets:1.10.0\ncom.example.lib.Widget com.example.lib:widgets:1.9.0' '' -- find com.example.lib.Widget
check find-star 0 'com.example.lib.Gadget com.example.lib:gadgets:2.0\ncom.example.lib.Shared com.example.lib:gadgets:2.0\ncom.example.lib.Shared org.other:thing:1' '' -- find 'com.example.lib.*a*'
check find-star-nodot 1 '' 'error: no matches for com.*.Helper (did you mean: Helper?)' -- find 'com.*.Helper'
check find-dstar 0 'com.example.lib.util.Helper com.example.lib:widgets:1.10.0' '' -- find 'com.**.Helper'
check find-none-sugg 1 '' 'error: no matches for x.Wi (did you mean: Wicket, Widget?)' -- find x.Wi
check find-none 1 '' 'error: no matches for Zzz' -- find Zzz
check cat-highest 0 'l1\nl2\nl3\nl4' '' -- cat com.example.lib.Widget
check cat-lines 0 'l2\nl3' '' -- cat com.example.lib.Widget --lines 2:3
check cat-clamp 0 'l3\nl4' '' -- cat com.example.lib.Widget --lines 3:99
check cat-past 0 '' '' -- cat com.example.lib.Widget --lines 9:12
check cat-ambig 2 '' 'error: com.example.lib.Shared is ambiguous across: com.example.lib:gadgets, org.other:thing' -- cat com.example.lib.Shared
check cat-none 1 '' 'error: no source for a.b.G (did you mean: Gadget?)' -- cat a.b.G
check cat-badlines 2 '' 'error: invalid --lines value: 3 (expected A:B)' -- cat com.example.lib.Widget --lines 3
check ls-versions 0 '1.0.0\n1.0.0-beta\n1.9.0\n1.10.0' '' -- ls com.example.lib:widgets
check ls-classes 0 'com.example.lib.Widget\ncom.example.lib.util.Helper' '' -- ls com.example.lib:widgets:1.10.0
check ls-nover 1 '' 'error: no artifact com.example.lib:widgets:9 (did you mean: com.example.lib:widgets?)' -- ls com.example.lib:widgets:9
check ls-none 1 '' 'error: no artifact com.example.lib:w (did you mean: com.example.lib:widgets?)' -- ls com.example.lib:w
check ls-none2 1 '' 'error: no artifact nope:x' -- ls nope:x
check unknown-flag 2 '' 'unknown flag: --bogus' -- find X --bogus
check help 0 - '' -- --help
"$BIN" --help | grep -q -- '--lines' && "$BIN" --help | grep -q -- '--root' || { echo "FAIL help flags"; FAIL=1; }
exit $FAIL
