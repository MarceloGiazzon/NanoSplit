package com.nanosplit.cli;

import java.io.File;
import java.sql.Connection;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.Callable;

import com.nanosplit.config.AppConfig;
import com.nanosplit.db.ConnectionFactory;
import com.nanosplit.db.RunResult;
import com.nanosplit.db.SqlcmdRunner;
import com.nanosplit.db.SqlcmdSupport;
import com.nanosplit.db.StatementRunner;
import com.nanosplit.index.Index;
import com.nanosplit.index.PartEntry;
import com.nanosplit.index.PartState;
import com.nanosplit.index.State;
import com.nanosplit.util.Log;
import com.nanosplit.util.Sizes;

import picocli.CommandLine.Command;
import picocli.CommandLine.Mixin;
import picocli.CommandLine.Option;

/**
 * {@code nanosplit run}: executes every part against the configured database,
 * in order, recording progress so a later run picks up exactly where things
 * stopped - not from part 1, not by skipping a whole part.
 *
 * <p>Two execution backends, chosen by {@code db.driver} (default {@code
 * auto}): JDBC (per-statement resume, needs TCP/IP reachable) or {@code
 * sqlcmd} (per-part resume, works over Named Pipes/Shared Memory - see
 * {@link SqlcmdRunner}). {@code auto} picks {@code sqlcmd} whenever it
 * resolves on {@code PATH}.
 */
@Command(name = "run", description = "Execute the split parts against the database, tracking progress for resume")
public final class RunCommand implements Callable<Integer> {

    @Mixin
    CommonOptions common;

    @Option(names = "--from", description = "First part number to run (default: from config, or 1)")
    Integer from;

    @Option(names = "--to", description = "Last part number to run (default: from config, or the last part)")
    Integer to;

    @Option(names = "--rerun-done", description = "Re-run parts already marked done, instead of skipping them")
    boolean rerunDone;

    @Option(names = "--dry-run", description = "Print which parts would run, without touching the database")
    boolean dryRun;

