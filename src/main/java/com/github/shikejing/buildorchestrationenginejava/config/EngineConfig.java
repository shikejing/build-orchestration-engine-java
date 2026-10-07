package com.github.shikejing.buildorchestrationenginejava.config;

import java.time.Duration;

public record EngineConfig(
        int concurrency,
        boolean cacheEnabled,
        CacheRetention retention,
        Duration defaultTimeout,
        int defaultRetries,
        FailureStrategy failureStrategy,
        boolean forceRebuild
) {
    public EngineConfig {
        if (concurrency < 1) {
            throw new IllegalArgumentException("concurrency must be at least 1");
        }
        if (defaultRetries < 0) {
            throw new IllegalArgumentException("defaultRetries must not be negative");
        }
        if (defaultTimeout != null && defaultTimeout.isNegative()) {
            throw new IllegalArgumentException("defaultTimeout must not be negative");
        }
        if (retention == null) {
            retention = CacheRetention.keepAll();
        }
        if (failureStrategy == null) {
            failureStrategy = FailureStrategy.CANCEL;
        }
    }

    public static EngineConfig defaults() {
        return new EngineConfig(1, true, CacheRetention.keepAll(), null, 0,
                FailureStrategy.CANCEL, false);
    }

    public static Builder builder() {
        return new Builder();
    }

    public static class Builder {
        private int concurrency = 1;
        private boolean cacheEnabled = true;
        private CacheRetention retention = CacheRetention.keepAll();
        private Duration defaultTimeout;
        private int defaultRetries;
        private FailureStrategy failureStrategy = FailureStrategy.CANCEL;
        private boolean forceRebuild;

        public Builder concurrency(int concurrency) {
            this.concurrency = concurrency;
            return this;
        }

        public Builder cacheEnabled(boolean cacheEnabled) {
            this.cacheEnabled = cacheEnabled;
            return this;
        }

        public Builder retention(CacheRetention retention) {
            this.retention = retention;
            return this;
        }

        public Builder defaultTimeout(Duration defaultTimeout) {
            this.defaultTimeout = defaultTimeout;
            return this;
        }

        public Builder defaultRetries(int defaultRetries) {
            this.defaultRetries = defaultRetries;
            return this;
        }

        public Builder failureStrategy(FailureStrategy failureStrategy) {
            this.failureStrategy = failureStrategy;
            return this;
        }

        public Builder forceRebuild(boolean forceRebuild) {
            this.forceRebuild = forceRebuild;
            return this;
        }

        public EngineConfig build() {
            return new EngineConfig(concurrency, cacheEnabled, retention, defaultTimeout,
                    defaultRetries, failureStrategy, forceRebuild);
        }
    }
}
