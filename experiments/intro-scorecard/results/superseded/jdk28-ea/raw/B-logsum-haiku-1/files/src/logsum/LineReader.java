package logsum;

import java.io.BufferedInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;

/**
 * Yields raw input lines one at a time, split on LF only. The terminator is not included,
 * and a final line without a terminator is still returned.
 */
final class LineReader {

    private final InputStream in;
    private final ByteArrayOutputStream buf = new ByteArrayOutputStream();

    LineReader(InputStream in) {
        this.in = new BufferedInputStream(in);
    }

    /** Returns the next line's bytes, or null at end of input. */
    byte[] next() throws IOException {
        buf.reset();
        int b;
        while ((b = in.read()) != -1) {
            if (b == '\n') {
                return buf.toByteArray();
            }
            buf.write(b);
        }
        return buf.size() == 0 ? null : buf.toByteArray();
    }
}
