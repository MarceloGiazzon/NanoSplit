package com.nanosplit.split;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.regex.Pattern;

import com.nanosplit.config.AppConfig;
import com.nanosplit.config.ConfigException;
import com.nanosplit.index.Index;
import com.nanosplit.index.IndexData;
import com.nanosplit.index.PartEntry;
import com.nanosplit.index.StatementRef;
import com.nanosplit.sql.ContextTracker;
import com.nanosplit.sql.Encodings;
import com.nanosplit.sql.SqlScanner;
import com.nanosplit.sql.SqlSources;
import com.nanosplit.sql.SqlUnit;
import com.nanosplit.sql.TableNames;
import com.nanosplit.util.Version;

/**
 * Splits one huge script into parts that can each run on their own.
 *
 * <p>The hard part of splitting a generated SQL dump is not cutting it - it is
 * that a cut loses session context. {@code USE [db]} appears once, at the top
 * of a 10 GB file; {@code SET IDENTITY_INSERT [t] ON} appears once per table.
 * A part that starts with a bare {@code INSERT} would run against the wrong
 * database, or be rejected for writing an identity column.
 *
 * <p>So the splitter carries that context via {@link ContextTracker}: it
 * replays it at the top of every part and closes any {@code IDENTITY_INSERT}
 * left open at the bottom. Each part is then independently runnable and
 * re-runnable.
 */
public final class Splitter {

    private static final int PREVIEW_CHARS = 200;

    private final AppConfig cfg;
    private final File inputFile;
    private final Encodings.Detected encoding;
    private final String outEncoding;
    private final boolean outBom;
    private final File outputDir;
    private final String eolSetting; // "same" | "\r\n" | "\n"
    private final String genEol;     // real EOL used for generated lines (never "same")
    private final String prefix;
    private final int digits;
    private final String batchSeparator;
    private final int goEvery;
    private final int maxStatements;
    private final long maxBytes;
    private final int fixedParts;
    private final boolean carryContext;
    private final boolean hashContent;
    private final String header;
    private final String footer;
    private final Pattern splittable;
    private final Pattern contextPattern;
    private final long sourceSize;
    private final String renameFrom;
    private final String renameTo;

    public Splitter(AppConfig cfg) throws ConfigException, IOException {
        this.cfg = cfg;
        this.inputFile = cfg.path("input.file", true);
        this.encoding = Encodings.detect(inputFile, cfg.get("input.encoding"));
        String outEnc = cfg.get("output.encoding").trim();
        this.outEncoding = outEnc.equalsIgnoreCase("same") || outEnc.isEmpty() ? encoding.charset : outEnc.toUpperCase();
        this.outBom = outEnc.equalsIgnoreCase("same") || outEnc.isEmpty() ? encoding.bom : Encodings.detect(inputFile, outEnc).bom;
        this.outputDir = cfg.path("output.dir", false);
        this.eolSetting = resolveEol(cfg.get("output.eol"));
        this.genEol = eolSetting.equals("same") ? "\r\n" : eolSetting;
        this.prefix = cfg.get("output.prefix");
        this.digits = Math.max(1, cfg.intVal("output.digits"));
        this.batchSeparator = cfg.get("split.batchSeparator").trim();
        this.goEvery = cfg.intVal("split.emitBatchSeparatorEvery");
        this.maxStatements = cfg.intVal("split.maxStatementsPerPart");
        this.maxBytes = cfg.size("split.maxBytesPerPart");
        this.fixedParts = cfg.intVal("split.parts");
        this.carryContext = cfg.bool("split.carryContext");
        this.hashContent = cfg.bool("output.hash");
        this.header = cfg.get("split.header");
        this.footer = cfg.get("split.footer");
        this.splittable = cfg.regex("split.splittablePattern");
        this.contextPattern = carryContext ? cfg.regex("split.contextPattern") : null;
        this.sourceSize = inputFile.length();
        this.renameFrom = cfg.get("split.renameFrom").trim();
        this.renameTo = cfg.get("split.renameTo").trim();
        if (!renameFrom.isEmpty() && renameTo.isEmpty()) {
            throw new ConfigException("split.renameFrom is set but split.renameTo is empty - "
                    + "say what to rename it to");
        }
    }

