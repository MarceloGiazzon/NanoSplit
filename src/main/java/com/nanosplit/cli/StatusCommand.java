package com.nanosplit.cli;

import java.io.File;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.Callable;

import com.nanosplit.config.AppConfig;
import com.nanosplit.index.Index;
import com.nanosplit.index.PartEntry;
import com.nanosplit.index.PartState;
import com.nanosplit.index.State;

import picocli.CommandLine.Command;
import picocli.CommandLine.Mixin;

/** {@code nanosplit status}: where a run stands - what's done, pending, or failed. */
@Command(name = "status", description = "Show split/run progress: how many parts are done, pending, or failed")
public final class StatusCommand implements Callable<Integer> {

    @Mixin
    CommonOptions common;

    @Override
    public Integer call() throws Exception {
        AppConfig cfg = AppConfig.load(common.config, common.overrides);
        File indexPath = new File(cfg.path("output.dir", false), cfg.get("index.file"));
        Index index = Index.load(indexPath);
        File statePath = new File(cfg.path("output.dir", false), cfg.get("state.file"));
        State state = State.loadOrNew(statePath, index);

        int total = index.parts().size();
        Map<String, Integer> counts = state.counts();
        int done = counts.getOrDefault(PartState.Status.DONE, 0);
        int failed = counts.getOrDefault(PartState.Status.FAILED, 0);
        int running = counts.getOrDefault(PartState.Status.RUNNING, 0);
        int skipped = counts.getOrDefault(PartState.Status.SKIPPED, 0);
        int pending = total - done - failed - running - skipped;

        System.out.println("Index:  " + indexPath + "  (" + total + " part(s), "
                + String.format(Locale.ROOT, "%,d", index.data.totals.statements) + " statements)");
        System.out.println("State:  " + (statePath.isFile() ? statePath.toString() : "(no run yet)"));
        System.out.println();
        System.out.println(String.format(Locale.ROOT, "  done: %-6d  pending: %-6d  failed: %-6d  running: %-6d  skipped: %-6d",
                done, pending, failed, running, skipped));

        List<PartState> failures = state.failures();
        if (!failures.isEmpty()) {
            System.out.println();
            System.out.println("Failed parts:");
            for (PartState f : failures) {
                System.out.println("  - " + f.error);
                if (f.failedAt != null) {
                    System.out.println("      source line " + f.failedAt.sourceLine + ": " + f.failedAt.preview);
                }
            }
            System.out.println();
            System.out.println("Run 'nanosplit run' again to retry - it resumes from where each failed part stopped.");
        } else if (pending > 0) {
            System.out.println();
            System.out.println("Run 'nanosplit run' to execute the remaining part(s).");
        } else if (total > 0) {
            System.out.println();
            System.out.println("All parts done.");
        }
        return failed > 0 ? 1 : 0;
    }
}
