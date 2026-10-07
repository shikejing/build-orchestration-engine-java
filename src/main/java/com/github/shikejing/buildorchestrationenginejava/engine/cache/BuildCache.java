package com.github.shikejing.buildorchestrationenginejava.engine.cache;

import java.io.IOException;
import java.nio.file.Path;
import java.util.Map;

/** Content-addressed cache with verifiable keys. */
public interface BuildCache {

    /** Store outputs under a key. */
    void put(String key, Map<String, Path> outputs) throws IOException;

    /**
     * Look up a key. Implementations must verify integrity of stored blobs
     * against the recorded hashes and report CORRUPT instead of serving
     * bad data, so callers fall back to re-execution.
     */
    CacheLookup lookup(String key);

    /** Restore cached outputs into the given dir; verifies hashes after restore. */
    boolean restore(CacheEntry entry, Path intoDir) throws IOException;

    /** Apply retention: keep at most maxEntries newest entries. */
    void evictToSize(int maxEntries) throws IOException;
}
