package jsonpick;

import java.math.BigInteger;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Parses PATH syntax such as {@code a.b[0][1].c}. Each step is either a {@code String} (object key)
 * or a {@code BigInteger} (zero-based array index).
 */
final class Selector {

    /** A whole path: optional leading dot, then keys and indexes, keys joined by dots. */
    private static final Pattern SYNTAX =
            Pattern.compile("\\.?(?:[^.\\[\\]]+|\\[\\d+\\])(?:\\.[^.\\[\\]]+|\\[\\d+\\])*");

    /** One step of a valid path. */
    private static final Pattern STEP = Pattern.compile("\\[(\\d+)\\]|([^.\\[\\]]+)");

    private Selector() {
    }

    /**
     * Returns the steps of a path; empty for the document root. Throws IllegalArgumentException
     * when the path is not syntactically valid.
     */
    static List<Object> parse(String path) {
        List<Object> steps = new ArrayList<>();
        if (path.isEmpty() || path.equals(".")) {
            return steps;
        }
        if (!SYNTAX.matcher(path).matches()) {
            throw new IllegalArgumentException("invalid path: " + path);
        }
        Matcher m = STEP.matcher(path);
        while (m.find()) {
            if (m.group(1) != null) {
                steps.add(new BigInteger(m.group(1)));
            } else {
                steps.add(m.group(2));
            }
        }
        return steps;
    }

    /** Walks the steps from {@code node}. Returns null when the path does not resolve. */
    static Object resolve(Object node, List<Object> steps) {
        Object current = node;
        for (Object step : steps) {
            if (step instanceof String key) {
                if (!(current instanceof Map<?, ?> map)) {
                    return null;
                }
                current = map.get(key);
            } else {
                BigInteger index = (BigInteger) step;
                if (!(current instanceof List<?> list)) {
                    return null;
                }
                if (index.compareTo(BigInteger.valueOf(list.size())) >= 0) {
                    return null;
                }
                current = list.get(index.intValue());
            }
            if (current == null) {
                return null;
            }
        }
        return current;
    }
}
