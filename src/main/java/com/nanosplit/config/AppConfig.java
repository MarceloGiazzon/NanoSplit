package com.nanosplit.config;

import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.Reader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Properties;
import java.util.TreeMap;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import com.nanosplit.util.Sizes;

/**
 * Merged configuration: {@link Defaults}, then the {@code .properties} file,
 * then {@code NANOSPLIT_*} environment variables, then {@code --set key=value}
 * on the command line - each layer overriding the one before it.
 *
 * <p>Relative paths resolve against the directory holding the properties
 * file, so a config works the same regardless of the current directory the
 * tool is invoked from.
 */
public final class AppConfig {

    public static final String CONFIG_BASENAME = "nanosplit.properties";
    private static final String ENV_PREFIX = "NANOSPLIT_";
    private static final Pattern INTERPOLATION = Pattern.compile("\\$\\{([^}]+)}");
    private static final Pattern SECRET_HINT = Pattern.compile("password|secret|token|pwd", Pattern.CASE_INSENSITIVE);

    private final Map<String, String> values;
    private final List<String> unknownKeys;
    private final File baseDir;
    private final File source;

    private AppConfig(Map<String, String> values, List<String> unknownKeys, File baseDir, File source) {
        this.values = values;
        this.unknownKeys = unknownKeys;
        this.baseDir = baseDir;
        this.source = source;
    }

    // ---- construction ---------------------------------------------------

    public static AppConfig load(String explicitPath, List<String> overrides) throws ConfigException {
        File resolved = locate(explicitPath);
        if (resolved == null && explicitPath != null) {
            throw new ConfigException("configuration file not found: " + explicitPath);
        }

        Map<String, String> values = new LinkedHashMap<String, String>(Defaults.MAP);
        List<String> unknown = new ArrayList<String>();
        if (resolved != null) {
            Properties fileProps = readProperties(resolved);
            for (String name : fileProps.stringPropertyNames()) {
                if (!Defaults.MAP.containsKey(name)) {
                    unknown.add(name);
                }
                values.put(name, fileProps.getProperty(name));
            }
        }
        File baseDir = resolved != null ? resolved.getParentFile() : new File(".").getAbsoluteFile();
        if (baseDir == null) {
            baseDir = new File(".").getAbsoluteFile();
        }

        for (String key : Defaults.MAP.keySet()) {
            String env = System.getenv(envKeyFor(key));
            if (env != null) {
                values.put(key, env);
            }
        }

        if (overrides != null) {
            for (String item : overrides) {
                int idx = item.indexOf('=');
                if (idx < 0) {
                    throw new ConfigException("--set expects key=value, got '" + item + "'");
                }
                values.put(item.substring(0, idx).trim(), item.substring(idx + 1));
            }
        }

        return new AppConfig(values, unknown, baseDir, resolved);
    }

    private static Properties readProperties(File file) throws ConfigException {
        Properties props = new Properties();
        // java.util.Properties.load() follows the strict Java escaping rules
        // and silently mangles a lone backslash instead of erroring, which is
        // exactly what a half-escaped Windows path is. LenientPropertiesReader
        // keeps an unrecognised escape (\P, \W, ...) literal instead, so a
        // path like "C:\Program Files\x" survives even with single backslashes.
        try (Reader reader = openReader(file)) {
            LenientPropertiesReader.parseInto(reader, props);
        } catch (IOException e) {
            throw new ConfigException("could not read " + file + ": " + e.getMessage());
        }
        return props;
    }

    private static Reader openReader(File file) throws IOException {
        byte[] head = new byte[3];
        int n;
        try (InputStream probe = Files.newInputStream(file.toPath())) {
            n = probe.read(head);
        }
        InputStream in = Files.newInputStream(file.toPath());
        if (n >= 3 && (head[0] & 0xFF) == 0xEF && (head[1] & 0xFF) == 0xBB && (head[2] & 0xFF) == 0xBF) {
            in.skip(3);
        }
        return new InputStreamReader(in, StandardCharsets.UTF_8);
    }

    private static File locate(String explicitPath) {
        if (explicitPath != null) {
            File f = new File(explicitPath);
            return f.isFile() ? f : null;
        }
        File cwdCandidate = new File(System.getProperty("user.dir"), CONFIG_BASENAME);
        if (cwdCandidate.isFile()) {
            return cwdCandidate;
        }
        return null;
    }

    public static String envKeyFor(String key) {
        return ENV_PREFIX + key.replaceAll("[^A-Za-z0-9]+", "_").toUpperCase();
    }

    // ---- typed access -----------------------------------------------------

