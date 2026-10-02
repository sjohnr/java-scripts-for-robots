package jsonpick;

/** A failure reported on stderr with exit code 2 (usage error or unparsable input). */
final class CliException extends RuntimeException {

    private static final long serialVersionUID = 1L;

    CliException(String message) {
        super(message);
    }
}
