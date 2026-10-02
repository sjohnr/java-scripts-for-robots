// #region all
// #region main
import module jdk.incubator.json;

void main() throws IOException {
    var text = readResource("/example6.json");
    var json = Json.parse(text);
    IO.println(json.get("message").asString());
}
// #endregion main

String readResource(String path) throws IOException {
    try (var resource = getClass().getResourceAsStream(path)) {
        assert resource != null : "Resource not found: " + path;
        return new String(resource.readAllBytes());
    }
}
// #endregion all
