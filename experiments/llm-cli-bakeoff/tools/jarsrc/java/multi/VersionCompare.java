public class VersionCompare {
    public static int cmpVersion(String a, String b) {
        String[] sa = a.split("[.\\-]", -1);
        String[] sb = b.split("[.\\-]", -1);
        int n = Math.min(sa.length, sb.length);
        for (int i = 0; i < n; i++) {
            int c = cmpSegment(sa[i], sb[i]);
            if (c != 0) return c;
        }
        return Integer.compare(sa.length, sb.length);
    }

    static boolean isNonNegInt(String s) {
        if (s.isEmpty()) return false;
        for (int i = 0; i < s.length(); i++) if (!Character.isDigit(s.charAt(i))) return false;
        return true;
    }

    static int cmpSegment(String a, String b) {
        if (isNonNegInt(a) && isNonNegInt(b)) {
            return new java.math.BigInteger(a).compareTo(new java.math.BigInteger(b));
        }
        return a.compareTo(b);
    }
}
