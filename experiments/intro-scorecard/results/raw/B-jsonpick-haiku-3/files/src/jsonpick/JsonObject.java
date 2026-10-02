package jsonpick;

import java.util.ArrayList;
import java.util.List;

/** A JSON object that keeps its members in document order. */
final class JsonObject {
    final List<String> keys = new ArrayList<>();
    final List<Object> values = new ArrayList<>();

    void put(String key, Object value) {
        keys.add(key);
        values.add(value);
    }

    int size() {
        return keys.size();
    }

    /** Returns the value of the last member with this key, or null if there is none. */
    Object get(String key) {
        for (int i = keys.size() - 1; i >= 0; i--) {
            if (keys.get(i).equals(key)) {
                return values.get(i);
            }
        }
        return null;
    }
}