    /**
     * Literal (non-regex) find/replace applied to every statement before it is
     * written, e.g. to point a script generated for one database at a
     * differently-named one. SSMS-generated scripts bake the database name
     * into {@code CREATE DATABASE}/{@code USE}/{@code ALTER DATABASE}
     * statements (and into the physical file paths inside {@code CREATE
     * DATABASE}) - there is no config key on the SQL Server side that
     * redirects those, so NanoSplit rewrites the text itself. Disabled (text
     * returned unchanged) when {@code split.renameFrom} is blank.
     */
    private String applyRename(String text) {
        if (renameFrom.isEmpty() || text.indexOf(renameFrom) < 0) {
            return text;
        }
        return text.replace(renameFrom, renameTo);
    }

    private static String resolveEol(String setting) throws ConfigException {
        String v = setting == null ? "same" : setting.trim().toLowerCase();
        if (v.equals("crlf") || v.equals("windows") || v.equals("\r\n")) {
            return "\r\n";
        }
        if (v.equals("lf") || v.equals("unix") || v.equals("\n")) {
            return "\n";
        }
        if (v.equals("same")) {
            return "same";
        }
        throw new ConfigException("output.eol must be crlf, lf or same (got '" + setting + "')");
    }

    public String partName(int number) {
        StringBuilder n = new StringBuilder(Integer.toString(number));
        while (n.length() < digits) {
            n.insert(0, '0');
        }
        return prefix + n + ".sql";
    }

    /** Clears out any part_*.sql left from a previous run. Refuses unless {@code force}. */
    public List<String> prepareOutputDir(boolean force) throws IOException, ConfigException {
        Files.createDirectories(outputDir.toPath());
        List<String> stale = new ArrayList<String>();
        File[] existing = outputDir.listFiles();
        if (existing != null) {
            for (File f : existing) {
                if (f.getName().startsWith(prefix) && f.getName().endsWith(".sql")) {
                    stale.add(f.getName());
                }
            }
        }
        if (!stale.isEmpty() && !force) {
            throw new ConfigException(outputDir + " already holds " + stale.size()
                    + " part file(s). Re-run with --force to replace them.");
        }
        for (String name : stale) {
            Files.deleteIfExists(new File(outputDir, name).toPath());
        }
        return stale;
    }

    public interface ProgressListener {
        void onProgress(SplitProgress progress);
    }

    public Index run(ProgressListener listener) throws IOException, ConfigException {
        ContextTracker context = new ContextTracker(contextPattern);
        Map<String, String> tableMemo = TableNames.newMemo();
        List<PartEntry> parts = new ArrayList<PartEntry>();

        long startedNanos = System.nanoTime();
        // [0]=statements written, [1]=inserts, [2]=batch separators, [3]=units seen (for progress cadence only)
        long[] totals = new long[]{0, 0, 0, 0};
        Map<String, Long> totalTables = new java.util.LinkedHashMap<String, Long>();

        SqlSources.Opened opened = SqlSources.open(inputFile.toPath(), encoding);
        State state = new State();

        try {
            SqlScanner scanner = new SqlScanner(opened.reader, splittable, batchSeparator);
            long lastReportNanos = startedNanos;

            for (SqlUnit unit : scanner) {
                totals[3]++;

                if (unit.kind == SqlUnit.Kind.BATCH_SEPARATOR) {
                    totals[2]++;
                    if (state.writer != null && state.sinceGo > 0) {
                        state.writer.writeLine(batchSeparator);
                        state.sinceGo = 0;
                    }
                    continue;
                }

                if (shouldCut(state, opened.counter.count())) {
                    closePart(state, context, parts, totalTables);
                }
                if (state.writer == null) {
                    openPart(state, context, unit.line);
                }

                String rawText = applyRename(unit.text);
                String text = normaliseEol(rawText);
                PartWriter.StatementPosition position = state.writer.writeStatement(text);

                state.stmtsInPart++;
                state.sinceGo++;
                totals[0]++;
                state.entry.sourceLineEnd = unit.line + Math.max(0, unit.lineCount - 1);

                StatementRef ref = new StatementRef();
                ref.offset = position.offset;
                ref.bytes = position.bytes;
                ref.sourceLine = unit.line;
                ref.preview = previewOf(text);
                ref.insert = unit.splittable;
                if (state.entry.firstStatement == null) {
                    state.entry.firstStatement = ref;
                }
                state.entry.lastStatement = ref;

                if (unit.splittable) {
                    totals[1]++;
                    state.entry.inserts++;
                    String table = TableNames.of(rawText, tableMemo);
                    if (table != null) {
                        state.entry.tables.merge(table, 1, Integer::sum);
                        totalTables.merge(table, 1L, Long::sum);
                    }
                } else if (carryContext) {
                    context.observe(rawText);
                }

                if (goEvery > 0 && state.sinceGo >= goEvery && !batchSeparator.isEmpty()) {
                    state.writer.writeLine(batchSeparator);
                    state.sinceGo = 0;
                }

                if (listener != null && totals[3] % 20000 == 0) {
                    long now = System.nanoTime();
                    if (now - lastReportNanos >= 500_000_000L) {
                        lastReportNanos = now;
                        listener.onProgress(new SplitProgress(
                                Math.min(opened.counter.count(), sourceSize), sourceSize,
                                totals[0], parts.size() + (state.writer != null ? 1 : 0),
                                (now - startedNanos) / 1e9));
                    }
                }
            }

            closePart(state, context, parts, totalTables);

            IndexData data = buildIndexData(parts, totals, totalTables, scanner.lineNo(),
                    scanner.charOffset(), (System.nanoTime() - startedNanos) / 1e9);
            File indexPath = new File(outputDir, cfg.get("index.file"));
            Index index = new Index(data, indexPath);
            index.save();
            if (cfg.bool("output.indexSql")) {
                writeIndexSql(parts);
            }
            return index;
        } finally {
            opened.reader.close();
        }
    }

