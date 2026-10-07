package com.github.shikejing.buildorchestrationenginejava.cache;

import com.github.shikejing.buildorchestrationenginejava.fingerprint.FingerprintService;
import com.github.shikejing.buildorchestrationenginejava.fingerprint.TargetFingerprints;
import com.github.shikejing.buildorchestrationenginejava.model.TargetDeclaration;
import java.util.Map;
import java.util.TreeMap;

public class CacheKeyFactory {
    public static final String SCHEMA_VERSION = "build-cache-v1";

    private final FingerprintService fingerprints;

    public CacheKeyFactory(FingerprintService fingerprints) {
        if (fingerprints == null) {
            throw new IllegalArgumentException("fingerprints must not be null");
        }
        this.fingerprints = fingerprints;
    }

    public CacheKey create(TargetDeclaration target, TargetFingerprints fingerprints) {
        if (target == null || fingerprints == null) {
            throw new IllegalArgumentException("target and fingerprints must not be null");
        }
        Map<String, String> components = new TreeMap<>();
        components.put("schema", SCHEMA_VERSION);
        components.put("target", target.name());
        components.put("inputs", fingerprints.inputFingerprint());
        components.put("action", fingerprints.actionFingerprint());
        components.put("dependencies", fingerprints.dependencyFingerprint());
        components.put("toolchain", nullToEmpty(target.toolchain()));
        components.put("arguments", String.join("\u0000", target.arguments()));
        components.put("environment", mapDigest(target));
        components.put("outputs", this.fingerprints.hashText(String.join("\u0000", target.outputs())));

        StringBuilder canonical = new StringBuilder();
        components.forEach((key, value) -> canonical
                .append(key)
                .append('=')
                .append(value == null ? "" : value)
                .append('\n'));
        return new CacheKey(this.fingerprints.hashText(canonical.toString()), Map.copyOf(components));
    }

    private String mapDigest(TargetDeclaration target) {
        Map<String, String> sorted = new TreeMap<>();
        if (target.environment() != null) {
            sorted.putAll(target.environment());
        }
        StringBuilder canonical = new StringBuilder();
        sorted.forEach((key, value) -> canonical
                .append(key)
                .append('=')
                .append(value == null ? "" : value)
                .append('\n'));
        return fingerprints.hashText(canonical.toString());
    }

    private String nullToEmpty(String value) {
        return value == null ? "" : value;
    }
}
