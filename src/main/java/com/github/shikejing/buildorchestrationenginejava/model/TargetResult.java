package com.github.shikejing.buildorchestrationenginejava.model;

import java.time.Duration;

public record TargetResult(
        String targetName,
        TargetStatus status,
        DecisionReason reason,
        Duration duration,
        int attempts,
        boolean cacheRestored,
        String outputFingerprint,
        String errorMessage
) {
}
