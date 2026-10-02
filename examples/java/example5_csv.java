void main() throws IOException {
    var people = readResource("/example5.csv").lines()
            .skip(1)
            .map(line -> line.split(","))
            .map(fields -> new Person(fields[0], Integer.parseInt(fields[1]), fields[2]))
            .toList();
    people.forEach(IO::println);
}

String readResource(String path) throws IOException {
    try (var resource = getClass().getResourceAsStream(path)) {
        assert resource != null : "Resource not found: " + path;
        return new String(resource.readAllBytes());
    }
}

record Person(String name, int age, String city) {}
