package com.github.shikejing.buildorchestrationenginejava.engine.cache;

import java.util.LinkedHashMap;
import java.util.Map;

/** A cache record: which outputs (path -> sha256) a key produced. */
public class CacheEntry {
    private String key;
    private Map<String, String> outputHashes = new LinkedHashMap<>();
    private long createdAtEpochMs;

    public String getKey() { return key; }
    public void setKey(String key) { this.key = key; }
    public Map<String, String> getOutputHashes() { return outputHashes; }
    public void setOutputHashes(Map<String, String> outputHashes) { this.outputHashes = outputHashes; }
    public long getCreatedAtEpochMs() { return createdAtEpochMs; }
    public void setCreatedAtEpochMs(long createdAtEpochMs) { this.createdAtEpochMs = createdAtEpochMs; }
}
