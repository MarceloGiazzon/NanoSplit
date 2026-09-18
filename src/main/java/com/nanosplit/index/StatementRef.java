package com.nanosplit.index;

/** Where one statement lives inside its part file, plus a short preview. */
public final class StatementRef {
    public long offset;      // byte offset of the statement's first byte
    public long bytes;       // length in bytes, excluding the trailing EOL
    public int sourceLine;   // 1-based line in the original source file
    public String preview;   // flattened, length-capped text
    public boolean insert;   // true for a splittable DML statement
}
