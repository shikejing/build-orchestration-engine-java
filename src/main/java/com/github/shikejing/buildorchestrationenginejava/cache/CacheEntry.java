package com.github.shikejing.buildorchestrationenginejava.cache;

import java.time.Instant;
import java.util.Map;

public record CacheEntry(
        String key,
        String targetName,
        Map<String, String> outputHashes,
        Instant createdAt,
        String manifestChecksum
) {
}
