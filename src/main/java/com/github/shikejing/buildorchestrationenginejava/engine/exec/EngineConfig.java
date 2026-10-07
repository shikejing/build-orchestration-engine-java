package com.github.shikejing.buildorchestrationenginejava.engine.exec;

/** Runtime configuration; a new config only affects subsequent runs. */
public class EngineConfig {
    private int parallelism = 1;
    private boolean cacheEnabled = true;
    private int cacheMaxEntries = 128;
    private long defaultTimeoutMs = 60_000;
    private int defaultRetries = 0;
    private FailurePolicy failurePolicy = FailurePolicy.CANCEL_REMAINING;

    public int getParallelism() { return parallelism; }
    public EngineConfig setParallelism(int parallelism) { this.parallelism = parallelism; return this; }
    public boolean isCacheEnabled() { return cacheEnabled; }
    public EngineConfig setCacheEnabled(boolean cacheEnabled) { this.cacheEnabled = cacheEnabled; return this; }
    public int getCacheMaxEntries() { return cacheMaxEntries; }
    public EngineConfig setCacheMaxEntries(int cacheMaxEntries) { this.cacheMaxEntries = cacheMaxEntries; return this; }
    public long getDefaultTimeoutMs() { return defaultTimeoutMs; }
    public EngineConfig setDefaultTimeoutMs(long defaultTimeoutMs) { this.defaultTimeoutMs = defaultTimeoutMs; return this; }
    public int getDefaultRetries() { return defaultRetries; }
    public EngineConfig setDefaultRetries(int defaultRetries) { this.defaultRetries = defaultRetries; return this; }
    public FailurePolicy getFailurePolicy() { return failurePolicy; }
    public EngineConfig setFailurePolicy(FailurePolicy failurePolicy) { this.failurePolicy = failurePolicy; return this; }
}
