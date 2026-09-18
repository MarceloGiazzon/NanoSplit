package com.nanosplit.util;

import java.io.IOException;
import java.io.PrintWriter;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;

/**
 * Minimal dual-sink logger: colour-free lines to the console, timestamped
 * lines to a file under {@code log.dir}. Deliberately not java.util.logging -
 * one file, no config ceremony, easy to read while a 10 GB run is in flight.
 */
public final class Log {

    public enum Level { DEBUG, INFO, WARN, ERROR }

    private static final DateTimeFormatter TS = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");

    private final Level threshold;
    private final PrintWriter file;

    private Log(Level threshold, PrintWriter file) {
        this.threshold = threshold;
        this.file = file;
    }

    public static Log create(String levelName, Path logDir, String fileName) {
        Level level;
        try {
            level = Level.valueOf(levelName.trim().toUpperCase());
        } catch (Exception e) {
            level = Level.INFO;
        }
        PrintWriter writer = null;
        if (logDir != null) {
            try {
                Files.createDirectories(logDir);
                writer = new PrintWriter(Files.newBufferedWriter(logDir.resolve(fileName),
                        java.nio.charset.StandardCharsets.UTF_8,
                        java.nio.file.StandardOpenOption.CREATE,
                        java.nio.file.StandardOpenOption.APPEND), true);
            } catch (IOException e) {
                System.err.println("warning: could not open log file: " + e.getMessage());
            }
        }
        return new Log(level, writer);
    }

    public static Log consoleOnly(String levelName) {
        return create(levelName, null, null);
    }

    private boolean enabled(Level level) {
        return level.ordinal() >= threshold.ordinal();
    }

    private void emit(Level level, String message) {
        if (!enabled(level)) {
            return;
        }
        String line = message;
        if (level != Level.INFO) {
            line = "[" + level + "] " + message;
        }
        if (level == Level.ERROR || level == Level.WARN) {
            System.err.println(line);
        } else {
            System.out.println(line);
        }
        if (file != null) {
            file.println(LocalDateTime.now().format(TS) + " [" + level + "] " + message);
        }
    }

    public void debug(String message) {
        emit(Level.DEBUG, message);
    }

    public void info(String message) {
        emit(Level.INFO, message);
    }

    public void warn(String message) {
        emit(Level.WARN, message);
    }

    public void error(String message) {
        emit(Level.ERROR, message);
    }

    public void error(String message, Throwable t) {
        emit(Level.ERROR, message + ": " + t.getMessage());
        if (file != null) {
            t.printStackTrace(file);
        }
    }

    /** Overwrites the current console line - used for progress bars. */
    public void progress(String message) {
        System.out.print("\r" + message);
        System.out.flush();
    }

    public void progressDone() {
        System.out.println();
    }

    public void close() {
        if (file != null) {
            file.close();
        }
    }
}
