package com.nanosplit.db;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.SQLException;

import com.nanosplit.config.AppConfig;
import com.nanosplit.config.ConfigException;

/**
 * Builds a JDBC connection to SQL Server from the {@code db.*} config keys.
 *
 * <p>Two authentication paths:
 * <ul>
 *   <li>{@code db.integratedSecurity=true} (default): Windows authentication.
 *       On Windows this works out of the box when the mssql-jdbc auth DLL
 *       matching your JVM's bitness is next to the jar or on {@code PATH} -
 *       see the README. It is what a local {@code SQLEXPRESS} instance
 *       almost always wants.</li>
 *   <li>{@code db.user} / {@code db.password} set: SQL Server authentication -
 *       no native DLL required, works cross-platform.</li>
 * </ul>
 */
public final class ConnectionFactory {

    private final AppConfig cfg;

    public ConnectionFactory(AppConfig cfg) {
        this.cfg = cfg;
    }

    public Connection connect() throws SQLException, ConfigException {
        try {
            Class.forName("com.microsoft.sqlserver.jdbc.SQLServerDriver");
        } catch (ClassNotFoundException e) {
            throw new SQLException("mssql-jdbc driver not found on the classpath", e);
        }
        return DriverManager.getConnection(buildUrl());
    }

    public String buildUrl() throws ConfigException {
        StringBuilder url = new StringBuilder("jdbc:sqlserver://");
        String server = cfg.get("db.server").trim();
        // "HOST\INSTANCE" -> jdbc wants ";instanceName=INSTANCE" instead of a backslash
        // in the host part. Note: the Microsoft JDBC driver only speaks TCP/IP - a named
        // pipe or shared-memory server name will not work here even though sqlcmd/ODBC
        // accept one; db.server must resolve to a host SQL Server is listening for TCP on.
        int slash = server.indexOf('\\');
        url.append(slash >= 0 ? server.substring(0, slash) : server);
        url.append(";");
        if (slash >= 0) {
            url.append("instanceName=").append(server.substring(slash + 1)).append(";");
        }
        String db = cfg.get("db.name").trim();
        if (!db.isEmpty()) {
            url.append("databaseName=").append(db).append(";");
        }
        boolean integrated = cfg.bool("db.integratedSecurity");
        if (integrated) {
            url.append("integratedSecurity=true;");
        } else {
            url.append("user=").append(cfg.get("db.user").trim()).append(";");
            url.append("password=").append(cfg.get("db.password")).append(";");
        }
        String encrypt = cfg.get("db.encrypt").trim().toLowerCase();
        url.append("encrypt=").append(encrypt.equals("yes") || encrypt.equals("true") ? "true" : "false").append(";");
        url.append("trustServerCertificate=").append(cfg.bool("db.trustServerCertificate")).append(";");
        url.append("loginTimeout=").append(cfg.intVal("db.connectTimeoutSeconds")).append(";");
        String appName = cfg.get("db.applicationName").trim();
        if (!appName.isEmpty()) {
            url.append("applicationName=").append(appName).append(";");
        }
        String extra = cfg.get("db.extraUrlParams").trim();
        if (!extra.isEmpty()) {
            url.append(extra.endsWith(";") ? extra : extra + ";");
        }
        return url.toString();
    }

    /** Same as {@link #buildUrl()} but with the password masked, safe to log. */
    public String describeUrl() throws ConfigException {
        return buildUrl().replaceAll("(?i)(password=)[^;]*", "$1***");
    }
}
