package com.nanosplit.cli;

import java.util.Locale;
import java.util.concurrent.Callable;

import com.nanosplit.config.AppConfig;
import com.nanosplit.index.Index;
import com.nanosplit.split.SplitProgress;
import com.nanosplit.split.Splitter;
import com.nanosplit.util.Sizes;

import picocli.CommandLine.Command;
import picocli.CommandLine.Mixin;
import picocli.CommandLine.Option;

/** {@code nanosplit split}: cuts the input file into runnable, resumable parts. */
@Command(name = "split", description = "Split the configured input file into parts under output.dir")
public final class SplitCommand implements Callable<Integer> {

    @Mixin
    CommonOptions common;

    @Option(names = "--force", description = "Delete any existing parts in output.dir before splitting")
    boolean force;

    @Override
    public Integer call() throws Exception {
        AppConfig cfg = AppConfig.load(common.config, common.overrides);
        for (String unknown : cfg.unknownKeys()) {
            System.out.println("note: unrecognised config key '" + unknown + "' (check spelling; it is ignored)");
        }

        Splitter splitter = new Splitter(cfg);
        splitter.prepareOutputDir(force);

        System.out.println("Input:  " + cfg.path("input.file", true));
        System.out.println("Output: " + cfg.path("output.dir", false));
        System.out.println();

        long startedAt = System.currentTimeMillis();
        Index index = splitter.run(new Splitter.ProgressListener() {
            @Override
            public void onProgress(SplitProgress p) {
                double pct = p.totalBytes > 0 ? (100.0 * p.bytesRead / p.totalBytes) : 0;
                System.out.print(String.format(Locale.ROOT,
                        "\r  %5.1f%%  %s / %s  |  %,d statements  |  %d part(s)  |  %s elapsed   ",
                        pct, Sizes.format(p.bytesRead), Sizes.format(p.totalBytes),
                        p.statements, p.parts, Sizes.formatSeconds(p.seconds)));
                System.out.flush();
            }
        });
        System.out.println();
        System.out.println();

        double seconds = (System.currentTimeMillis() - startedAt) / 1000.0;
        System.out.println("Done in " + Sizes.formatSeconds(seconds) + ":");
        System.out.println("  parts:      " + index.data.totals.parts);
        System.out.println("  statements: " + String.format(Locale.ROOT, "%,d", index.data.totals.statements)
                + "  (" + String.format(Locale.ROOT, "%,d", index.data.totals.inserts) + " inserts)");
        System.out.println("  bytes:      " + Sizes.format(index.data.totals.bytes));
        System.out.println("  index:      " + index.path());
        if (!index.data.totals.tables.isEmpty()) {
            System.out.println("  tables:");
            int shown = 0;
            for (java.util.Map.Entry<String, Long> e : index.data.totals.tables.entrySet()) {
                if (shown++ >= 15) {
                    System.out.println("    ... and " + (index.data.totals.tables.size() - shown + 1) + " more");
                    break;
                }
                System.out.println("    " + e.getKey() + ": " + String.format(Locale.ROOT, "%,d", e.getValue()));
            }
        }
        System.out.println();
        System.out.println("Next: 'nanosplit smoke' to sanity-check the first/last row of each part, "
                + "then 'nanosplit run' to execute them all.");
        return 0;
    }
}
