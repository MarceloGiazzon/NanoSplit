package com.nanosplit.cli;

import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.sql.Connection;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.List;
import java.util.concurrent.Callable;

import com.nanosplit.config.AppConfig;
import com.nanosplit.db.ConnectionFactory;
import com.nanosplit.db.PartAccess;
import com.nanosplit.db.SqlcmdSupport;
import com.nanosplit.index.Index;
import com.nanosplit.index.PartEntry;
import com.nanosplit.index.StatementRef;

import picocli.CommandLine.Command;
import picocli.CommandLine.Mixin;
import picocli.CommandLine.Option;

/**
 * {@code nanosplit smoke}: for every part, try just its first and last
 * statement (each in its own transaction, rolled back by default) so a schema
 * mismatch or bad connection surfaces in seconds instead of after loading
 * millions of rows.
 *
 * <p>Uses the same {@code db.driver} choice as {@code run} (JDBC or {@code
 * sqlcmd} - see {@link com.nanosplit.db.SqlcmdRunner}).
 */
@Command(name = "smoke", description = "Try the first and last statement of every part, without loading the rest")
public final class SmokeCommand implements Callable<Integer> {

    @Mixin
    CommonOptions common;

    @Option(names = "--from", description = "First part number to test")
    Integer from;

    @Option(names = "--to", description = "Last part number to test")
    Integer to;

    @Override
    public Integer call() throws Exception {
        AppConfig cfg = AppConfig.load(common.config, common.overrides);
        File outputDir = cfg.path("output.dir", true);
        Index index = Index.load(new File(outputDir, cfg.get("index.file")));
        boolean rollback = cfg.bool("smoke.rollback");
        boolean stopOnError = cfg.bool("smoke.stopOnError");

        int first = from != null ? from : 0;
        int last = to != null ? to : 0;
        List<PartEntry> parts = index.select(first, last);
        if (parts.isEmpty()) {
            System.out.println("No parts match the requested range.");
            return 0;
        }

        String driverMode = cfg.get("db.driver").trim().toLowerCase();
        boolean useSqlcmd = driverMode.equals("sqlcmd") || (driverMode.equals("auto") && SqlcmdSupport.isAvailable(cfg));

        System.out.println((rollback ? "Rolling back" : "Committing") + " every test statement (smoke.rollback=" + rollback + ")");
        int failures;
        if (useSqlcmd) {
            System.out.println("Connecting via sqlcmd to " + cfg.get("db.server")
                    + (cfg.get("db.name").isEmpty() ? "" : " / " + cfg.get("db.name")));
            System.out.println();
            failures = runSqlcmd(cfg, index, parts, rollback, stopOnError);
        } else {
            ConnectionFactory factory = new ConnectionFactory(cfg);
            System.out.println("Connecting to " + factory.describeUrl());
            System.out.println();
            failures = runJdbc(factory, index, parts, rollback, stopOnError);
        }

        System.out.println();
        if (failures == 0) {
            System.out.println("All " + parts.size() + " part(s) look good. Run 'nanosplit run' to execute them all.");
            return 0;
        }
        System.out.println(failures + " of " + parts.size() + " part(s) failed the smoke test.");
        return 1;
    }

    // -- JDBC path -----------------------------------------------------------

    private int runJdbc(ConnectionFactory factory, Index index, List<PartEntry> parts, boolean rollback,
                         boolean stopOnError) throws Exception {
        int failures = 0;
        try (Connection conn = factory.connect()) {
            conn.setAutoCommit(false);
            for (PartEntry part : parts) {
                String outcome = testPartJdbc(conn, index, part, rollback);
                System.out.println(String.format("part %-5d %-8s %s", part.n, outcome, part.file));
                if (!"OK".equals(outcome)) {
                    failures++;
                    if (stopOnError) {
                        break;
                    }
                }
            }
        }
        return failures;
    }

    private String testPartJdbc(Connection conn, Index index, PartEntry part, boolean rollback) {
        File partFile = index.partPath(part);
        String charset = index.data.output.encoding;
        try {
            for (String statement : part.context) {
                execute(conn, statement);
            }
            if (part.firstStatement != null) {
                execute(conn, PartAccess.readStatement(partFile, part.firstStatement, charset));
            }
            if (part.lastStatement != null && !sameStatement(part.firstStatement, part.lastStatement)) {
                execute(conn, PartAccess.readStatement(partFile, part.lastStatement, charset));
            }
            for (String statement : part.closers) {
                execute(conn, statement);
            }
            return "OK";
        } catch (Exception e) {
            String message = e.getMessage();
            return "FAIL: " + (message != null ? message.replace("\n", " ").replace("\r", "") : e.toString());
        } finally {
            try {
                if (rollback) {
                    conn.rollback();
                } else {
                    conn.commit();
                }
            } catch (SQLException ignored) {
                // best-effort cleanup only
            }
        }
    }

    private static void execute(Connection conn, String sql) throws SQLException {
        try (Statement stmt = conn.createStatement()) {
            stmt.execute(sql);
        }
    }

    // -- sqlcmd path -----------------------------------------------------------

    private int runSqlcmd(AppConfig cfg, Index index, List<PartEntry> parts, boolean rollback, boolean stopOnError)
            throws Exception {
        int failures = 0;
        List<String> baseArgs = SqlcmdSupport.baseArgs(cfg);
        for (PartEntry part : parts) {
            String outcome = testPartSqlcmd(baseArgs, index, part, rollback);
            System.out.println(String.format("part %-5d %-8s %s", part.n, outcome, part.file));
            if (!"OK".equals(outcome)) {
                failures++;
                if (stopOnError) {
                    break;
                }
            }
        }
        return failures;
    }

    private String testPartSqlcmd(List<String> baseArgs, Index index, PartEntry part, boolean rollback) throws Exception {
        File partFile = index.partPath(part);
        String charset = index.data.output.encoding;
        File script = File.createTempFile("nanosplit-smoke-part" + part.n + "-", ".sql");
        try {
            StringBuilder sb = new StringBuilder();
            sb.append("SET NOCOUNT ON;\r\n");
            for (String statement : part.context) {
                sb.append(statement).append("\r\nGO\r\n");
            }
            sb.append("BEGIN TRANSACTION;\r\n");
            if (part.firstStatement != null) {
                sb.append(PartAccess.readStatement(partFile, part.firstStatement, charset)).append("\r\n");
            }
            if (part.lastStatement != null && !sameStatement(part.firstStatement, part.lastStatement)) {
                sb.append(PartAccess.readStatement(partFile, part.lastStatement, charset)).append("\r\n");
            }
            sb.append(rollback ? "ROLLBACK TRANSACTION;\r\n" : "COMMIT TRANSACTION;\r\n");
            sb.append("GO\r\n");
            for (String statement : part.closers) {
                sb.append(statement).append("\r\nGO\r\n");
            }
            Files.write(script.toPath(), sb.toString().getBytes(StandardCharsets.UTF_8));

            SqlcmdSupport.ProcessResult result = SqlcmdSupport.runScript(baseArgs, script, 60);
            if (result.exitCode == 0) {
                return "OK";
            }
            String tail = result.output.trim().replace("\r\n", " | ").replace("\n", " | ");
            return "FAIL: " + (tail.isEmpty() ? "sqlcmd exited " + result.exitCode : tail);
        } finally {
            script.delete();
        }
    }

    private static boolean sameStatement(StatementRef a, StatementRef b) {
        return a != null && b != null && a.offset == b.offset;
    }
}