    public String raw(String key, String fallback) {
        String v = values.get(key);
        return v != null ? v : fallback;
    }

    public String get(String key) throws ConfigException {
        if (!values.containsKey(key)) {
            throw new ConfigException("unknown configuration key: " + key);
        }
        String value = values.get(key);
        java.util.Set<String> seen = new java.util.HashSet<String>();
        while (true) {
            Matcher m = INTERPOLATION.matcher(value);
            if (!m.find()) {
                return value;
            }
            String name = m.group(1);
            if (!seen.add(name)) {
                throw new ConfigException("circular ${" + name + "} interpolation in " + key);
            }
            String replacement = values.containsKey(name) ? values.get(name) : System.getenv(name);
            if (replacement == null) {
                replacement = "";
            }
            value = value.substring(0, m.start()) + replacement + value.substring(m.end());
        }
    }

    public boolean bool(String key) throws ConfigException {
        String v = get(key).trim().toLowerCase();
        if (v.equals("1") || v.equals("true") || v.equals("yes") || v.equals("y") || v.equals("on") || v.equals("sim")) {
            return true;
        }
        if (v.isEmpty() || v.equals("0") || v.equals("false") || v.equals("no") || v.equals("n") || v.equals("off")
                || v.equals("nao") || v.equals("não")) {
            return false;
        }
        throw new ConfigException(key + " must be true or false, got '" + v + "'");
    }

    public int intVal(String key) throws ConfigException {
        String v = get(key).trim().replace("_", "");
        if (v.isEmpty()) {
            return 0;
        }
        try {
            return Integer.parseInt(v);
        } catch (NumberFormatException e) {
            throw new ConfigException(key + " must be an integer, got '" + v + "'");
        }
    }

    public long size(String key) throws ConfigException {
        try {
            return Sizes.parse(key, get(key));
        } catch (IllegalArgumentException e) {
            throw new ConfigException(e.getMessage());
        }
    }

    public Pattern regex(String key) throws ConfigException {
        String pattern = get(key).trim();
        if (pattern.isEmpty()) {
            return null;
        }
        try {
            return Pattern.compile(pattern, Pattern.CASE_INSENSITIVE);
        } catch (Exception e) {
            throw new ConfigException(key + " is not a valid regex: " + e.getMessage());
        }
    }

    /** Resolves a path value against {@link #baseDir}. */
    public File path(String key, boolean mustExist) throws ConfigException {
        String value = get(key).trim();
        if (value.length() >= 2 && value.startsWith("\"") && value.endsWith("\"")) {
            value = value.substring(1, value.length() - 1);
        }
        if (value.isEmpty()) {
            throw new ConfigException(key + " is empty - set it in "
                    + (source != null ? source.getPath() : CONFIG_BASENAME));
        }
        for (char c : new char[]{'\t', '\n', '\r', '\f'}) {
            if (value.indexOf(c) >= 0) {
                throw new ConfigException(key + " contains a control character, which usually means a "
                        + "Windows path with single backslashes (a lone backslash-t became a TAB). "
                        + "Double the backslashes or use forward slashes.");
            }
        }
        value = expandEnv(value);
        File f = new File(value);
        if (!f.isAbsolute()) {
            f = new File(baseDir, value);
        }
        f = normalise(f);
        if (mustExist && !f.exists()) {
            throw new ConfigException(key + " does not exist: " + f);
        }
        return f;
    }

    private static File normalise(File f) {
        try {
            return f.getCanonicalFile();
        } catch (IOException e) {
            return f.getAbsoluteFile();
        }
    }

    private static String expandEnv(String value) {
        Matcher m = Pattern.compile("%([A-Za-z0-9_]+)%").matcher(value);
        StringBuffer sb = new StringBuffer();
        while (m.find()) {
            String env = System.getenv(m.group(1));
            m.appendReplacement(sb, Matcher.quoteReplacement(env != null ? env : m.group(0)));
        }
        m.appendTail(sb);
        return sb.toString();
    }

    // ---- reporting ---------------------------------------------------------

    public List<String> unknownKeys() {
        return unknownKeys;
    }

    public File baseDir() {
        return baseDir;
    }

    public File source() {
        return source;
    }

    /** Every key with secret-looking values masked - safe to print or log. */
    public Map<String, String> redacted() {
        Map<String, String> out = new TreeMap<String, String>();
        for (Map.Entry<String, String> e : values.entrySet()) {
            String v = e.getValue();
            if (SECRET_HINT.matcher(e.getKey()).find() && v != null && !v.isEmpty()) {
                v = "***";
            }
            out.put(e.getKey(), v);
        }
        return out;
    }
}
