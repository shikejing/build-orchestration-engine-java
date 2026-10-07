package com.github.shikejing.buildorchestrationenginejava.cache;

import com.github.shikejing.buildorchestrationenginejava.config.CacheRetention;
import java.nio.file.Path;
import java.util.List;
import java.util.Optional;

public interface ArtifactCache {
    CacheLookupResult inspect(CacheKey key, String targetName);

    default Optional<CacheEntry> find(CacheKey key, String targetName) {
        return inspect(key, targetName).entry();
    }

    void store(CacheKey key, String targetName, Path stagingRoot, List<String> relativeOutputs);

    void restore(CacheEntry entry, Path destinationRoot);

    void prune(CacheRetention retention);
}
