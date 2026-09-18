package com.nanosplit.sql;

import java.util.HashMap;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** Pulls the target table out of an {@code INSERT} statement's text. */
public final class TableNames {

    private static final Pattern INSERT_TABLE = Pattern.compile(
            "^INSERT\\s+(?:INTO\\s+)?"
                    + "((?:\\[[^]]+]|[A-Za-z_@#][\\w$@#]*)"
                    + "(?:\\s*\\.\\s*(?:\\[[^]]+]|[A-Za-z_@#][\\w$@#]*)){0,2})",
            Pattern.CASE_INSENSITIVE);

    private TableNames() {
    }

    /** {@code INSERT [dbo].[Entity] (...) VALUES (...)} -&gt; {@code dbo.Entity}. */
    public static String of(String text, Map<String, String> memo) {
        String key = text.length() > 120 ? text.substring(0, 120) : text;
        if (memo != null && memo.containsKey(key)) {
            return memo.get(key);
        }
        Matcher m = INSERT_TABLE.matcher(ltrim(text));
        String result = null;
        if (m.find() && m.start() == 0) {
            StringBuilder sb = new StringBuilder();
            for (String part : m.group(1).split("\\.")) {
                String p = part.trim();
                if (p.startsWith("[") && p.endsWith("]")) {
                    p = p.substring(1, p.length() - 1);
                }
                if (!p.isEmpty()) {
                    if (sb.length() > 0) {
                        sb.append('.');
                    }
                    sb.append(p);
                }
            }
            result = sb.length() > 0 ? sb.toString() : null;
        }
        if (memo != null) {
            memo.put(key, result);
        }
        return result;
    }

    public static Map<String, String> newMemo() {
        return new HashMap<String, String>();
    }

    private static String ltrim(String s) {
        int i = 0;
        while (i < s.length() && Character.isWhitespace(s.charAt(i))) {
            i++;
        }
        return s.substring(i);
    }
}
