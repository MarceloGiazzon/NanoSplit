package com.nanosplit.db;

import java.io.File;
import java.io.IOException;
import java.sql.BatchUpdateException;
import java.sql.Connection;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Pattern;

import com.nanosplit.index.IndexData;
import com.nanosplit.index.StatementRef;
import com.nanosplit.sql.Encodings;
import com.nanosplit.sql.SqlScanner;
import com.nanosplit.sql.SqlSources;
import com.nanosplit.sql.SqlUnit;
import com.nanosplit.util.Log;

/**
 * Executes one part file against a connection.
 *
 * <p>Statements are batched with JDBC's {@code addBatch}/{@code executeBatch}
 * for throughput, committing every {@code run.commitEvery} statements. If a
 * batch fails, it is rolled back and replayed one statement at a time so the
 * exact failing statement (and everything that succeeded before it) is known
 * precisely - that fine-grained position is what makes a resume land exactly
 * where things stopped instead of re-running or skipping a whole batch.
 */
public final class StatementRunner {

    public interface ProgressListener {
        void onProgress(long statementsDone, long rowsAffected);
    }

    private final Connection conn;
    private final int commitEvery;
    private final int commandTimeoutSeconds;
    private final boolean stopOnError;
    private final Log log;

    public StatementRunner(Connection conn, int commitEvery, int commandTimeoutSeconds, boolean stopOnError, Log log) {
        this.conn = conn;
        this.commitEvery = commitEvery > 0 ? commitEvery : Integer.MAX_VALUE;
        this.commandTimeoutSeconds = commandTimeoutSeconds;
        this.stopOnError = stopOnError;
        this.log = log;
    }

    public RunResult run(File partFile, IndexData.Options options, int skipFirstNStatements,
                          ProgressListener listener) throws IOException {
        RunResult result = new RunResult();
        long startedNanos = System.nanoTime();
        Pattern splittable = options.splittablePattern == null || options.splittablePattern.isEmpty()
                ? null : Pattern.compile(options.splittablePattern, Pattern.CASE_INSENSITIVE);

        Encodings.Detected enc = Encodings.detect(partFile, "auto");
        SqlSources.Opened opened = SqlSources.open(partFile.toPath(), enc);
        long statementsDone = 0;
        long rowsAffected = 0;
        int skipped = skipFirstNStatements;

        try {
            boolean previousAutoCommit = conn.getAutoCommit();
            conn.setAutoCommit(false);
            try {
                SqlScanner scanner = new SqlScanner(opened.reader, splittable, options.batchSeparator);
                List<PendingStatement> batch = new ArrayList<PendingStatement>(commitEvery);
                int lineForOffset = 0;

                for (SqlUnit unit : scanner) {
                    if (unit.kind == SqlUnit.Kind.BATCH_SEPARATOR) {
                        continue; // GO is a client directive only; SQL Server has no such statement
                    }
                    if (skipped > 0) {
                        skipped--;
                        continue;
                    }
                    StatementRef ref = new StatementRef();
                    ref.sourceLine = unit.line;
                    ref.preview = flatten(unit.text);
                    ref.insert = unit.splittable;
                    batch.add(new PendingStatement(unit.text, ref));

                    if (batch.size() >= commitEvery) {
                        BatchOutcome outcome = runBatch(batch);
                        statementsDone += outcome.statementsDone;
                        rowsAffected += outcome.rowsAffected;
                        if (listener != null) {
                            listener.onProgress(statementsDone, rowsAffected);
                        }
                        if (outcome.failure != null) {
                            return failure(result, outcome, statementsDone, startedNanos);
                        }
                        batch.clear();
                    }
                }
                if (!batch.isEmpty()) {
                    BatchOutcome outcome = runBatch(batch);
                    statementsDone += outcome.statementsDone;
                    rowsAffected += outcome.rowsAffected;
                    if (listener != null) {
                        listener.onProgress(statementsDone, rowsAffected);
                    }
                    if (outcome.failure != null) {
                        return failure(result, outcome, statementsDone, startedNanos);
                    }
                }
            } finally {
                try {
                    conn.setAutoCommit(previousAutoCommit);
                } catch (SQLException ignored) {
                    // best-effort restore only
                }
            }
        } catch (SQLException e) {
            result.success = false;
            result.error = describe(e);
            result.statementsDone = statementsDone;
            result.rowsAffected = rowsAffected;
            result.seconds = elapsed(startedNanos);
            return result;
        } finally {
            opened.reader.close();
        }

        result.success = true;
        result.statementsDone = statementsDone;
        result.rowsAffected = rowsAffected;
        result.seconds = elapsed(startedNanos);
        return result;
    }