    @Override
    public Integer call() throws Exception {
        AppConfig cfg = AppConfig.load(common.config, common.overrides);
        File outputDir = cfg.path("output.dir", true);
        File indexPath = new File(outputDir, cfg.get("index.file"));
        Index index = Index.load(indexPath);
        File statePath = new File(outputDir, cfg.get("state.file"));
        State state = State.loadOrNew(statePath, index);
        Log log = Log.create(cfg.get("log.level"), cfg.path("log.dir", false).toPath(), "run.log");

        int first = from != null ? from : cfg.intVal("run.from");
        int last = to != null ? to : cfg.intVal("run.to");
        List<PartEntry> parts = index.select(first, last);
        if (parts.isEmpty()) {
            System.out.println("No parts match the requested range.");
            return 0;
        }

        int retries = Math.max(1, cfg.intVal("run.retries"));
        int retryDelay = cfg.intVal("run.retryDelaySeconds");
        boolean stopOnError = cfg.bool("run.stopOnError");

        if (dryRun) {
            for (PartEntry p : parts) {
                String status = state.status(p.n);
                System.out.println(String.format(Locale.ROOT, "  part %-5d %-8s %s (%,d statements)",
                        p.n, status, p.file, p.statements));
            }
            return 0;
        }

        String driverMode = cfg.get("db.driver").trim().toLowerCase();
        boolean useSqlcmd = driverMode.equals("sqlcmd") || (driverMode.equals("auto") && SqlcmdSupport.isAvailable(cfg));
        if (driverMode.equals("auto")) {
            System.out.println(useSqlcmd
                    ? "db.driver=auto: sqlcmd found on PATH, using it (works locally without TCP/IP)."
                    : "db.driver=auto: sqlcmd not found on PATH, using the JDBC driver.");
        }

        Connection conn = null;
        ConnectionFactory factory = null;
        SqlcmdRunner sqlcmdRunner = null;
        if (useSqlcmd) {
            sqlcmdRunner = new SqlcmdRunner(cfg, log);
            System.out.println("Connecting via sqlcmd to " + cfg.get("db.server")
                    + (cfg.get("db.name").isEmpty() ? "" : " / " + cfg.get("db.name")));
        } else {
            factory = new ConnectionFactory(cfg);
            System.out.println("Connecting to " + factory.describeUrl());
            conn = factory.connect();
        }

        try {
            int failedCount = 0;
            for (PartEntry part : parts) {
                if (state.isDone(part.n) && !rerunDone) {
                    System.out.println("part " + part.n + "/" + parts.size() + ": already done, skipping (" + part.file + ")");
                    continue;
                }

                // Per-statement resume (JDBC) only; sqlcmd mode is all-or-nothing per
                // part (see SqlcmdRunner), so it always restarts a failed part from 0.
                int resumeFrom = 0;
                if (!useSqlcmd && !rerunDone && PartState.Status.FAILED.equals(state.status(part.n))) {
                    resumeFrom = (int) state.record(part.n).statementsDone;
                }

                System.out.println("part " + part.n + "/" + parts.size() + ": " + part.file
                        + (resumeFrom > 0 ? " (resuming after statement " + resumeFrom + ")" : ""));
                state.markRunning(part.n, resumeFrom);
                state.save();

                File partFile = index.partPath(part);
                long cursor = resumeFrom;
                RunResult result = null;
                for (int attempt = 1; attempt <= retries; attempt++) {
                    if (attempt > 1) {
                        System.out.println("  retrying (attempt " + attempt + "/" + retries + ") in " + retryDelay + "s ...");
                        Thread.sleep(retryDelay * 1000L);
                        if (!useSqlcmd && conn.isClosed()) {
                            conn = factory.connect();
                        }
                    }
                    if (useSqlcmd) {
                        result = sqlcmdRunner.run(part, partFile);
                        cursor = result.statementsDone;
                    } else {
                        result = runJdbc(cfg, conn, log, state, part, partFile, index, cursor, stopOnError);
                        cursor = result.statementsDone;
                    }
                    if (result.success) {
                        break;
                    }
                }
                System.out.println();

                if (result.success) {
                    state.markDone(part.n, cursor, result.rowsAffected, result.seconds);
                    state.save();
                    String rows = result.rowsAffected >= 0
                            ? String.format(Locale.ROOT, "%,d", result.rowsAffected) + " row(s) affected"
                            : "rows affected not tracked in sqlcmd mode";
                    System.out.println("  done in " + Sizes.formatSeconds(result.seconds) + ": " + rows);
                } else {
                    state.markFailed(part.n, result.error, cursor, result.failedAt, result.seconds);
                    state.save();
                    failedCount++;
                    log.error("part " + part.n + " failed: " + result.error);
                    System.out.println("  FAILED: " + result.error);
                    if (result.failedAt != null) {
                        String where = result.failedAt.sourceLine > 0
                                ? "at source line " + result.failedAt.sourceLine + ": " + result.failedAt.preview
                                : result.failedAt.preview;
                        System.out.println("    " + where);
                    }
                    if (stopOnError) {
                        System.out.println();
                        System.out.println("Stopping (run.stopOnError=true). Fix the issue, then run "
                                + "'nanosplit run' again - it resumes automatically from where this part stopped.");
                        return 1;
                    }
                }
            }
            System.out.println();
            if (failedCount == 0) {
                System.out.println("All " + parts.size() + " part(s) completed successfully.");
                return 0;
            }
            System.out.println(failedCount + " of " + parts.size() + " part(s) failed. Run 'nanosplit status' for details.");
            return 1;
        } finally {
            log.close();
            if (conn != null && !conn.isClosed()) {
                conn.close();
            }
        }
    }

    private RunResult runJdbc(AppConfig cfg, Connection conn, Log log, State state, PartEntry part, File partFile,
                               Index index, long resumeFrom, boolean stopOnError) throws Exception {
        int commitEvery = cfg.intVal("run.commitEvery");
        int commandTimeout = cfg.intVal("db.commandTimeoutSeconds");
        StatementRunner runner = new StatementRunner(conn, commitEvery, commandTimeout, stopOnError, log);
        final int partNumber = part.n;
        final long base = resumeFrom;
        RunResult result = runner.run(partFile, index.data.options, (int) base, (statementsDone, rows) -> {
            state.progress(partNumber, base + statementsDone, rows);
            try {
                state.save();
            } catch (Exception e) {
                log.warn("could not save state: " + e.getMessage());
            }
            System.out.print(String.format(Locale.ROOT, "\r  %,d / %,d statements  |  %,d rows affected   ",
                    base + statementsDone, part.statements, rows));
            System.out.flush();
        });
        result.statementsDone = base + result.statementsDone;
        return result;
    }
}
