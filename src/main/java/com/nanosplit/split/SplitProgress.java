package com.nanosplit.split;

/** A snapshot of split progress, handed to a listener a few times a second. */
public final class SplitProgress {
    public final long bytesRead;
    public final long totalBytes;
    public final long statements;
    public final int parts;
    public final double seconds;

    public SplitProgress(long bytesRead, long totalBytes, long statements, int parts, double seconds) {
        this.bytesRead = bytesRead;
        this.totalBytes = totalBytes;
        this.statements = statements;
        this.parts = parts;
        this.seconds = seconds;
    }
}
