package jsonpick;

import java.util.ArrayList;
import java.util.List;

/** Parses path expressions such as {@code a.b[0].c} into key and index steps. */
final class JsonPath {
    private JsonPath() {}

    /**
     * Returns the steps of {@code path}. Each step is a {@link String} (object key) or a
     * {@link Long} (array index). A leading {@code .}, an empty path or {@code .} selects the root.
     *
     * @throws IllegalArgumentException if the path is malformed
     */
    static List<Object> parse(String path) {
        List<Object> steps = new ArrayList<>();
        int n = path.length();
        int i = 0;
        if (n > 0 && path.charAt(0) == '.') {
            i = 1;
            if (i < n && path.charAt(i) == '.') {
                throw new IllegalArgumentException(path);
            }
        }
        boolean afterDot = false;
        while (i < n) {
            char c = path.charAt(i);
            if (c == '.') {
                if (afterDot || i == n - 1) {
                    throw new IllegalArgumentException(path);
                }
                afterDot = true;
                i++;
            } else if (c == '[') {
                if (afterDot) {
                    throw new IllegalArgumentException(path);
                }
                int close = path.indexOf(']', i);
                if (close < 0) {
                    throw new IllegalArgumentException(path);
                }
                String digits = path.substring(i + 1, close);
                if (!digits.matches("[0-9]+")) {
                    throw new IllegalArgumentException(path);
                }
                steps.add(parseIndex(digits));
                i = close + 1;
            } else {
                if (!steps.isEmpty() && !afterDot) {
                    throw new IllegalArgumentException(path);
                }
                int end = i;
                while (end < n && path.charAt(end) != '.' && path.charAt(end) != '[') {
                    end++;
                }
                steps.add(path.substring(i, end));
                afterDot = false;
                i = end;
            }
        }
        return steps;
    }

    /** Returns the value at the given steps, or null if the path does not resolve. */
    static Object resolve(Object root, List<Object> steps) {
        Object current = root;
        for (Object step : steps) {
            if (step instanceof String key) {
                if (!(current instanceof JsonObject obj)) {
                    return null;
                }
                current = obj.get(key);
                if (current == null) {
                    return null;
                }
            } else {
                if (!(current instanceof List<?> list)) {
                    return null;
                }
                long index = (Long) step;
                if (index >= list.size()) {
                    return null;
                }
                current = list.get((int) index);
            }
        }
        return current;
    }

    private static long parseIndex(String digits) {
        try {
            return Long.parseLong(digits);
        } catch (NumberFormatException e) {
            // Too large to be a valid index in any document, so it simply does not resolve.
            return Long.MAX_VALUE;
        }
    }
}
