package com.nanosplit.index;

import java.util.LinkedHashMap;

/** Root object of {@code nanosplit-state.json}. */
public final class StateData {
    public String tool = "NanoSplit";
    public int formatVersion = IndexData.FORMAT_VERSION;
    public String createdAt;
    public String updatedAt;
    public IndexRef index = new IndexRef();
    // Keys are part numbers as strings (JSON object keys must be strings).
    // Declared as a concrete LinkedHashMap, not the Map interface, so Gson's
    // deserializer preserves insertion order instead of falling back to its
    // sorted-by-key LinkedTreeMap.
    public LinkedHashMap<String, PartState> parts = new LinkedHashMap<String, PartState>();

    public static final class IndexRef {
        public String file;
        public int parts;
        public String createdAt;
    }
}
