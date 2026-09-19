package com.nanosplit.sql;

import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Wraps {@code CREATE DATABASE}/{@code CREATE TABLE}/{@code CREATE INDEX}
 * statements in an existence check, so re-running a part against a database
 * that already has some or all of that schema - because a previous attempt
 * got partway through before failing or being interrupted - skips the
 * objects that already exist instead of erroring on them.
 *
 * <p>This is what makes the index/state resume story actually work end to
 * end: a split script's job is to import into a target, and that target is
 * very often not pristine - it is exactly the database a prior, partial
 * {@code run} left behind. SSMS's own generated scripts assume a pristine
 * target and are not safe to re-run; NanoSplit's are.
 *
 * <p>Plain DML ({@code INSERT}/{@code UPDATE}/{@code DELETE}) is never
 * touched - only schema-creation DDL, which is what actually breaks a naive
 * retry.
 */
public final class IdempotentDdl {

    private static final Pattern CREATE_DATABASE = Pattern.compile(
            "^CREATE\\s+DATABASE\\s+(\\[[^\\]]+\\]|[A-Za-z_][\\w$#]*)", Pattern.CASE_INSENSITIVE);

    private static final Pattern CREATE_TABLE = Pattern.compile(
            "^CREATE\\s+TABLE\\s+((?:\\[[^\\]]+\\]|[A-Za-z_][\\w$#]*)"
                    + "(?:\\s*\\.\\s*(?:\\[[^\\]]+\\]|[A-Za-z_][\\w$#]*)){0,2})\\s*\\(",
            Pattern.CASE_INSENSITIVE);

    private static final Pattern CREATE_INDEX = Pattern.compile(
            "^CREATE\\s+(?:UNIQUE\\s+)?(?:CLUSTERED\\s+|NONCLUSTERED\\s+)?INDEX\\s+"
                    + "(\\[[^\\]]+\\]|[A-Za-z_][\\w$#]*)\\s+ON\\s+"
                    + "((?:\\[[^\\]]+\\]|[A-Za-z_][\\w$#]*)(?:\\s*\\.\\s*(?:\\[[^\\]]+\\]|[A-Za-z_][\\w$#]*)){0,2})",
            Pattern.CASE_INSENSITIVE);

    private IdempotentDdl() {
    }

    /** Wraps {@code text} in an existence guard if it is DDL this class knows how to guard, else returns it unchanged. */
    public static String wrap(String text) {
        int i = skipWhitespaceAndComments(text);
        String leading = text.substring(0, i);
        String trimmed = text.substring(i);

        Matcher m = CREATE_DATABASE.matcher(trimmed);
        if (m.find() && m.start() == 0) {
            String literal = unbracketAndEscape(m.group(1));
            return leading + "IF DB_ID(N'" + literal + "') IS NULL\r\nBEGIN\r\n" + trimmed + "\r\nEND";
        }

        m = CREATE_TABLE.matcher(trimmed);
        if (m.find() && m.start() == 0) {
            String qualified = escapeForNString(m.group(1).replaceAll("\\s*\\.\\s*", "."));
            return leading + "IF OBJECT_ID(N'" + qualified + "', N'U') IS NULL\r\nBEGIN\r\n" + trimmed + "\r\nEND";
        }

        m = CREATE_INDEX.matcher(trimmed);
        if (m.find() && m.start() == 0) {
            String indexLiteral = unbracketAndEscape(m.group(1));
            String tableLiteral = escapeForNString(m.group(2).replaceAll("\\s*\\.\\s*", "."));
            return leading + "IF NOT EXISTS (SELECT 1 FROM sys.indexes WHERE name = N'" + indexLiteral
                    + "' AND object_id = OBJECT_ID(N'" + tableLiteral + "'))\r\nBEGIN\r\n" + trimmed + "\r\nEND";
        }

        return text;
    }

    /**
     * Index just past any leading whitespace and comments (SSMS prefixes every
     * DDL statement it generates with a {@code /****** Objeto: ... ******&#47;}
     * block comment on its own line) - i.e. the offset the actual keyword
     * ({@code CREATE}, ...) starts at.
     */
    public static int skipWhitespaceAndComments(String text) {
        int i = 0;
        int n = text.length();
        while (i < n) {
            char c = text.charAt(i);
            if (Character.isWhitespace(c)) {
                i++;
            } else if (c == '-' && i + 1 < n && text.charAt(i + 1) == '-') {
                int nl = text.indexOf('\n', i);
                i = (nl < 0) ? n : nl + 1;
            } else if (c == '/' && i + 1 < n && text.charAt(i + 1) == '*') {
                int end = text.indexOf("*/", i + 2);
                i = (end < 0) ? n : end + 2;
            } else {
                break;
            }
        }
        return i;
    }

    private static String unbracketAndEscape(String identifier) {
        String bare = identifier;
        if (bare.startsWith("[") && bare.endsWith("]")) {
            bare = bare.substring(1, bare.length() - 1);
        }
        return bare.replace("'", "''");
    }

    private static String escapeForNString(String literal) {
        return literal.replace("'", "''");
    }
}
