package com.nanosplit.config;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.Reader;
import java.util.ArrayList;
import java.util.List;
import java.util.Properties;

/**
 * A ``.properties`` parser that is forgiving about backslashes.
 *
 * <p>{@link java.util.Properties#load} follows the Java spec to the letter: an
 * escape it does not recognise (say {@code \P}) silently collapses to the bare
 * letter ({@code P}). That is exactly what happens to a half-escaped Windows
 * path such as {@code C:\Program Files\x} - it comes out as {@code C:rogram
 * Filesx}. This reader keeps an unrecognised escape literal instead
 * ({@code \P} stays {@code \P}), so paths survive whether or not their
 * backslashes were doubled. {@code \t}, {@code \n}, {@code \r}, {@code \f} and
 * a unicode escape still mean what they always have.
 */
final class LenientPropertiesReader {

    private LenientPropertiesReader() {
    }

    static void parseInto(Reader source, Properties target) throws IOException {
        BufferedReader reader = new BufferedReader(source);
        String rawLine;
        StringBuilder logical = null;
        List<String> lines = new ArrayList<String>();
        while ((rawLine = reader.readLine()) != null) {
            lines.add(rawLine);
        }
        int i = 0;
        while (i < lines.size()) {
            String line = lines.get(i);
            String stripped = stripLeading(line);
            if (logical == null && (stripped.isEmpty() || stripped.charAt(0) == '#' || stripped.charAt(0) == '!')) {
                i++;
                continue;
            }
            String candidate = logical == null ? line : stripLeading(line);
            if (logical != null) {
                logical.append(candidate);
            } else {
                logical = new StringBuilder(candidate);
            }
            int trailingBackslashes = countTrailingBackslashes(candidate);
            i++;
            if (trailingBackslashes % 2 == 1) {
                logical.setLength(logical.length() - 1); // drop the continuation backslash
                continue;
            }
            String[] pair = splitPair(logical.toString());
            if (pair[0] != null && !pair[0].isEmpty()) {
                target.setProperty(pair[0], pair[1]);
            }
            logical = null;
        }
        if (logical != null && logical.length() > 0) {
            String[] pair = splitPair(logical.toString());
            if (pair[0] != null && !pair[0].isEmpty()) {
                target.setProperty(pair[0], pair[1]);
            }
        }
    }

    private static String stripLeading(String s) {
        int i = 0;
        while (i < s.length() && Character.isWhitespace(s.charAt(i))) {
            i++;
        }
        return s.substring(i);
    }

    private static int countTrailingBackslashes(String s) {
        int count = 0;
        for (int i = s.length() - 1; i >= 0 && s.charAt(i) == '\\'; i--) {
            count++;
        }
        return count;
    }

    /** Splits "key = value" respecting backslash-escaped separators in the key. */
    private static String[] splitPair(String logical) {
        StringBuilder key = new StringBuilder();
        int i = 0;
        int n = logical.length();
        while (i < n) {
            char c = logical.charAt(i);
            if (c == '\\' && i + 1 < n) {
                key.append(c).append(logical.charAt(i + 1));
                i += 2;
                continue;
            }
            if (c == '=' || c == ':' || Character.isWhitespace(c)) {
                break;
            }
            key.append(c);
            i++;
        }
        String rest = logical.substring(i);
        int j = 0;
        while (j < rest.length() && Character.isWhitespace(rest.charAt(j))) {
            j++;
        }
        rest = rest.substring(j);
        if (!rest.isEmpty() && (rest.charAt(0) == '=' || rest.charAt(0) == ':')) {
            rest = rest.substring(1);
            int k = 0;
            while (k < rest.length() && Character.isWhitespace(rest.charAt(k))) {
                k++;
            }
            rest = rest.substring(k);
        }
        return new String[]{unescape(key.toString()), unescape(rest)};
    }

    private static String unescape(String text) {
        StringBuilder out = new StringBuilder(text.length());
        int i = 0;
        int n = text.length();
        while (i < n) {
            char c = text.charAt(i);
            if (c != '\\') {
                out.append(c);
                i++;
                continue;
            }
            if (i + 1 >= n) {
                out.append('\\');
                break;
            }
            char next = text.charAt(i + 1);
            switch (next) {
                case 'n': out.append('\n'); i += 2; break;
                case 'r': out.append('\r'); i += 2; break;
                case 't': out.append('\t'); i += 2; break;
                case 'f': out.append('\f'); i += 2; break;
                case 'u':
                    if (i + 5 < n + 1 && i + 6 <= n) {
                        String hex = text.substring(i + 2, Math.min(i + 6, n));
                        try {
                            out.append((char) Integer.parseInt(hex, 16));
                            i += 6;
                            break;
                        } catch (NumberFormatException ignored) {
                            // fall through to literal handling below
                        }
                    }
                    out.append('\\').append('u');
                    i += 2;
                    break;
                case '\\': case '=': case ':': case ' ': case '!': case '#': case '\'': case '"':
                    out.append(next);
                    i += 2;
                    break;
                default:
                    // Forgiving: keep the backslash so "C:\Program Files" survives.
                    out.append('\\').append(next);
                    i += 2;
            }
        }
        return out.toString();
    }
}
