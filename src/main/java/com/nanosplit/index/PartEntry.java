package com.nanosplit.index;

import java.util.LinkedHashMap;
import java.util.List;

/** One part file's description, as recorded in {@code index.json}. */
public final class PartEntry {
    public int n;                 // 1-based part number
    public String file;           // file name, relative to the index
    public int statements;
    public int inserts;
    public LinkedHashMap<String, Integer> tables = new LinkedHashMap<String, Integer>();
    public List<String> context;  // session statements replayed at the top
    public List<String> closers;  // IDENTITY_INSERT ... OFF appended at the bottom
    public int sourceLineStart;
    public int sourceLineEnd;
    public StatementRef firstStatement;
    public StatementRef lastStatement;
    public long bytes;
    public String sha256;
    public int lines;
}
