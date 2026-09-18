package com.nanosplit.cli;

import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.security.MessageDigest;
import java.util.concurrent.Callable;

import com.nanosplit.config.AppConfig;
import com.nanosplit.index.Index;
import com.nanosplit.index.PartEntry;

import picocli.CommandLine.Command;
import picocli.CommandLine.Mixin;

/**
 * {@code nanosplit verify}: recomputes each part's SHA-256 and its byte size,
 * and compares them against what {@code split} recorded in {@code index.json}.
 * Run this before {@code run} if a part file might have been touched, moved
 * across drives, or copied by something that could mangle line endings.
 */
@Command(name = "verify", description = "Check every part file's checksum against index.json")
public final class VerifyCommand implements Callable<Integer> {

    @Mixin
    CommonOptions common;

    @Override
    public Integer call() throws Exception {
        AppConfig cfg = AppConfig.load(common.config, common.overrides);
        File outputDir = cfg.path("output.dir", true);
        Index index = Index.load(new File(outputDir, cfg.get("index.file")));

        int bad = 0;
        for (PartEntry part : index.parts()) {
            File file = index.partPath(part);
            if (!file.isFile()) {
                System.out.println("part " + part.n + " MISSING: " + file);
                bad++;
                continue;
            }
            long size = file.length();
            if (size != part.bytes) {
                System.out.println("part " + part.n + " SIZE MISMATCH: expected " + part.bytes + " bytes, found " + size);
                bad++;
                continue;
            }
            if (part.sha256 != null) {
                String actual = sha256(file);
                if (!actual.equalsIgnoreCase(part.sha256)) {
                    System.out.println("part " + part.n + " CHECKSUM MISMATCH: " + file);
                    bad++;
                    continue;
                }
            }
            System.out.println("part " + part.n + " OK");
        }
        System.out.println();
        if (bad == 0) {
            System.out.println("All " + index.parts().size() + " part(s) verified.");
            return 0;
        }
        System.out.println(bad + " part(s) failed verification - re-run 'nanosplit split --force' to regenerate them.");
        return 1;
    }

    private static String sha256(File file) throws IOException {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            try (InputStream in = Files.newInputStream(file.toPath())) {
                byte[] buf = new byte[1 << 20];
                int n;
                while ((n = in.read(buf)) != -1) {
                    digest.update(buf, 0, n);
                }
            }
            byte[] hash = digest.digest();
            StringBuilder sb = new StringBuilder(hash.length * 2);
            for (byte b : hash) {
                sb.append(Character.forDigit((b >> 4) & 0xF, 16));
                sb.append(Character.forDigit(b & 0xF, 16));
            }
            return sb.toString();
        } catch (java.security.NoSuchAlgorithmException e) {
            throw new IOException("SHA-256 not available", e);
        }
    }
}
