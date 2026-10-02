///usr/bin/env jbang "$0" "$@" ; exit $?
//JAVA 25+
//DEPS com.grack:nanojson:1.10
//FILES example6.json
//RUNTIME_OPTIONS -ea

import com.grack.nanojson.JsonParser;
import com.grack.nanojson.JsonParserException;

import java.io.IOException;

// #region main
void main() throws IOException, JsonParserException {
    var text = readResource("/example6.json");
    var json = JsonParser.object().from(text);
    IO.println(json.getString("message"));
}

String readResource(String path) throws IOException {
    try (var resource = getClass().getResourceAsStream(path)) {
        assert resource != null : "Resource not found: " + path;
        return new String(resource.readAllBytes());
    }
}
// #endregion main
