package jsonpick;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/** A path such as {@code a.b[0].c}, and its resolution against a parsed document. */
final class JsonPath {

    /** One step of a path: an object key or an array index. */
    sealed interface Step permits Key, Index {
    }

    record Key(String name) implements Step {
    }

    record Index(long position) implements Step {
    }

    private JsonPath() {
    }

    /**
     * Parses dot-separated keys and bracketed indexes. An empty path or {@code "."} selects the root.
     * Keys cannot be empty and cannot contain {@code .} or {@code [}.
     */
    static List<Step> parse(String path) {
        List<Step> steps = new ArrayList<>();
        if (path.isEmpty() || path.equals(".")) {
            return steps;
        }
        int n = path.length();
        int i = path.startsWith(".") ? 1 : 0;
        while (i < n) {
            if (path.charAt(i) == '[') {
                int close = path.indexOf(']', i);
                String digits = close < 0 ? "" : path.substring(i + 1, close);
                if (!isIndex(digits)) {
                    throw invalid(path);
                }
                steps.add(new Index(toIndex(digits)));
                i = close + 1;
                if (i < n && path.charAt(i) != '.' && path.charAt(i) != '[') {
                    throw invalid(path);
                }
            } else {
                int start = i;
                while (i < n && path.charAt(i) != '.' && path.charAt(i) != '[') {
                    i++;
                }
                if (i == start) {
                    throw invalid(path);
                }
                steps.add(new Key(path.substring(start, i)));
            }
            if (i < n && path.charAt(i) == '.') {
                i++;
                if (i == n || path.charAt(i) == '[') {
                    throw invalid(path);
                }
            }
        }
        return steps;
    }

    /** Returns the addressed value, or null when the path does not resolve. */
    static Object resolve(Object root, List<Step> steps) {
        Object current = root;
        for (Step step : steps) {
            if (step instanceof Key key) {
                if (!(current instanceof Map<?, ?> map) || !map.containsKey(key.name())) {
                    return null;
                }
                current = map.get(key.name());
            } else if (step instanceof Index index) {
                if (!(current instanceof List<?> list) || index.position() >= list.size()) {
                    return null;
                }
                current = list.get((int) index.position());
            }
        }
        return current;
    }

    private static boolean isIndex(String digits) {
        return !digits.isEmpty() && digits.chars().allMatch(c -> c >= '0' && c <= '9');
    }

    /** Out-of-range digits cannot match any array, so they saturate rather than fail to parse. */
    private static long toIndex(String digits) {
        try {
            return Long.parseLong(digits);
        } catch (NumberFormatException e) {
            return Long.MAX_VALUE;
        }
    }

    private static CliException invalid(String path) {
        return new CliException("error: invalid path: " + path);
    }
}
