package jarsrc;

import java.nio.charset.StandardCharsets;
import java.util.Arrays;

/** Version ordering as defined in SPEC.md, "Version comparison". */
final class Version {

    private Version() {
    }

    static int compare(String a, String b) {
        String[] x = a.split("[.-]", -1);
        String[] y = b.split("[.-]", -1);
        int n = Math.min(x.length, y.length);
        for (int i = 0; i < n; i++) {
            int c = compareSegment(x[i], y[i]);
            if (c != 0) {
                return c;
            }
        }
        return Integer.compare(x.length, y.length);
    }

    private static int compareSegment(String p, String q) {
        if (isDigits(p) && isDigits(q)) {
            String ps = p.replaceFirst("^0+(?=.)", "");
            String qs = q.replaceFirst("^0+(?=.)", "");
            if (ps.length() != qs.length()) {
                return Integer.compare(ps.length(), qs.length());
            }
            return ps.compareTo(qs);
        }
        return Arrays.compareUnsigned(p.getBytes(StandardCharsets.UTF_8), q.getBytes(StandardCharsets.UTF_8));
    }

    private static boolean isDigits(String s) {
        if (s.isEmpty()) {
            return false;
        }
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            if (c < '0' || c > '9') {
                return false;
            }
        }
        return true;
    }
}
