package jarsrc;

import java.nio.charset.StandardCharsets;
import java.util.Arrays;

/** Lexicographic (byte-wise over UTF-8) string ordering. */
final class Text {
    private Text() {}

    static int lex(String a, String b) {
        return Arrays.compareUnsigned(a.getBytes(StandardCharsets.UTF_8), b.getBytes(StandardCharsets.UTF_8));
    }
}