    // -- part lifecycle ----------------------------------------------------

    /** Mutable state for the part currently being written; avoids a dozen locals. */
    private static final class State {
        PartWriter writer;
        PartEntry entry;
        int partNumber;
        int stmtsInPart;
        int sinceGo;
    }

    private boolean shouldCut(State s, long bytesReadFromSource) {
        if (s.writer == null || s.stmtsInPart == 0) {
            return false;
        }
        if (fixedParts > 0) {
            long target = (long) s.partNumber * sourceSize / fixedParts;
            return bytesReadFromSource >= target && s.partNumber < fixedParts;
        }
        if (maxStatements > 0 && s.stmtsInPart >= maxStatements) {
            return true;
        }
        if (maxBytes > 0 && s.writer.pos() >= maxBytes) {
            return true;
        }
        return false;
    }

    private void openPart(State s, ContextTracker context, int firstLine) throws IOException, ConfigException {
        s.partNumber++;
        s.stmtsInPart = 0;
        s.sinceGo = 0;
        String name = partName(s.partNumber);
        s.writer = new PartWriter(new File(outputDir, name).toPath(), outEncoding, outBom, genEol, hashContent);

        List<String> replay = carryContext ? context.replay() : java.util.Collections.<String>emptyList();
        PartEntry entry = new PartEntry();
        entry.n = s.partNumber;
        entry.file = name;
        entry.context = replay;
        entry.sourceLineStart = firstLine;
        entry.sourceLineEnd = firstLine;
        s.entry = entry;

        s.writer.writeLine("-- NanoSplit " + Version.STRING + " - part " + s.partNumber);
        s.writer.writeLine("-- source: " + inputFile.getName() + " (line " + firstLine + " and on)");
        s.writer.writeLine("-- generated: " + Index.nowIso());
        s.writer.writeLine("-- This part replays the session context below so it can run alone.");
        s.writer.writeLine();
        for (String statement : replay) {
            s.writer.writeLine(statement);
            if (!batchSeparator.isEmpty()) {
                s.writer.writeLine(batchSeparator);
            }
        }
        if (!header.isEmpty()) {
            s.writer.writeLine(header);
            if (!batchSeparator.isEmpty()) {
                s.writer.writeLine(batchSeparator);
            }
        }
        if (!replay.isEmpty() || !header.isEmpty()) {
            s.writer.writeLine();
        }
    }

    private void closePart(State s, ContextTracker context, List<PartEntry> parts, Map<String, Long> totalTables)
            throws IOException {
        if (s.writer == null) {
            return;
        }
        if (s.sinceGo > 0 && !batchSeparator.isEmpty()) {
            s.writer.writeLine(batchSeparator);
        }
        List<String> closers = carryContext ? context.closers() : java.util.Collections.<String>emptyList();
        if (!closers.isEmpty()) {
            s.writer.writeLine();
            s.writer.writeLine("-- NanoSplit: close the context this part opened");
            for (String statement : closers) {
                s.writer.writeLine(statement);
                if (!batchSeparator.isEmpty()) {
                    s.writer.writeLine(batchSeparator);
                }
            }
        }
        if (!footer.isEmpty()) {
            s.writer.writeLine(footer);
            if (!batchSeparator.isEmpty()) {
                s.writer.writeLine(batchSeparator);
            }
        }
        PartWriter.CloseResult info = s.writer.close();
        s.entry.bytes = info.bytes;
        s.entry.sha256 = info.sha256;
        s.entry.lines = s.writer.lines();
        s.entry.closers = closers;
        s.entry.statements = s.stmtsInPart;
        s.entry.pureDml = s.stmtsInPart > 0 && s.stmtsInPart == s.entry.inserts;
        parts.add(s.entry);
        s.writer = null;
        s.entry = null;
    }