    private RunResult failure(RunResult result, BatchOutcome outcome, long statementsDone, long startedNanos) {
        result.success = false;
        result.statementsDone = statementsDone;
        result.rowsAffected = 0;
        result.error = outcome.failure;
        result.failedAt = outcome.failedRef;
        result.seconds = elapsed(startedNanos);
        return result;
    }

    private static double elapsed(long startedNanos) {
        return (System.nanoTime() - startedNanos) / 1e9;
    }

    private static final class PendingStatement {
        final String text;
        final StatementRef ref;

        PendingStatement(String text, StatementRef ref) {
            this.text = text;
            this.ref = ref;
        }
    }

    private static final class BatchOutcome {
        long statementsDone;
        long rowsAffected;
        String failure;
        StatementRef failedRef;
    }

    /** Fast path: send the whole batch together. Falls back to one-by-one on failure. */
    private BatchOutcome runBatch(List<PendingStatement> batch) throws SQLException {
        try (Statement stmt = conn.createStatement()) {
            if (commandTimeoutSeconds > 0) {
                stmt.setQueryTimeout(commandTimeoutSeconds);
            }
            for (PendingStatement p : batch) {
                stmt.addBatch(p.text);
            }
            int[] counts = stmt.executeBatch();
            conn.commit();
            BatchOutcome outcome = new BatchOutcome();
            outcome.statementsDone = batch.size();
            outcome.rowsAffected = sumRows(counts);
            return outcome;
        } catch (SQLException fastPathError) {
            rollbackQuietly();
            return runSerially(batch, fastPathError);
        }
    }

    /** Slow path after a batch fails: replay one statement per commit to pinpoint the failure. */
    private BatchOutcome runSerially(List<PendingStatement> batch, SQLException originalError) throws SQLException {
        BatchOutcome outcome = new BatchOutcome();
        for (PendingStatement p : batch) {
            try (Statement stmt = conn.createStatement()) {
                if (commandTimeoutSeconds > 0) {
                    stmt.setQueryTimeout(commandTimeoutSeconds);
                }
                int rows = stmt.executeUpdate(p.text);
                conn.commit();
                outcome.statementsDone++;
                outcome.rowsAffected += Math.max(rows, 0);
            } catch (SQLException e) {
                rollbackQuietly();
                outcome.failure = describe(e);
                outcome.failedRef = p.ref;
                if (log != null) {
                    log.error("statement failed at source line " + p.ref.sourceLine + ": " + p.ref.preview);
                }
                if (stopOnError) {
                    return outcome;
                }
                if (log != null) {
                    log.warn("run.stopOnError=false: skipping this statement and continuing");
                }
                outcome.statementsDone++; // counted as "processed" so resume does not retry it forever
            }
        }
        return outcome;
    }

    private void rollbackQuietly() {
        try {
            conn.rollback();
        } catch (SQLException ignored) {
            // the connection may already be dead; the caller will surface the real error
        }
    }

    private static long sumRows(int[] counts) {
        long total = 0;
        for (int c : counts) {
            if (c > 0) {
                total += c;
            }
        }
        return total;
    }

    private static String describe(SQLException e) {
        StringBuilder sb = new StringBuilder();
        SQLException cur = e;
        while (cur != null) {
            if (sb.length() > 0) {
                sb.append(" | ");
            }
            sb.append("SQLState=").append(cur.getSQLState())
                    .append(" ErrorCode=").append(cur.getErrorCode())
                    .append(" ").append(cur.getMessage());
            cur = cur.getNextException();
        }
        return sb.toString();
    }

    private static String flatten(String text) {
        String flat = text.replaceAll("\\s+", " ").trim();
        return flat.length() <= 200 ? flat : flat.substring(0, 199) + "…";
    }
}
