package com.nanosplit.split;

import java.io.BufferedOutputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.nio.CharBuffer;
import java.nio.ByteBuffer;
import java.nio.charset.CharsetEncoder;
import java.nio.charset.CodingErrorAction;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;

import com.nanosplit.sql.Encodings;

/**
 * Buffered writer that tracks byte offsets and a running SHA-256 digest as it
 * writes, so a part's first/last statement offsets and its checksum are known
 * for free once the write finishes - no separate re-read of the file needed.
 */
public final class PartWriter {

    public final Path path;
    private final String charset;
    private final String eol;
    private final OutputStream out;
    private final CharsetEncoder encoder;
    private final MessageDigest digest;
    private ByteBuffer scratch = ByteBuffer.allocate(1 << 16);
    private long pos = 0;
    private int lines = 1;

    public PartWriter(Path path, String charset, boolean bom, String eol, boolean hashContent) throws IOException {
        this.path = path;
        this.charset = charset;
        this.eol = eol;
        this.out = new BufferedOutputStream(Files.newOutputStream(path), 1 << 20);
        this.encoder = Encodings.toCharset(charset).newEncoder()
                .onMalformedInput(CodingErrorAction.REPLACE)
                .onUnmappableCharacter(CodingErrorAction.REPLACE);
        try {
            this.digest = hashContent ? MessageDigest.getInstance("SHA-256") : null;
        } catch (NoSuchAlgorithmException e) {
            throw new IOException("SHA-256 not available", e);
        }
        byte[] bomBytes = Encodings.bomBytes(charset, bom);
        if (bomBytes.length > 0) {
            writeBytes(bomBytes, 0, bomBytes.length);
        }
    }

    public long pos() {
        return pos;
    }

    public int lines() {
        return lines;
    }

    /** Appends text; returns the byte offset it was written at. */
    public long write(String text) throws IOException {
        long start = pos;
        CharBuffer in = CharBuffer.wrap(text);
        while (in.hasRemaining()) {
            scratch.clear();
            encoder.encode(in, scratch, false);
            scratch.flip();
            writeBytes(scratch.array(), scratch.arrayOffset() + scratch.position(), scratch.remaining());
        }
        for (int i = 0; i < text.length(); i++) {
            if (text.charAt(i) == '\n') {
                lines++;
            }
        }
        return start;
    }

    public void writeLine(String text) throws IOException {
        write(text);
        write(eol);
    }

    public void writeLine() throws IOException {
        write(eol);
    }

    /** Writes one statement plus its EOL; returns its offset and byte length (EOL excluded). */
    public StatementPosition writeStatement(String text) throws IOException {
        long start = write(text);
        long bytesWritten = pos - start;
        write(eol);
        return new StatementPosition(start, bytesWritten);
    }

    private void writeBytes(byte[] buf, int off, int len) throws IOException {
        if (len <= 0) {
            return;
        }
        out.write(buf, off, len);
        if (digest != null) {
            digest.update(buf, off, len);
        }
        pos += len;
    }

    public CloseResult close() throws IOException {
        CharBuffer empty = CharBuffer.allocate(0);
        scratch.clear();
        encoder.encode(empty, scratch, true);
        scratch.flip();
        writeBytes(scratch.array(), scratch.arrayOffset() + scratch.position(), scratch.remaining());
        boolean underflow;
        do {
            scratch.clear();
            underflow = encoder.flush(scratch).isUnderflow();
            scratch.flip();
            writeBytes(scratch.array(), scratch.arrayOffset() + scratch.position(), scratch.remaining());
        } while (!underflow);
        out.close();
        return new CloseResult(pos, digest != null ? toHex(digest.digest()) : null);
    }

    private static String toHex(byte[] bytes) {
        StringBuilder sb = new StringBuilder(bytes.length * 2);
        for (byte b : bytes) {
            sb.append(Character.forDigit((b >> 4) & 0xF, 16));
            sb.append(Character.forDigit(b & 0xF, 16));
        }
        return sb.toString();
    }

    public static final class StatementPosition {
        public final long offset;
        public final long bytes;

        StatementPosition(long offset, long bytes) {
            this.offset = offset;
            this.bytes = bytes;
        }
    }

    public static final class CloseResult {
        public final long bytes;
        public final String sha256;

        CloseResult(long bytes, String sha256) {
            this.bytes = bytes;
            this.sha256 = sha256;
        }
    }
}
