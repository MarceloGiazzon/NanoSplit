package com.nanosplit.db;

import com.nanosplit.index.StatementRef;

/** Outcome of executing one part file. */
public final class RunResult {
    public boolean success;
    public long statementsDone;
    public long rowsAffected;
    public double seconds;
    public String error;
    public StatementRef failedAt;
}
