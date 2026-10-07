package com.github.shikejing.buildorchestrationenginejava.engine.exec;

/** Machine-readable reason why a target ended in its status. */
public enum DecisionReason {
    INPUTS_UNCHANGED,
    FORCED_RERUN,
    NO_PRIOR_STATE,
    INPUTS_CHANGED,
    DEPENDENCY_CHANGED,
    OUTPUT_MISSING,
    OUTPUT_TAMPERED,
    CACHE_RESTORED,
    CACHE_MISS,
    CACHE_CORRUPT,
    ACTION_FAILED,
    TIMEOUT,
    CANCELLED_BY_FAILURE,
    DEPENDENCY_FAILED
}
