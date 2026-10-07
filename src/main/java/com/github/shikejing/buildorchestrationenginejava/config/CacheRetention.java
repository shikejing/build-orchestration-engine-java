package com.github.shikejing.buildorchestrationenginejava.config;

import java.time.Duration;

public record CacheRetention(
        Long maxEntries,
        Duration maxAge
) {
    public static CacheRetention keepAll() {
        return new CacheRetention(null, null);
    }
}
