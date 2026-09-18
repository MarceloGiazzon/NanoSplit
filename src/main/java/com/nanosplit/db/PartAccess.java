package com.nanosplit.db;

import java.io.IOException;
import java.io.RandomAccessFile;
import java.nio.charset.Charset;

import com.nanosplit.index.StatementRef;
import com.nanosplit.sql.Encodings;

/**
 * Reads one statement's exact text straight out of a part file using the byte
 * offset {@code split} recorded for it - no need to re-scan gigabytes just to
 * fetch the first or last statement.
 */
public final class PartAccess {

    private PartAccess() {
    }

    public static String readStatement(java.io.File partFile, StatementRef ref, String charset) throws IOException {
        Charset cs = Encodings.toCharset(charset);
        byte[] buf = new byte[(int) ref.bytes];
        try (RandomAccessFile raf = new RandomAccessFile(partFile, "r")) {
            raf.seek(ref.offset);
            raf.readFully(buf);
        }
        return new String(buf, cs);
    }
}
