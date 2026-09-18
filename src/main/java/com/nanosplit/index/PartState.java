package com.nanosplit.index;

/** Run status for one part, as recorded in {@code nanosplit-state.json}. */
public final class PartState {
    public String status = Status.PENDING;
    public int attempts = 0;
    public long statementsDone = 0;
    public long rowsAffected = 0;
    public double seconds = 0;
    public String startedAt;
    public String finishedAt;
    public String error;
    public StatementRef failedAt;
    public Integer resumedFrom;
    public String reason; // set when status == SKIPPED

    public static final class Status {
        public static final String PENDING = "pending";
        public static final String RUNNING = "running";
        public static final String DONE = "done";
        public static final String FAILED = "failed";
        public static final String SKIPPED = "skipped";

        private Status() {
        }
    }
}
