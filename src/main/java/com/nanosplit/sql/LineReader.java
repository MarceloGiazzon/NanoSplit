package com.nanosplit.sql;

import java.io.IOException;
import java.io.Reader;

/**
 * A buffered line reader that keeps the original line terminator on every
 * line it returns (unlike {@link java.io.BufferedReader#readLine()}, which
 * discards it).
 *
 * <p>This matters here because a part must reproduce the source bytes exactly
 * when {@code output.eol=same} - this dataset stores XML with embedded
 * newlines inside quoted string literals, and silently normalising {@code
 * \r\n} to {@code \n} there would change the data being inserted, not just
 * the script's formatting.
 *
 * <p>Reads in bulk ({@link Reader#read(char[], int, int)}) rather than one
 * character at a time, which is what makes scanning a multi-gigabyte file at
 * tens of MB/s possible in the JVM.
 */
public final class LineReader implements java.io.Closeable {

    private final Reader source;
    private final char[] buffer;
    private int pos = 0;
    private int limit = 0;
    private boolean eof = false;

    public LineReader(Reader source, int bufferSize) {
        this.source = source;
        this.buffer = new char[Math.max(4096, bufferSize)];
    }

    public LineReader(Reader source) {
        this(source, 1 << 16);
    }

    private boolean refill() throws IOException {
        if (eof) {
            return false;
        }
        int n = source.read(buffer, 0, buffer.length);
        if (n < 0) {
            eof = true;
            return false;
        }
        pos = 0;
        limit = n;
        return true;
    }

    /** Next line including its terminator ({@code \n}, {@code \r\n}, or {@code \r}), or null at EOF. */
    public String readLine() throws IOException {
        StringBuilder sb = null;
        while (true) {
            if (pos >= limit) {
                if (!refill()) {
                    return (sb != null && sb.length() > 0) ? sb.toString() : null;
                }
            }
            int start = pos;
            while (pos < limit) {
                char c = buffer[pos];
                pos++;
                if (c == '\n') {
                    if (sb == null) {
                        return new String(buffer, start, pos - start);
                    }
                    sb.append(buffer, start, pos - start);
                    return sb.toString();
                }
                if (c == '\r') {
                    // Could be a lone \r or the start of \r\n; peek one more char.
                    if (pos < limit) {
                        if (buffer[pos] == '\n') {
                            pos++;
                        }
                        if (sb == null) {
                            return new String(buffer, start, pos - start);
                        }
                        sb.append(buffer, start, pos - start);
                        return sb.toString();
                    }
                    // \r is the last char currently buffered: fall through to
                    // refill and resolve the \r\n-vs-\r ambiguity on the next pass.
                    if (sb == null) {
                        sb = new StringBuilder(128);
                    }
                    sb.append(buffer, start, pos - start);
                    if (!refill()) {
                        return sb.toString();
                    }
                    if (limit > 0 && buffer[0] == '\n') {
                        sb.append('\n');
                        pos = 1;
                    }
                    return sb.toString();
                }
            }
            if (sb == null) {
                sb = new StringBuilder(256);
            }
            sb.append(buffer, start, pos - start);
        }
    }

    @Override
    public void close() throws IOException {
        source.close();
    }
}
