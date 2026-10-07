package com.github.shikejing.buildorchestrationenginejava.fingerprint;

public record FileFingerprint(
        String relativePath,
        String sha256,
        long size
) {
}
