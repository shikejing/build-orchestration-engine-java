package com.github.shikejing.buildorchestrationenginejava.engine.cache;

/** Result of a cache lookup; corruption is reported, never served silently. */
public record CacheLookup(Status status, CacheEntry entry) {

    public enum Status { HIT, MISS, CORRUPT }

    public static CacheLookup hit(CacheEntry entry) {
        return new CacheLookup(Status.HIT, entry);
    }

    public static CacheLookup miss() {
        return new CacheLookup(Status.MISS, null);
    }

    public static CacheLookup corrupt() {
        return new CacheLookup(Status.CORRUPT, null);
    }
}
