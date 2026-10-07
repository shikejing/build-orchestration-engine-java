package com.github.shikejing.buildorchestrationenginejava.engine.state;

import java.util.LinkedHashMap;
import java.util.Map;

/** Persisted per-target state used for incremental checks and recovery. */
public class TargetState {
    private String name;
    private String fingerprint;
    private Map<String, String> outputHashes = new LinkedHashMap<>();
    private boolean committed;
    private long finishedAtEpochMs;

    public String getName() { return name; }
    public void setName(String name) { this.name = name; }
    public String getFingerprint() { return fingerprint; }
    public void setFingerprint(String fingerprint) { this.fingerprint = fingerprint; }
    public Map<String, String> getOutputHashes() { return outputHashes; }
    public void setOutputHashes(Map<String, String> outputHashes) { this.outputHashes = outputHashes; }
    public boolean isCommitted() { return committed; }
    public void setCommitted(boolean committed) { this.committed = committed; }
    public long getFinishedAtEpochMs() { return finishedAtEpochMs; }
    public void setFinishedAtEpochMs(long finishedAtEpochMs) { this.finishedAtEpochMs = finishedAtEpochMs; }
}