    private String normaliseEol(String text) {
        if (eolSetting.equals("same")) {
            return text;
        }
        return text.replace("\r\n", "\n").replace("\r", "\n").replace("\n", eolSetting);
    }

    private static String previewOf(String text) {
        String flat = text.replaceAll("\\s+", " ").trim();
        return flat.length() <= PREVIEW_CHARS ? flat : flat.substring(0, PREVIEW_CHARS - 1) + "…";
    }

    private IndexData buildIndexData(List<PartEntry> parts, long[] totals, Map<String, Long> totalTables,
                                      int sourceLines, long sourceChars, double seconds) throws ConfigException {
        IndexData data = new IndexData();
        data.version = Version.STRING;
        data.createdAt = Index.nowIso();

        data.source.path = inputFile.getPath();
        data.source.name = inputFile.getName();
        data.source.size = sourceSize;
        data.source.encoding = encoding.charset;
        data.source.bytesPerChar = Encodings.bytesPerChar(encoding.charset);
        data.source.characters = sourceChars;
        data.source.lines = sourceLines;

        data.output.dir = outputDir.getPath();
        data.output.encoding = outEncoding;
        data.output.eol = cfg.get("output.eol");
        data.output.prefix = prefix;

        data.options.parts = fixedParts;
        data.options.maxStatementsPerPart = maxStatements;
        data.options.maxBytesPerPart = maxBytes;
        data.options.batchSeparator = batchSeparator;
        data.options.emitBatchSeparatorEvery = goEvery;
        data.options.splittablePattern = cfg.get("split.splittablePattern");
        data.options.contextPattern = carryContext ? cfg.get("split.contextPattern") : "";
        data.options.carryContext = carryContext;

        data.totals.parts = parts.size();
        data.totals.statements = totals[0];
        data.totals.inserts = totals[1];
        data.totals.batchSeparators = totals[2];
        long totalBytes = 0;
        for (PartEntry p : parts) {
            totalBytes += p.bytes;
        }
        data.totals.bytes = totalBytes;
        data.totals.seconds = Math.round(seconds * 100.0) / 100.0;
        List<Map.Entry<String, Long>> sortedTables = new ArrayList<Map.Entry<String, Long>>(totalTables.entrySet());
        sortedTables.sort((a, b) -> Long.compare(b.getValue(), a.getValue()));
        for (Map.Entry<String, Long> e : sortedTables) {
            data.totals.tables.put(e.getKey(), e.getValue());
        }

        data.parts = parts;
        return data;
    }

    private void writeIndexSql(List<PartEntry> parts) throws IOException, ConfigException {
        File path = new File(outputDir, "index.sql");
        try (java.io.Writer w = Files.newBufferedWriter(path.toPath(), java.nio.charset.StandardCharsets.UTF_8)) {
            String eol = genEol;
            w.write("-- NanoSplit " + Version.STRING + " - driver script for " + parts.size() + " part(s)" + eol);
            w.write("-- Generated " + Index.nowIso() + " from " + inputFile.getName() + eol);
            w.write("--" + eol);
            w.write("-- Run every part, stopping at the first error:" + eol);
            String dbClause = cfg.get("db.name").isEmpty() ? "" : ("-d \"" + cfg.get("db.name") + "\" ");
            w.write("--   sqlcmd -S \"" + cfg.get("db.server") + "\" " + dbClause + "-i \"" + path + "\" -b" + eol);
            w.write("--" + eol);
            w.write("-- 'nanosplit run' does the same thing but records progress and can resume." + eol);
            w.write(":on error exit" + eol);
            String dirWithSep = outputDir.getPath().endsWith(File.separator) ? outputDir.getPath() : outputDir.getPath() + File.separator;
            w.write(":setvar NanoSplitDir \"" + dirWithSep + "\"" + eol);
            w.write(eol);
            for (PartEntry part : parts) {
                w.write("PRINT '>> NanoSplit part " + part.n + "/" + parts.size() + ": " + part.file
                        + " (" + part.statements + " statements)';" + eol);
                w.write(":r $(NanoSplitDir)" + part.file + eol);
            }
            w.write(eol);
            w.write("PRINT 'NanoSplit: all " + parts.size() + " part(s) completed.';" + eol);
        }
    }
}
