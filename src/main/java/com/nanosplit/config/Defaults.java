package com.nanosplit.config;

import java.util.LinkedHashMap;
import java.util.Map;

/** Every recognised configuration key, with the value used when the file omits it. */
public final class Defaults {

    public static final Map<String, String> MAP = build();

    private Defaults() {
    }

    private static Map<String, String> build() {
        Map<String, String> d = new LinkedHashMap<String, String>();

        // --- Database --------------------------------------------------
        d.put("db.server", "localhost\\SQLEXPRESS");
        d.put("db.name", "");
        d.put("db.user", "");
        d.put("db.password", "");
        d.put("db.integratedSecurity", "true");
        d.put("db.encrypt", "false");
        d.put("db.trustServerCertificate", "true");
        d.put("db.connectTimeoutSeconds", "30");
        d.put("db.commandTimeoutSeconds", "0");
        d.put("db.applicationName", "NanoSplit");
        d.put("db.extraUrlParams", "");
        // auto: use sqlcmd if it resolves on PATH (works locally without TCP -
        // SSMS and sqlcmd use Named Pipes/Shared Memory for a local server,
        // which the Microsoft JDBC driver cannot do), otherwise fall back to
        // the JDBC driver. jdbc/sqlcmd force one or the other.
        d.put("db.driver", "auto");
        d.put("db.sqlcmdPath", "sqlcmd");
        d.put("db.sqlcmdExtraArgs", "");

        // --- Files -------------------------------------------------------
        d.put("input.file", "");
        d.put("input.encoding", "auto");
        d.put("output.dir", "temp/sql_parts");
        d.put("output.encoding", "same");
        d.put("output.prefix", "part_");
        d.put("output.digits", "5");
        // 'same' leaves every byte of a statement untouched. crlf/lf rewrite
        // line endings *including those inside string literals*, which
        // changes the data you insert - only ask for it if you mean it.
        d.put("output.eol", "same");
        d.put("output.hash", "true");
        d.put("output.indexSql", "true");

        // --- Splitting -----------------------------------------------------
        d.put("split.parts", "0");
        d.put("split.maxStatementsPerPart", "50000");
        d.put("split.maxBytesPerPart", "64MB");
        d.put("split.batchSeparator", "GO");
        d.put("split.emitBatchSeparatorEvery", "1000");
        d.put("split.splittablePattern", "^(INSERT|UPDATE|DELETE|MERGE)\\b");
        d.put("split.blockPattern", "");
        d.put("split.carryContext", "true");
        d.put("split.contextPattern",
                "^(USE\\s|SET\\s+(ANSI_NULLS|ANSI_PADDING|ANSI_WARNINGS|QUOTED_IDENTIFIER"
                        + "|NOCOUNT|IDENTITY_INSERT|DATEFORMAT|DATEFIRST|ARITHABORT|XACT_ABORT"
                        + "|CONCAT_NULL_YIELDS_NULL|NUMERIC_ROUNDABORT|LANGUAGE)\\b)");
        d.put("split.header", "");
        d.put("split.footer", "");
        // Literal find/replace applied to every statement, e.g. to point a
        // script generated for one database at a differently-named one -
        // renameFrom="OldDbName" renameTo="NewDbName". Empty renameFrom disables
        // it. See README for why this is needed (the name is baked into the SQL
        // text - CREATE DATABASE/USE/ALTER DATABASE and their file paths - there
        // is no server-side setting that redirects it).
        d.put("split.renameFrom", "");
        d.put("split.renameTo", "");
        // Wrap CREATE DATABASE/TABLE/INDEX in an existence check so re-running a
        // part against a target that already has some of that schema - e.g. a
        // previous run got partway through part 1 before failing - skips what's
        // already there instead of erroring "already exists". This is what makes
        // resuming into an already-partially-created database actually work.
        d.put("split.idempotentDdl", "true");
        // Statements matching this regex are commented out instead of written
        // as executable SQL. Defaults to CREATE USER/ADD MEMBER for a Windows
        // domain group (a "DOMAIN\name" identifier) - a common
        // GeneXus/SSMS-generated-script portability snag: the domain almost
        // never exists on the machine you're restoring onto, and that one
        // statement otherwise blocks the entire import. Does not touch SQL
        // logins/roles (no backslash) or anything else. Set to blank to keep
        // every statement exactly as the source script has it.
        d.put("split.skipPattern", "^(CREATE USER|ALTER ROLE\\s.*ADD MEMBER)\\b.*\\\\");

        // --- Execution -----------------------------------------------------
        d.put("run.stopOnError", "true");
        d.put("run.commitEvery", "1000");
        d.put("run.retries", "1");
        d.put("run.retryDelaySeconds", "5");
        d.put("run.from", "0");
        d.put("run.to", "0");
        d.put("run.progressEvery", "5000");

        // --- Smoke test ------------------------------------------------------
        d.put("smoke.rollback", "true");
        d.put("smoke.stopOnError", "false");

        // --- Housekeeping ------------------------------------------------------
        d.put("log.dir", "logs");
        d.put("log.level", "INFO");
        d.put("state.file", "nanosplit-state.json");
        d.put("index.file", "index.json");

        return d;
    }
}
