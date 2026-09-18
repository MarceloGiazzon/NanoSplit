package com.nanosplit.index;

import java.io.File;
import java.io.FileNotFoundException;
import java.io.IOException;
import java.nio.file.Files;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.List;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.nanosplit.util.AtomicFiles;

/** In-memory view over {@code index.json}, with load/save and part lookups. */
public final class Index {

    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().disableHtmlEscaping().create();

    public final IndexData data;
    private File path;

    public Index(IndexData data, File path) {
        this.data = data;
        this.path = path;
    }

    public static String nowIso() {
        return OffsetDateTime.now().withNano(0).toString();
    }

    public static Index load(File path) throws IOException {
        if (!path.isFile()) {
            throw new FileNotFoundException("index not found: " + path + " - run 'nanosplit split' first");
        }
        String json = new String(Files.readAllBytes(path.toPath()), java.nio.charset.StandardCharsets.UTF_8);
        IndexData data = GSON.fromJson(json, IndexData.class);
        if (data.formatVersion != IndexData.FORMAT_VERSION) {
            throw new IOException("index format " + data.formatVersion + " is not supported by this version");
        }
        return new Index(data, path);
    }

    public File save(File target) throws IOException {
        File dest = target != null ? target : path;
        if (dest == null) {
            throw new IllegalStateException("no path to save the index to");
        }
        AtomicFiles.writeUtf8(dest.toPath(), GSON.toJson(data));
        this.path = dest;
        return dest;
    }

    public File save() throws IOException {
        return save(null);
    }

    public File path() {
        return path;
    }

    public List<PartEntry> parts() {
        return data.parts;
    }

    public PartEntry part(int number) {
        for (PartEntry p : data.parts) {
            if (p.n == number) {
                return p;
            }
        }
        throw new java.util.NoSuchElementException("part " + number + " is not in the index");
    }

    public File partPath(PartEntry entry) {
        File base = path != null ? path.getParentFile() : new File(".");
        return new File(base, entry.file);
    }

    /** Parts within an inclusive 1-based range; 0 means open-ended. */
    public List<PartEntry> select(int first, int last) {
        List<PartEntry> out = new ArrayList<PartEntry>();
        for (PartEntry p : data.parts) {
            if (first > 0 && p.n < first) {
                continue;
            }
            if (last > 0 && p.n > last) {
                continue;
            }
            out.add(p);
        }
        return out;
    }
}
