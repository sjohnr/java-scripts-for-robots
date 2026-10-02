///usr/bin/env jbang "$0" "$@" ; exit $?
//JAVA 25+
//RUNTIME_OPTIONS -ea

import java.util.Arrays;

void main(String... args) {
    IO.println(Arrays.toString(args));
}
