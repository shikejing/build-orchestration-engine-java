package com.github.shikejing.buildorchestrationenginejava.state;

import java.time.Instant;

public record TargetStateRecord(
        String targetName,
        String inputFingerprint,
        String outputFingerprint,
        String actionFingerprint,
        String dependencyFingerprint,
        String cacheKey,
        Instant completedAt
) {
}
