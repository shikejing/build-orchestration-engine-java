package com.github.shikejing.buildorchestrationenginejava.state;

import java.time.Instant;
import java.util.Map;

public record TransactionRecord(
        String targetName,
        TransactionPhase phase,
        String cacheKey,
        String stagingDirectory,
        Map<String, String> outputHashes,
        Instant updatedAt,
        String inputFingerprint,
        String outputFingerprint,
        String actionFingerprint,
        String dependencyFingerprint
) {
    public TransactionRecord {
        outputHashes = outputHashes == null ? Map.of() : Map.copyOf(outputHashes);
    }

    public TransactionRecord(String targetName, TransactionPhase phase, String cacheKey,
                             String stagingDirectory, Map<String, String> outputHashes,
                             Instant updatedAt) {
        this(targetName, phase, cacheKey, stagingDirectory, outputHashes, updatedAt,
                null, null, null, null);
    }
}
