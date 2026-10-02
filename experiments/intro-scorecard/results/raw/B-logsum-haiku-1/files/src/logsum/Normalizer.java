package logsum;

import java.util.regex.Pattern;

/** Collapses the variable parts of a message so that similar errors group together. */
final class Normalizer {

    private static final Pattern UUID = Pattern.compile(
            "[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}");
    private static final Pattern HEX = Pattern.compile("\\b(?=[0-9a-fA-F]*[a-fA-F])[0-9a-fA-F]{6,}\\b");
    private static final Pattern NUM = Pattern.compile("[0-9]+");

    private Normalizer() {}

    /** Order matters: UUIDs must be replaced before hex runs, or they would be split apart. */
    static String normalize(String message) {
        String s = UUID.matcher(message).replaceAll("<uuid>");
        s = HEX.matcher(s).replaceAll("<hex>");
        return NUM.matcher(s).replaceAll("<num>");
    }
}
