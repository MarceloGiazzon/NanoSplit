package com.nanosplit.db;

import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.TimeUnit;

import com.nanosplit.config.AppConfig;
import com.nanosplit.config.ConfigException;

/**
 * Shared plumbing for driving {@code sqlcmd.exe} as a subprocess.
 *
 * <p>Why this exists at all: the Microsoft JDBC driver only ever speaks
 * TCP/IP, while {@code sqlcmd} (like SSMS) can reach a local SQL Server over
 * Named Pipes or Shared Memory. On a machine where TCP/IP has not been
 * enabled for the instance - a common state for a fresh SQL Server Express
 * install - {@code sqlcmd} still connects fine and the JDBC path cannot.
 * {@code db.driver=auto} (the default) picks {@code sqlcmd} whenever it
 * resolves on {@code PATH}, precisely to make the local, no-TCP case work
 * out of the box.
 */
public final class SqlcmdSupport {

    private SqlcmdSupport() {
    }

    public static final class ProcessResult {
        public final int exitCode;
        public final String output; // stdout+stderr merged, tail-truncated

        ProcessResult(int exitCode, String output) {
            this.exitCode = exitCode;
            this.output = output;
        }
    }

    /** True if the configured sqlcmd executable can actually be launched. */
    public static boolean isAvailable(AppConfig cfg) {
        try {
            String path = cfg.get("db.sqlcmdPath").trim();
            ProcessBuilder pb = new ProcessBuilder(path, "-?");
            pb.redirectErrorStream(true);
            pb.redirectOutput(ProcessBuilder.Redirect.to(tempSink()));
            Process p = pb.start();
            return p.waitFor(10, TimeUnit.SECONDS);
        } catch (Exception e) {
            return false;
        }
    }

    /** Connection/auth flags common to every sqlcmd invocation NanoSplit makes. */
    public static List<String> baseArgs(AppConfig cfg) throws ConfigException {
        List<String> args = new ArrayList<String>();
        args.add(cfg.get("db.sqlcmdPath").trim());
        args.add("-S");
        args.add(cfg.get("db.server").trim());
        String db = cfg.get("db.name").trim();
        if (!db.isEmpty()) {
            args.add("-d");
            args.add(db);
        }
        if (cfg.bool("db.integratedSecurity")) {
            args.add("-E");
        } else {
            args.add("-U");
            args.add(cfg.get("db.user").trim());
            args.add("-P");
            args.add(cfg.get("db.password"));
        }
        if (cfg.bool("db.trustServerCertificate")) {
            args.add("-C");
        }
        String encrypt = cfg.get("db.encrypt").trim().toLowerCase();
        if (encrypt.equals("yes") || encrypt.equals("true")) {
            args.add("-N");
        }
        int loginTimeout = cfg.intVal("db.connectTimeoutSeconds");
        if (loginTimeout > 0) {
            args.add("-l");
            args.add(String.valueOf(loginTimeout));
        }
        int queryTimeout = cfg.intVal("db.commandTimeoutSeconds");
        if (queryTimeout > 0) {
            args.add("-t");
            args.add(String.valueOf(queryTimeout));
        }
        args.add("-b");   // stop and return a non-zero exit code on the first error
        args.add("-r1");  // send error messages to stderr, not stdout
        String extra = cfg.get("db.sqlcmdExtraArgs").trim();
        if (!extra.isEmpty()) {
            for (String token : extra.split("\\s+")) {
                if (!token.isEmpty()) {
                    args.add(token);
                }
            }
        }
        return args;
    }

    /** Runs {@code sqlcmd <baseArgs> -i scriptFile}, merging stdout+stderr. */
    public static ProcessResult runScript(List<String> baseArgs, File scriptFile, int timeoutSeconds) throws IOException {
        List<String> args = new ArrayList<String>(baseArgs);
        args.add("-i");
        args.add(scriptFile.getAbsolutePath());

        File outFile = File.createTempFile("nanosplit-sqlcmd-", ".log");
        try {
            ProcessBuilder pb = new ProcessBuilder(args);
            pb.redirectErrorStream(true);
            pb.redirectOutput(ProcessBuilder.Redirect.to(outFile));
            Process process = pb.start();
            boolean finished = timeoutSeconds > 0
                    ? process.waitFor(timeoutSeconds, TimeUnit.SECONDS)
                    : process.waitFor(365, TimeUnit.DAYS);
            if (!finished) {
                process.destroyForcibly();
                return new ProcessResult(-1, "sqlcmd timed out after " + timeoutSeconds + "s and was killed");
            }
            String output = tail(outFile, 8000);
            return new ProcessResult(process.exitValue(), output);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IOException("interrupted while waiting for sqlcmd", e);
        } finally {
            outFile.delete();
        }
    }

    private static String tail(File file, int maxChars) throws IOException {
        byte[] bytes = Files.readAllBytes(file.toPath());
        String text = new String(bytes, StandardCharsets.UTF_8);
        return text.length() <= maxChars ? text : "..." + text.substring(text.length() - maxChars);
    }

    private static File tempSink() throws IOException {
        File f = File.createTempFile("nanosplit-sqlcmd-probe-", ".log");
        f.deleteOnExit();
        return f;
    }
}
