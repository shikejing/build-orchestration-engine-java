package com.github.shikejing.buildorchestrationenginejava.cache;

import java.util.Optional;

public record CacheLookupResult(
        Optional<CacheEntry> entry,
        boolean corrupted
) {
    public static CacheLookupResult missing() {
        return new CacheLookupResult(Optional.empty(), false);
    }

    public static CacheLookupResult hit(CacheEntry entry) {
        return new CacheLookupResult(Optional.of(entry), false);
    }

    public static CacheLookupResult invalid() {
        return new CacheLookupResult(Optional.empty(), true);
    }
}
