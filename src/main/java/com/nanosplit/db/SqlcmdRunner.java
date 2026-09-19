package com.nanosplit.db;

import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import com.nanosplit.config.AppConfig;
import com.nanosplit.config.ConfigException;
import com.nanosplit.index.PartEntry;
import com.nanosplit.index.StatementRef;
import com.nanosplit.util.Log;

/**
 * Executes one part file by shelling out to {@code sqlcmd}, instead of over
 * JDBC. See {@link SqlcmdSupport} for why this path exists.
 *
 * <p>Resume model here is coarser than the JDBC path's per-statement one, and
 * deliberately so: a {@linkplain PartEntry#pureDml pure-DML} part is wrapped
 * in one explicit transaction with {@code SET XACT_ABORT ON}, so a failure
 * anywhere in it rolls back the whole part - nothing from it is left half
 * committed. That makes a blind retry of the whole part always safe (no
 * duplicate-key errors from re-inserting rows that already landed), at the
 * cost of not knowing exactly which statement failed the way the JDBC path
 * does. A part that also carries DDL/DATABASE statements is run as-is
 * (SQL Server refuses those inside a user transaction) and is not safe to
 * blindly retry after a partial failure - same caveat the JDBC path has for
 * such statements.
 */
public final class SqlcmdRunner {

    private static final Pattern LINE_HINT = Pattern.compile("Line\\s+(\\d+)", Pattern.CASE_INSENSITIVE);

    private final AppConfig cfg;
    private final Log log;

    public SqlcmdRunner(AppConfig cfg, Log log) {
        this.cfg = cfg;
        this.log = log;
    }

    public boolean isAvailable() {
        return SqlcmdSupport.isAvailable(cfg);
    }

    public RunResult run(PartEntry part, File partFile) throws IOException, ConfigException {
        long startedNanos = System.nanoTime();
        File wrapper = part.pureDml ? writeWrapper(part, partFile) : partFile;
        try {
            List<String> baseArgs = SqlcmdSupport.baseArgs(cfg);
            // No process-level timeout here - db.commandTimeoutSeconds is already passed
            // to sqlcmd itself (as -t, a per-query timeout) inside baseArgs. A whole part
            // can legitimately take a long time to run.
            SqlcmdSupport.ProcessResult result = SqlcmdSupport.runScript(baseArgs, wrapper, 0);

            RunResult out = new RunResult();
            out.seconds = (System.nanoTime() - startedNanos) / 1e9;
            if (result.exitCode == 0) {
                out.success = true;
                out.statementsDone = part.statements;
                out.rowsAffected = -1; // not tracked in sqlcmd mode (SET NOCOUNT ON is used for speed)
                return out;
            }

            out.success = false;
            out.statementsDone = 0; // safe: pure-DML parts are transaction-wrapped, all-or-nothing
            out.error = summarize(result.output);
            StatementRef ref = new StatementRef();
            Matcher m = LINE_HINT.matcher(result.output);
            ref.sourceLine = m.find() ? Integer.parseInt(m.group(1)) : -1;
            ref.preview = "(sqlcmd mode: exact statement not tracked - see part file"
                    + (ref.sourceLine > 0 ? " near its line " + ref.sourceLine : "") + ")";
            ref.insert = false;
            out.failedAt = ref;
            if (log != null) {
                log.error("sqlcmd exited " + result.exitCode + " on part " + part.n + ": " + out.error);
            }
            return out;
        } finally {
            if (wrapper != partFile) {
                wrapper.delete();
            }
        }
    }

    /** {@code BEGIN TRAN; SET XACT_ABORT ON; :r partFile; COMMIT TRAN;} in a scratch file. */
    private File writeWrapper(PartEntry part, File partFile) throws IOException {
        File wrapper = File.createTempFile("nanosplit-part" + part.n + "-", ".sql");
        StringBuilder sb = new StringBuilder();
        sb.append("SET NOCOUNT ON;\r\n");
        sb.append("SET XACT_ABORT ON;\r\n"); // any error aborts AND rolls back the transaction outright
        sb.append("BEGIN TRANSACTION;\r\n");
        sb.append(":r \"").append(partFile.getAbsolutePath()).append("\"\r\n");
        sb.append("COMMIT TRANSACTION;\r\n");
        Files.write(wrapper.toPath(), sb.toString().getBytes(StandardCharsets.UTF_8));
        return wrapper;
    }

    private static String summarize(String output) {
        String trimmed = output.trim();
        if (trimmed.isEmpty()) {
            return "sqlcmd exited with an error but produced no output";
        }
        // Keep it terminal-friendly: the last handful of lines usually has the actual error.
        String[] lines = trimmed.split("\r?\n");
        int from = Math.max(0, lines.length - 12);
        StringBuilder sb = new StringBuilder();
        for (int i = from; i < lines.length; i++) {
            if (sb.length() > 0) {
                sb.append(" | ");
            }
            sb.append(lines[i].trim());
        }
        return sb.toString();
    }
}
