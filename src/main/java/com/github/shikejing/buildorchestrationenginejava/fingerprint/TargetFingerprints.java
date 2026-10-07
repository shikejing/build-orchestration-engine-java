package com.github.shikejing.buildorchestrationenginejava.fingerprint;

public record TargetFingerprints(
        String inputFingerprint,
        String outputFingerprint,
        String actionFingerprint,
        String dependencyFingerprint,
        String cacheKey,
        boolean outputsPresent
) {
}
