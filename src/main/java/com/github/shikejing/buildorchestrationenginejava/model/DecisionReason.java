package com.github.shikejing.buildorchestrationenginejava.model;

public enum DecisionReason {
    NONE,
    NO_PREVIOUS_STATE,
    INPUT_CHANGED,
    OUTPUT_MISSING,
    OUTPUT_MODIFIED,
    DEPENDENCY_CHANGED,
    ACTION_CHANGED,
    FORCED_REBUILD,
    UP_TO_DATE,
    CACHE_HIT,
    CORRUPT_CACHE_FALLBACK,
    DEPENDENCY_FAILED,
    CANCELLED,
    INTERRUPTED,
    RECOVERED_PUBLISHED
}
