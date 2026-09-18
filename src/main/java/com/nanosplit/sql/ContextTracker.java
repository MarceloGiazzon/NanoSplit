package com.nanosplit.sql;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Remembers the session-configuring statements ({@code USE}, {@code SET
 * IDENTITY_INSERT ... ON}, other {@code SET} options) a later part must
 * replay to be runnable on its own, and the closing statements a part must
 * append to leave the session the way it found it.
 */
public final class ContextTracker {

    private static final Pattern IDENTITY_RE = Pattern.compile(
            "^SET\\s+IDENTITY_INSERT\\s+(?<table>.+?)\\s+(?<mode>ON|OFF)\\s*$", Pattern.CASE_INSENSITIVE);
    private static final Pattern USE_RE = Pattern.compile("^USE\\s+(?<db>.+?)\\s*$", Pattern.CASE_INSENSITIVE);
    private static final Pattern SET_RE = Pattern.compile("^SET\\s+(?<option>[A-Z_]+)\\b", Pattern.CASE_INSENSITIVE);

    private final Pattern pattern;
    private final Map<String, String> active = new LinkedHashMap<String, String>();

    public ContextTracker(Pattern pattern) {
        this.pattern = pattern;
    }

    /** Records {@code text} if it configures the session. Returns true if it did. */
    public boolean observe(String text) {
        if (pattern == null) {
            return false;
        }
        String flat = flatten(text);
        if (!pattern.matcher(flat).find()) {
            return false;
        }
        Matcher identity = IDENTITY_RE.matcher(flat);
        if (identity.matches()) {
            String key = "IDENTITY_INSERT " + identity.group("table").toUpperCase();
            if (identity.group("mode").equalsIgnoreCase("OFF")) {
                active.remove(key);
            } else {
                active.put(key, flat);
            }
            return true;
        }
        if (USE_RE.matcher(flat).matches()) {
            active.put("USE", flat);
            return true;
        }
        Matcher option = SET_RE.matcher(flat);
        if (option.find()) {
            active.put("SET " + option.group("option").toUpperCase(), flat);
            return true;
        }
        String key = flat.toUpperCase();
        active.put(key.length() > 80 ? key.substring(0, 80) : key, flat);
        return true;
    }

    /** Context statements in the order they were first seen. */
    public List<String> replay() {
        return new ArrayList<String>(active.values());
    }

    /** {@code OFF} counterparts for every {@code IDENTITY_INSERT} left open. */
    public List<String> closers() {
        List<String> out = new ArrayList<String>();
        for (Map.Entry<String, String> e : active.entrySet()) {
            if (e.getKey().startsWith("IDENTITY_INSERT ")) {
                Matcher m = IDENTITY_RE.matcher(e.getValue());
                if (m.matches()) {
                    out.add("SET IDENTITY_INSERT " + m.group("table") + " OFF");
                }
            }
        }
        return out;
    }

    private static String flatten(String text) {
        return text.trim().replaceAll("\\s+", " ");
    }
}
