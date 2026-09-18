package com.nanosplit.index;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;

/** Root object of {@code index.json}, written once by {@code split}. */
public final class IndexData {
    public static final int FORMAT_VERSION = 1;

    public String tool = "NanoSplit";
    public String version;
    public int formatVersion = FORMAT_VERSION;
    public String createdAt;

    public Source source = new Source();
    public Output output = new Output();
    public Options options = new Options();
    public Totals totals = new Totals();
    public List<PartEntry> parts = new ArrayList<PartEntry>();

    public static final class Source {
        public String path;
        public String name;
        public long size;
        public String encoding;
        public Integer bytesPerChar; // null when the encoding is variable-width (e.g. UTF-8)
        public long characters;
        public int lines;
    }

    public static final class Output {
        public String dir;
        public String encoding;
        public String eol;
        public String prefix;
    }

    public static final class Options {
        public int parts;
        public int maxStatementsPerPart;
        public long maxBytesPerPart;
        public String batchSeparator;
        public int emitBatchSeparatorEvery;
        public String splittablePattern;
        public String contextPattern;
        public boolean carryContext;
    }

    public static final class Totals {
        public int parts;
        public long statements;
        public long inserts;
        public long batchSeparators;
        public long bytes;
        public double seconds;
        public LinkedHashMap<String, Long> tables = new LinkedHashMap<String, Long>();
    }
}
