///usr/bin/env jbang "$0" "$@" ; exit $?
//JAVA 25+
//DEPS org.apache.commons:commons-csv:1.14.1
//FILES example5.csv
//RUNTIME_OPTIONS -ea

import org.apache.commons.csv.CSVFormat;

import java.io.IOException;
import java.io.StringReader;

// #region all
// #region main
void main() throws IOException {
    var format = CSVFormat.DEFAULT.builder().setHeader().setSkipHeaderRecord(true).get();
    try (var parser = format.parse(new StringReader(readResource("/example5.csv")))) {
        var people = parser.stream()
                .map(record -> new Person(record.get("name"), Integer.parseInt(record.get("age")), record.get("city")))
                .toList();
        people.forEach(IO::println);
    }
}
// #endregion main

String readResource(String path) throws IOException {
    try (var resource = getClass().getResourceAsStream(path)) {
        assert resource != null : "Resource not found: " + path;
        return new String(resource.readAllBytes());
    }
}

record Person(String name, int age, String city) {}
// #endregion all
