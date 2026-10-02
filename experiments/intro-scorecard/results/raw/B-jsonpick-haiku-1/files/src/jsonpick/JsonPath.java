package jsonpick;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * Path syntax: dot-separated object keys and zero-based [N] array indexes,
 * e.g. {@code a.b[0][1].c}. Empty, "." and a leading "." select the root.
 * Keys cannot contain '.' or '['.
 */
final class JsonPath {

    /** Returned by {@link #resolve} when the path does not exist in the document. */
    static final Object MISSING = new Object();

    private JsonPath() {
    }

    /**
     * Parses a path into segments: String for a key, Integer for an index.
     *
     * @throws IllegalArgumentException if the path is syntactically malformed
     */
    static List<Object> parse(String path) {
        List<Object> segments = new ArrayList<>();
        int n = path.length();
        if (n == 0 || path.equals(".")) {
            return segments;
        }
        int i = 0;
        if (path.charAt(0) == '.') {
            i = 1;
            requireKeyAt(path, i);
        }
        while (i < n) {
            char c = path.charAt(i);
            if (c == '[') {
                int close = path.indexOf(']', i);
                if (close < 0) {
                    throw malformed(path);
                }
                String digits = path.substring(i + 1, close);
                if (digits.isEmpty() || !digits.chars().allMatch(ch -> ch >= '0' && ch <= '9')) {
                    throw malformed(path);
                }
                segments.add(saturatedIndex(digits));
                i = close + 1;
                if (i < n && path.charAt(i) != '.' && path.charAt(i) != '[') {
                    throw malformed(path);
                }
            } else {
                int end = i;
                while (end < n && path.charAt(end) != '.' && path.charAt(end) != '[') {
                    end++;
                }
                if (end == i) {
                    throw malformed(path);
                }
                segments.add(path.substring(i, end));
                i = end;
            }
            if (i < n && path.charAt(i) == '.') {
                i++;
                requireKeyAt(path, i);
            }
        }
        return segments;
    }

    /** Looks up the segments from root; returns MISSING when any step fails. */
    static Object resolve(Object root, List<Object> segments) {
        Object current = root;
        for (Object segment : segments) {
            if (segment instanceof String) {
                if (!(current instanceof Map) || !((Map<?, ?>) current).containsKey(segment)) {
                    return MISSING;
                }
                current = ((Map<?, ?>) current).get(segment);
            } else {
                int index = (Integer) segment;
                if (!(current instanceof List) || index >= ((List<?>) current).size()) {
                    return MISSING;
                }
                current = ((List<?>) current).get(index);
            }
        }
        return current;
    }

    /** After a '.', a key must follow; "a.", "a..b" and "a.[0]" are malformed. */
    private static void requireKeyAt(String path, int i) {
        if (i >= path.length() || path.charAt(i) == '[' || path.charAt(i) == '.') {
            throw malformed(path);
        }
    }

    private static Integer saturatedIndex(String digits) {
        if (digits.length() > 9) {
            return Integer.MAX_VALUE; // larger than any document array; resolves to MISSING
        }
        return Integer.valueOf(digits);
    }

    private static IllegalArgumentException malformed(String path) {
        return new IllegalArgumentException("invalid path: " + path);
    }
}
