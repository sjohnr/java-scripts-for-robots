package jarsrc;

import java.util.ArrayList;
import java.util.Collection;
import java.util.List;

/** The version ordering from SPEC.md, "Version comparison". */
final class Version {
    private Version() {}

    static int compare(String a, String b) {
        String[] x = a.split("[.-]", -1);
        String[] y = b.split("[.-]", -1);
        for (int i = 0; i < Math.min(x.length, y.length); i++) {
            int c = segment(x[i], y[i]);
            if (c != 0) {
                return c;
            }
        }
        return Integer.compare(x.length, y.length);
    }

    /** Ascending order. Insertion sort, because this ordering is not transitive across mixed segments. */
    static List<String> ascending(Collection<String> versions) {
        List<String> out = new ArrayList<>(versions);
        out.sort(Text::lex);
        for (int i = 1; i < out.size(); i++) {
            String v = out.get(i);
            int j = i - 1;
            while (j >= 0 && compare(out.get(j), v) > 0) {
                out.set(j + 1, out.get(j));
                j--;
            }
            out.set(j + 1, v);
        }
        return out;
    }

    private static int segment(String a, String b) {
        if (isNumber(a) && isNumber(b)) {
            return compareNumbers(a, b);
        }
        return Text.lex(a, b);
    }

    private static boolean isNumber(String s) {
        if (s.isEmpty()) {
            return false;
        }
        for (int i = 0; i < s.length(); i++) {
            if (s.charAt(i) < '0' || s.charAt(i) > '9') {
                return false;
            }
        }
        return true;
    }

    /** Compares without parsing, so arbitrarily long digit runs cannot overflow. */
    private static int compareNumbers(String a, String b) {
        String x = stripLeadingZeros(a);
        String y = stripLeadingZeros(b);
        if (x.length() != y.length()) {
            return Integer.compare(x.length(), y.length());
        }
        return x.compareTo(y);
    }

    private static String stripLeadingZeros(String s) {
        int i = 0;
        while (i < s.length() - 1 && s.charAt(i) == '0') {
            i++;
        }
        return s.substring(i);
    }
}
