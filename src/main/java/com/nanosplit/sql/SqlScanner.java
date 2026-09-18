package com.nanosplit.sql;

import java.io.IOException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Streaming T-SQL scanner: turns a {@link Reader} into {@link SqlUnit}s
 * without ever holding more than one statement in memory, so a 10 GB script
 * costs about the same as a 10 KB one.
 *
 * <p>What counts as a unit:
 * <ul>
 *   <li>A batch separator line ({@code GO}) is its own unit.</li>
 *   <li>A line matching {@code splittablePattern} starts a new unit, unless
 *       what is pending matches {@code blockPattern} - a construct such as
 *       {@code IF} or {@code DECLARE} that owns the statements after it. That
 *       is what keeps {@code IF ... BEGIN INSERT ... END} in one piece while
 *       still cutting a run of ten million {@code INSERT} lines into ten
 *       million units, and what lets {@code SET IDENTITY_INSERT ... ON} stand
 *       on its own so it can be replayed at the top of any part that needs
 *       it.</li>
 *   <li>Everything else accumulates: multi-line DDL, procedure bodies and
 *       {@code BEGIN}/{@code END} blocks survive as one atomic unit.</li>
 * </ul>
 *
 * <p>Quotes, {@code --} line comments and {@code /* *&#47;} block comments are
 * tracked across line boundaries, so a value containing an embedded newline
 * (GeneXus stores XML that way) never gets split mid-string.
 *
 * <p>Known limitation: cuts happen at line boundaries. Two statements sharing
 * one physical line stay together - coarser, never wrong.
 */
public final class SqlScanner implements Iterable<SqlUnit> {

    /** Constructs that own whatever follows, so cutting inside one is unsafe. */
    public static final Pattern DEFAULT_BLOCK_PATTERN = Pattern.compile(
            "^(IF|ELSE|WHILE|BEGIN|DECLARE|WITH|MERGE|CASE|TRY|GRANT"
                    + "|CREATE\\s+(OR\\s+ALTER\\s+)?(PROC|PROCEDURE|FUNCTION|TRIGGER|VIEW)"
                    + "|ALTER\\s+(PROC|PROCEDURE|FUNCTION|TRIGGER|VIEW))\\b",
            Pattern.CASE_INSENSITIVE);

    private static final Pattern GO_RE = Pattern.compile("^GO(\\s+\\d+)?\\s*(--.*)?$", Pattern.CASE_INSENSITIVE);

    /**
     * Session/permission statements that are always complete on their own
     * line in these generated dumps - see the comment where this is used for
     * why they need to force a cut just like a DML line does.
     */
    private static final Pattern ATOMIC_STATEMENT_RE =
            Pattern.compile("^(SET|USE|GRANT|DENY|REVOKE)\\b", Pattern.CASE_INSENSITIVE);

    private final LineReader reader;
    private final Pattern splittable;
    private final Pattern block;
    private final String batchSeparator;
    private long charOffset = 0;
    private int lineNo = 0;

    public SqlScanner(java.io.Reader reader, Pattern splittable, String batchSeparator) {
        this(reader, splittable, batchSeparator, DEFAULT_BLOCK_PATTERN);
    }

    public SqlScanner(java.io.Reader reader, Pattern splittable, String batchSeparator, Pattern block) {
        this.reader = new LineReader(reader);
        this.splittable = splittable;
        this.batchSeparator = batchSeparator == null ? "" : batchSeparator.trim().toUpperCase();
        this.block = block;
    }

    public long charOffset() {
        return charOffset;
    }

    public int lineNo() {
        return lineNo;
    }

    /** Advances the quote/comment state machine across one line. */
    static boolean[] scanLineState(String line, boolean inString, boolean inBlockComment) {
        int i = 0;
        int n = line.length();
        while (i < n) {
            if (inString) {
                int j = line.indexOf('\'', i);
                if (j < 0) {
                    return new boolean[]{true, false};
                }
                inString = false; // '' toggles off then on again: net no-op, plain toggle is correct
                i = j + 1;
            } else if (inBlockComment) {
                int j = line.indexOf("*/", i);
                if (j < 0) {
                    return new boolean[]{false, true};
                }
                inBlockComment = false;
                i = j + 2;
            } else {
                int quote = line.indexOf('\'', i);
                int dash = line.indexOf("--", i);
                int slash = line.indexOf("/*", i);
                int best = -1;
                char which = 0;
                if (quote >= 0 && (best < 0 || quote < best)) { best = quote; which = 'q'; }
                if (dash >= 0 && (best < 0 || dash < best)) { best = dash; which = 'd'; }
                if (slash >= 0 && (best < 0 || slash < best)) { best = slash; which = 's'; }
                if (best < 0) {
                    return new boolean[]{false, false};
                }
                if (which == 'q') {
                    inString = true;
                    i = best + 1;
                } else if (which == 'd') {
                    return new boolean[]{false, false}; // rest of the line is a comment
                } else {
                    inBlockComment = true;
                    i = best + 2;
                }
            }
        }
        return new boolean[]{inString, inBlockComment};
    }

    private static String stripCommentTail(String stripped) {
        int idx = stripped.indexOf("--");
        if (idx < 0) {
            return stripped;
        }
        String prefix = stripped.substring(0, idx);
        int quotes = 0;
        for (int i = 0; i < prefix.length(); i++) {
            if (prefix.charAt(i) == '\'') {
                quotes++;
            }
        }
        if (quotes % 2 == 0) {
            String r = prefix;
            int end = r.length();
            while (end > 0 && Character.isWhitespace(r.charAt(end - 1))) {
                end--;
            }
            return r.substring(0, end);
        }
        return stripped;
    }

    private boolean isBatchSeparator(String stripped) {
        if (batchSeparator.isEmpty() || stripped.length() > 24) {
            return false;
        }
        String upper = stripped.toUpperCase();
        if (batchSeparator.equals("GO")) {
            return GO_RE.matcher(upper).matches();
        }
        return upper.equals(batchSeparator);
    }

    // Small cache: a dump has millions of lines sharing the same table name
    // prefix, so this turns a per-line regex match into a per-shape one.
    private final Map<String, Boolean> splittableCache = new HashMap<String, Boolean>();

    private boolean isSplittable(String stripped) {
        if (splittable == null) {
            return false;
        }
        String key = stripped.length() > 32 ? stripped.substring(0, 32) : stripped;
        Boolean cached = splittableCache.get(key);
        if (cached == null) {
            cached = splittable.matcher(stripped).find();
            if (splittableCache.size() < 50000) {
                splittableCache.put(key, cached);
            }
        }
        return cached;
    }

    @Override
    public java.util.Iterator<SqlUnit> iterator() {
        return new UnitIterator();
    }

    private final class UnitIterator implements java.util.Iterator<SqlUnit> {
        private final List<SqlUnit> queue = new ArrayList<SqlUnit>(2);
        private boolean exhausted = false;

        private boolean inString = false;
        private boolean inComment = false;
        private StringBuilder pending = null;
        private int pendingLine = 0;
        private int pendingLines = 0;
        private boolean pendingSplittable = false;
        private boolean pendingBlocks = false;

        @Override
        public boolean hasNext() {
            fill();
            return !queue.isEmpty();
        }

        @Override
        public SqlUnit next() {
            fill();
            if (queue.isEmpty()) {
                throw new java.util.NoSuchElementException();
            }
            return queue.remove(0);
        }

        private void fill() {
            while (queue.isEmpty() && !exhausted) {
                step();
            }
        }

        private void flushPending() {
            if (pending != null) {
                String text = rstripNewline(pending.toString());
                queue.add(new SqlUnit(text, SqlUnit.Kind.SQL, pendingLine, pendingLines, pendingSplittable));
                pending = null;
            }
        }

        private void step() {
            String raw;
            try {
                raw = reader.readLine();
            } catch (IOException e) {
                throw new RuntimeException("error reading SQL source: " + e.getMessage(), e);
            }
            if (raw == null) {
                flushPending();
                exhausted = true;
                return;
            }
            lineNo++;
            charOffset += raw.length();
            boolean atTopLevel = !inString && !inComment;

            if (atTopLevel) {
                String stripped = raw.trim();
                if (stripped.isEmpty()) {
                    if (pending != null) {
                        pending.append(raw);
                        pendingLines++;
                    }
                    inString = false;
                    inComment = false;
                    return;
                }

                if (isBatchSeparator(stripCommentTail(stripped))) {
                    flushPending();
                    queue.add(new SqlUnit(stripped, SqlUnit.Kind.BATCH_SEPARATOR, lineNo, 1, false));
                    pendingBlocks = false;
                    inString = false;
                    inComment = false;
                    return;
                }

                boolean lineSplittable = isSplittable(stripped);
                // A line matching ATOMIC_STATEMENT_RE (SET/USE/GRANT/...) is always
                // complete in itself, just like a DML line - it must start a fresh
                // unit too, even though it is not "splittable" (not DML, not counted
                // as an insert). Without this, a one-line SET immediately following a
                // one-line INSERT (no GO between them) would silently glue onto the
                // INSERT's still-open pending buffer instead of becoming its own unit -
                // which then also breaks ContextTracker, since that glued-on SET is
                // never seen as its own unit and so is never observed as context.
                boolean lineAtomic = lineSplittable || ATOMIC_STATEMENT_RE.matcher(stripped).find();
                if (lineAtomic && !pendingBlocks) {
                    flushPending();
                    pending = new StringBuilder(raw);
                    pendingLine = lineNo;
                    pendingLines = 1;
                    pendingSplittable = lineSplittable;
                    boolean[] state = scanLineState(raw, false, false);
                    inString = state[0];
                    inComment = state[1];
                    return;
                }

                if (pending == null) {
                    pendingLine = lineNo;
                    pendingSplittable = lineSplittable;
                    pendingLines = 0;
                    pending = new StringBuilder();
                }
                if (!lineSplittable && block != null && block.matcher(stripped).find()) {
                    pendingBlocks = true;
                }
            }

            if (pending == null) {
                pending = new StringBuilder();
                pendingLine = lineNo;
                pendingSplittable = false;
            }
            pending.append(raw);
            pendingLines++;
            boolean[] state = scanLineState(raw, inString, inComment);
            inString = state[0];
            inComment = state[1];
        }

        @Override
        public void remove() {
            throw new UnsupportedOperationException();
        }
    }

    private static String rstripNewline(String s) {
        int end = s.length();
        while (end > 0 && (s.charAt(end - 1) == '\n' || s.charAt(end - 1) == '\r')) {
            end--;
        }
        return s.substring(0, end);
    }
}
