package com.nanosplit.sql;

import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.Charset;
import java.util.HashMap;
import java.util.Map;

/**
 * Byte-order-mark sniffing plus the fixed-width-encoding table that lets a
 * character offset be turned into a byte offset without re-reading the file.
 *
 * <p>Encoding labels here are always the explicit little/big-endian charset
 * name (e.g. {@code UTF-16LE}), never the generic {@code UTF-16} - Java's
 * generic UTF-16 encoder always writes a big-endian BOM regardless of what
 * order the source used, which would silently flip a little-endian source's
 * byte order on every part. Whether a BOM should be written is tracked
 * separately in {@link Detected#bom}, and {@link com.nanosplit.split.PartWriter}
 * writes it itself before handing bytes to the explicit-order encoder.
 */
public final class Encodings {

    /** Bytes per character for encodings where that is a constant. UTF-8 is
     *  deliberately absent: it is variable width. */
    private static final Map<String, Integer> FIXED_WIDTH = new HashMap<String, Integer>();
    static {
        FIXED_WIDTH.put("UTF-16LE", 2);
        FIXED_WIDTH.put("UTF-16BE", 2);
        FIXED_WIDTH.put("UTF-32LE", 4);
        FIXED_WIDTH.put("UTF-32BE", 4);
        FIXED_WIDTH.put("US-ASCII", 1);
        FIXED_WIDTH.put("ISO-8859-1", 1);
        FIXED_WIDTH.put("WINDOWS-1252", 1);
        FIXED_WIDTH.put("CP1252", 1);
        FIXED_WIDTH.put("CP850", 1);
        FIXED_WIDTH.put("CP1251", 1);
    }

    public static final class Detected {
        public final String charset; // explicit Java charset name, ready for Charset.forName
        public final boolean bom;    // whether a byte-order mark should be read/written
        public final int bomBytes;   // its size in bytes (0 if none)

        Detected(String charset, boolean bom, int bomBytes) {
            this.charset = charset;
            this.bom = bom;
            this.bomBytes = bomBytes;
        }
    }

    private Encodings() {
    }

    public static Integer bytesPerChar(String encoding) {
        return FIXED_WIDTH.get(encoding.toUpperCase());
    }

    public static Charset toCharset(String encoding) {
        return Charset.forName(encoding);
    }

    /** BOM bytes to write for a given (charset, bom) pair, or an empty array. */
    public static byte[] bomBytes(String charset, boolean bom) {
        if (!bom) {
            return new byte[0];
        }
        switch (charset.toUpperCase()) {
            case "UTF-16LE": return new byte[]{(byte) 0xFF, (byte) 0xFE};
            case "UTF-16BE": return new byte[]{(byte) 0xFE, (byte) 0xFF};
            case "UTF-32LE": return new byte[]{(byte) 0xFF, (byte) 0xFE, 0x00, 0x00};
            case "UTF-32BE": return new byte[]{0x00, 0x00, (byte) 0xFE, (byte) 0xFF};
            case "UTF-8": return new byte[]{(byte) 0xEF, (byte) 0xBB, (byte) 0xBF};
            default: return new byte[0];
        }
    }

    /**
     * Honours an explicit encoding (accepting the common spellings, e.g.
     * {@code utf-16-le}, {@code utf16}); otherwise sniffs the byte-order mark,
     * then falls back to a NUL-byte heuristic for BOM-less UTF-16.
     */
    public static Detected detect(File file, String declared) throws IOException {
        if (declared != null && !declared.trim().isEmpty() && !declared.trim().equalsIgnoreCase("auto")) {
            return fromDeclared(declared.trim());
        }
        byte[] head = new byte[4096];
        int n;
        try (InputStream in = new FileInputStream(file)) {
            n = in.read(head);
        }
        if (n < 0) {
            n = 0;
        }
        if (n >= 4 && (head[0] & 0xFF) == 0xFF && (head[1] & 0xFF) == 0xFE
                && (head[2] & 0xFF) == 0x00 && (head[3] & 0xFF) == 0x00) {
            return new Detected("UTF-32LE", true, 4);
        }
        if (n >= 4 && (head[0] & 0xFF) == 0x00 && (head[1] & 0xFF) == 0x00
                && (head[2] & 0xFF) == 0xFE && (head[3] & 0xFF) == 0xFF) {
            return new Detected("UTF-32BE", true, 4);
        }
        if (n >= 2 && (head[0] & 0xFF) == 0xFF && (head[1] & 0xFF) == 0xFE) {
            return new Detected("UTF-16LE", true, 2);
        }
        if (n >= 2 && (head[0] & 0xFF) == 0xFE && (head[1] & 0xFF) == 0xFF) {
            return new Detected("UTF-16BE", true, 2);
        }
        if (n >= 3 && (head[0] & 0xFF) == 0xEF && (head[1] & 0xFF) == 0xBB && (head[2] & 0xFF) == 0xBF) {
            return new Detected("UTF-8", true, 3);
        }
        int zeros = 0;
        for (int i = 0; i < n; i++) {
            if (head[i] == 0) {
                zeros++;
            }
        }
        if (n > 0 && zeros > n / 3) {
            boolean littleEndian = n >= 2 && head[1] == 0;
            return new Detected(littleEndian ? "UTF-16LE" : "UTF-16BE", false, 0);
        }
        return new Detected("UTF-8", false, 0);
    }

    private static Detected fromDeclared(String declared) {
        String norm = declared.toUpperCase().replace("_", "-");
        if (norm.equals("UTF-16") || norm.equals("UTF16")) {
            norm = "UTF-16LE"; // Windows/SSMS default; explicit is safer than Java's generic BE-with-BOM
        }
        if (norm.equals("UTF-32") || norm.equals("UTF32")) {
            norm = "UTF-32LE";
        }
        boolean bom = norm.startsWith("UTF-16") || norm.startsWith("UTF-32") || norm.equals("UTF-8-BOM");
        if (norm.equals("UTF-8-BOM")) {
            norm = "UTF-8";
        }
        int bomLen = norm.startsWith("UTF-32") ? 4 : (norm.startsWith("UTF-16") ? 2 : (bom ? 3 : 0));
        return new Detected(norm, bom, bom ? bomLen : 0);
    }
}
