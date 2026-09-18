package com.nanosplit.sql;

import java.util.regex.Pattern;

/** One atomic chunk of SQL produced by {@link SqlScanner}. */
public final class SqlUnit {

    public enum Kind { SQL, BATCH_SEPARATOR }

    private static final Pattern WS = Pattern.compile("\\s+");

    public final String text;
    public final Kind kind;
    public final int line;       // 1-based first source line
    public final int lineCount;  // physical lines spanned
    public final boolean splittable;

    private String head;

    public SqlUnit(String text, Kind kind, int line, int lineCount, boolean splittable) {
        this.text = text;
        this.kind = kind;
        this.line = line;
        this.lineCount = lineCount;
        this.splittable = splittable;
    }

    /** Normalised, length-capped, upper-cased prefix - handy for reports and dispatch. */
    public String normalisedHead(int limit) {
        if (head == null) {
            String flat = WS.matcher(text.substring(0, Math.min(text.length(), limit * 2)).trim()).replaceAll(" ");
            head = flat.substring(0, Math.min(flat.length(), limit)).toUpperCase();
        }
        return head;
    }

    @Override
    public String toString() {
        String preview = text.length() > 60 ? text.substring(0, 60) : text;
        return "SqlUnit(" + kind + ", line=" + line + ", " + preview.replace("\n", "\\n") + ")";
    }
}
