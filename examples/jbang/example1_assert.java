///usr/bin/env jbang "$0" "$@" ; exit $?
//JAVA 25+
//DEPS org.assertj:assertj-core:3.27.7
//RUNTIME_OPTIONS -ea

import static org.assertj.core.api.Assertions.*;

void main(String... args) {
    assertThat(args).as("No message provided").hasSize(1);

    var expected = "Hello";
    var actual = args[0];
    try {
        assertThat(actual).isEqualTo(expected);
        IO.println("Passed");
    } catch (AssertionError error) {
        IO.println("Failed");
    }
}
