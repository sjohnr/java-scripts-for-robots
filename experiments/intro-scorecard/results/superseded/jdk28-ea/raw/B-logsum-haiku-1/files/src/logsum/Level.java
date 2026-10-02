package logsum;

/** Log levels, in the fixed order used for output. Matching is case-sensitive. */
enum Level {
    DEBUG, INFO, WARN, ERROR;

    /** Returns the level named exactly by {@code token}, or null if there is none. */
    static Level parse(String token) {
        for (Level level : values()) {
            if (level.name().equals(token)) {
                return level;
            }
        }
        return null;
    }
}
