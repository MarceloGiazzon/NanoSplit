package com.nanosplit.sql;

import java.io.BufferedInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.Reader;
import java.nio.file.Files;
import java.nio.file.Path;

import com.nanosplit.util.CountingInputStream;

/** Opens a SQL source file for scanning: skips its BOM, decodes with the right charset. */
public final class SqlSources {

    private SqlSources() {
    }

    /** A reader over the source, plus a live counter of raw bytes pulled off disk. */
    public static final class Opened {
        public final Reader reader;
        public final CountingInputStream counter;

        Opened(Reader reader, CountingInputStream counter) {
            this.reader = reader;
            this.counter = counter;
        }
    }

    public static Opened open(Path path, Encodings.Detected enc) throws IOException {
        CountingInputStream counter = new CountingInputStream(
                new BufferedInputStream(Files.newInputStream(path), 1 << 20));
        InputStream in = counter;
        long toSkip = enc.bomBytes;
        while (toSkip > 0) {
            long skipped = in.skip(toSkip);
            if (skipped <= 0) {
                break;
            }
            toSkip -= skipped;
        }
        Reader reader = new InputStreamReader(in, Encodings.toCharset(enc.charset));
        return new Opened(reader, counter);
    }
}
