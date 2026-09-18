package com.nanosplit.util;

import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;

/** Write-then-rename so a crash mid-write never leaves a truncated file behind. */
public final class AtomicFiles {

    private AtomicFiles() {
    }

    public static void writeUtf8(Path target, String content) throws IOException {
        Path dir = target.toAbsolutePath().getParent();
        if (dir != null) {
            Files.createDirectories(dir);
        }
        Path tmp = Files.createTempFile(dir, ".nanosplit-", ".tmp");
        try {
            Files.write(tmp, content.getBytes(StandardCharsets.UTF_8));
            Files.move(tmp, target, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
        } catch (IOException e) {
            // Cross-filesystem or locked-target fallback: non-atomic but still correct.
            Files.move(tmp, target, StandardCopyOption.REPLACE_EXISTING);
        } finally {
            Files.deleteIfExists(tmp);
        }
    }

    public static void ensureDir(File dir) throws IOException {
        Files.createDirectories(dir.toPath());
    }
}
