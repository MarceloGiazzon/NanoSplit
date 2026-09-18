package com.nanosplit.index;

import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonSyntaxException;
import com.nanosplit.util.AtomicFiles;

/**
 * Mutable run state, persisted after every part (and every commit). This is
 * the file that answers "where did it stop?", and what makes {@code run
 * --resume} possible.
 */
public final class State {

    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().disableHtmlEscaping().create();

    public final StateData data;
    private final File path;

    private State(StateData data, File path) {
        this.data = data;
        this.path = path;
    }

    public static State loadOrNew(File path, Index index) {
        if (path.isFile()) {
            try {
                String json = new String(Files.readAllBytes(path.toPath()), StandardCharsets.UTF_8);
                StateData data = GSON.fromJson(json, StateData.class);
                if (data != null) {
                    if (data.parts == null) {
                        data.parts = new java.util.LinkedHashMap<String, PartState>();
                    }
                    return new State(data, path);
                }
            } catch (IOException | JsonSyntaxException e) {
                // Corrupt or half-written: start fresh rather than refuse to run.
            }
        }
        StateData data = new StateData();
        data.createdAt = Index.nowIso();
        data.updatedAt = data.createdAt;
        if (index != null) {
            data.index.file = index.path() != null ? index.path().getName() : null;
            data.index.parts = index.parts().size();
            data.index.createdAt = index.data.createdAt;
        }
        return new State(data, path);
    }

    public void save() throws IOException {
        data.updatedAt = Index.nowIso();
        AtomicFiles.writeUtf8(path.toPath(), GSON.toJson(data));
    }

    // -- per-part records --------------------------------------------------

    public PartState record(int number) {
        String key = String.valueOf(number);
        PartState state = data.parts.get(key);
        if (state == null) {
            state = new PartState();
            data.parts.put(key, state);
        }
        return state;
    }

    public String status(int number) {
        return record(number).status;
    }

    public boolean isDone(int number) {
        return PartState.Status.DONE.equals(status(number));
    }

    public PartState markRunning(int number, int resumeFrom) {
        PartState s = record(number);
        s.status = PartState.Status.RUNNING;
        s.attempts++;
        s.startedAt = Index.nowIso();
        s.error = null;
        s.failedAt = null;
        if (resumeFrom > 0) {
            s.resumedFrom = resumeFrom;
        }
        return s;
    }

    public void markDone(int number, long statements, long rows, double seconds) {
        PartState s = record(number);
        s.status = PartState.Status.DONE;
        s.statementsDone = statements;
        s.rowsAffected = rows;
        s.seconds = round(seconds);
        s.finishedAt = Index.nowIso();
        s.error = null;
        s.failedAt = null;
    }

    public void markFailed(int number, String error, long statementsDone, StatementRef failedAt, double seconds) {
        PartState s = record(number);
        s.status = PartState.Status.FAILED;
        s.statementsDone = statementsDone;
        s.error = error;
        s.seconds = round(seconds);
        s.finishedAt = Index.nowIso();
        s.failedAt = failedAt;
    }

    public void markSkipped(int number, String reason) {
        PartState s = record(number);
        s.status = PartState.Status.SKIPPED;
        s.reason = reason;
        s.finishedAt = Index.nowIso();
    }

    public void progress(int number, long statementsDone, long rows) {
        PartState s = record(number);
        s.statementsDone = statementsDone;
        s.rowsAffected = rows;
    }

    // -- aggregates ----------------------------------------------------------

    public Map<String, Integer> counts() {
        Map<String, Integer> tally = new java.util.LinkedHashMap<String, Integer>();
        for (PartState s : data.parts.values()) {
            tally.merge(s.status, 1, Integer::sum);
        }
        return tally;
    }

    public Integer firstUnfinished(List<Integer> numbers) {
        for (Integer n : numbers) {
            if (!isDone(n)) {
                return n;
            }
        }
        return null;
    }

    public List<PartState> failures() {
        List<PartState> out = new ArrayList<PartState>();
        for (PartState s : data.parts.values()) {
            if (PartState.Status.FAILED.equals(s.status)) {
                out.add(s);
            }
        }
        return out;
    }

    private static double round(double seconds) {
        return Math.round(seconds * 1000.0) / 1000.0;
    }
}
