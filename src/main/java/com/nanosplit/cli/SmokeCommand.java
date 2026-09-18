package com.nanosplit.cli;

import java.io.File;
import java.sql.Connection;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.List;
import java.util.concurrent.Callable;

import com.nanosplit.config.AppConfig;
import com.nanosplit.db.ConnectionFactory;
import com.nanosplit.db.PartAccess;
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

        ConnectionFactory factory = new ConnectionFactory(cfg);
        System.out.println("Connecting to " + factory.describeUrl());
        System.out.println((rollback ? "Rolling back" : "Committing") + " every test statement (smoke.rollback=" + rollback + ")");
        System.out.println();

        int failures = 0;
        try (Connection conn = factory.connect()) {
            conn.setAutoCommit(false);
            for (PartEntry part : parts) {
                String outcome = testPart(conn, index, part, rollback);
                System.out.println(String.format("part %-5d %-8s %s", part.n, outcome, part.file));
                if (!"OK".equals(outcome)) {
                    failures++;
                    if (stopOnError) {
                        break;
                    }
                }
            }
        }

        System.out.println();
        if (failures == 0) {
            System.out.println("All " + parts.size() + " part(s) look good. Run 'nanosplit run' to execute them all.");
            return 0;
        }
        System.out.println(failures + " of " + parts.size() + " part(s) failed the smoke test.");
        return 1;
    }

    private String testPart(Connection conn, Index index, PartEntry part, boolean rollback) {
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

    private static boolean sameStatement(StatementRef a, StatementRef b) {
        return a != null && b != null && a.offset == b.offset;
    }

    private static void execute(Connection conn, String sql) throws SQLException {
        try (Statement stmt = conn.createStatement()) {
            stmt.execute(sql);
        }
    }
}
