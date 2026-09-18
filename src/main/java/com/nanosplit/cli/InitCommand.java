package com.nanosplit.cli;

import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.util.concurrent.Callable;

import picocli.CommandLine.Command;
import picocli.CommandLine.Mixin;
import picocli.CommandLine.Option;

/** Creates a local {@code nanosplit.properties} from the bundled template. */
@Command(name = "init", description = "Create a local nanosplit.properties you can edit with your own paths and credentials")
public final class InitCommand implements Callable<Integer> {

    @Mixin
    CommonOptions common;

    @Option(names = "--force", description = "Overwrite an existing nanosplit.properties")
    boolean force;

    @Override
    public Integer call() throws Exception {
        File target = new File(common.config != null ? common.config : "nanosplit.properties");
        if (target.exists() && !force) {
            System.err.println(target + " already exists - pass --force to overwrite it.");
            return 1;
        }
        try (InputStream in = getClass().getResourceAsStream("/nanosplit.properties.example")) {
            if (in == null) {
                System.err.println("internal error: bundled template not found");
                return 1;
            }
            byte[] bytes = readAll(in);
            Files.write(target.toPath(), bytes);
        }
        System.out.println("Wrote " + target.getAbsolutePath());
        System.out.println("Edit it with your database server, database name and input file path, then run:");
        System.out.println("  nanosplit split");
        System.out.println();
        System.out.println("Never commit this file once it holds real credentials - it is already listed in .gitignore.");
        return 0;
    }

    private static byte[] readAll(InputStream in) throws IOException {
        java.io.ByteArrayOutputStream out = new java.io.ByteArrayOutputStream();
        byte[] buf = new byte[8192];
        int n;
        while ((n = in.read(buf)) != -1) {
            out.write(buf, 0, n);
        }
        return out.toByteArray();
    }
}
