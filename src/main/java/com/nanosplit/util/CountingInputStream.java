package com.nanosplit.util;

import java.io.FilterInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.util.concurrent.atomic.AtomicLong;

/** Tracks bytes pulled off the underlying stream, for progress reporting. */
public final class CountingInputStream extends FilterInputStream {

    private final AtomicLong count = new AtomicLong();

    public CountingInputStream(InputStream in) {
        super(in);
    }

    public long count() {
        return count.get();
    }

    @Override
    public int read() throws IOException {
        int b = super.read();
        if (b >= 0) {
            count.incrementAndGet();
        }
        return b;
    }

    @Override
    public int read(byte[] b, int off, int len) throws IOException {
        int n = super.read(b, off, len);
        if (n > 0) {
            count.addAndGet(n);
        }
        return n;
    }
}
