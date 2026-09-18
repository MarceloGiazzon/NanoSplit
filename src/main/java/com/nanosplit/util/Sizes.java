package com.nanosplit.util;

import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** Parsing and formatting for human-friendly byte sizes ("64MB", "512kb"). */
public final class Sizes {

    private static final Pattern SIZE_RE =
            Pattern.compile("^\\s*([0-9]*\\.?[0-9]+)\\s*([kKmMgGtT]?)[bB]?\\s*$");

    private Sizes() {
    }

    /** Parses "64MB" / "512kb" / "1048576" into a byte count. 0/blank -&gt; 0 (unlimited). */
    public static long parse(String key, String value) {
        if (value == null) {
            return 0;
        }
        String trimmed = value.trim();
        if (trimmed.isEmpty() || trimmed.equals("0")) {
            return 0;
        }
        Matcher m = SIZE_RE.matcher(trimmed);
        if (!m.matches()) {
            throw new IllegalArgumentException(key + " must be a size such as 64MB, got '" + value + "'");
        }
        double amount = Double.parseDouble(m.group(1));
        long unit;
        switch (m.group(2).toLowerCase(Locale.ROOT)) {
            case "k": unit = 1024L; break;
            case "m": unit = 1024L * 1024; break;
            case "g": unit = 1024L * 1024 * 1024; break;
            case "t": unit = 1024L * 1024 * 1024 * 1024; break;
            default: unit = 1L;
        }
        return (long) (amount * unit);
    }

    /** Formats a byte count as e.g. "1.3 GB" for progress lines and reports. */
    public static String format(long bytes) {
        if (bytes < 1024) {
            return bytes + " B";
        }
        String[] units = {"KB", "MB", "GB", "TB", "PB"};
        double value = bytes;
        int i = -1;
        do {
            value /= 1024.0;
            i++;
        } while (value >= 1024 && i < units.length - 1);
        return String.format(Locale.ROOT, "%.1f %s", value, units[i]);
    }

    /** Formats a duration in seconds as e.g. "1h 12m 03s" / "42.3s". */
    public static String formatSeconds(double seconds) {
        if (seconds < 60) {
            return String.format(Locale.ROOT, "%.1fs", seconds);
        }
        long total = (long) seconds;
        long h = total / 3600;
        long m = (total % 3600) / 60;
        long s = total % 60;
        if (h > 0) {
            return String.format(Locale.ROOT, "%dh %02dm %02ds", h, m, s);
        }
        return String.format(Locale.ROOT, "%dm %02ds", m, s);
    }
}
