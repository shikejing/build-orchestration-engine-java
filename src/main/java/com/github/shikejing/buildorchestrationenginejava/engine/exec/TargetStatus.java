package com.github.shikejing.buildorchestrationenginejava.engine.exec;

public enum TargetStatus {
    UP_TO_DATE,   // skipped: fingerprint + outputs unchanged
    CACHE_HIT,    // restored from verified cache
    EXECUTED,     // actually ran the action
    FAILED,       // ran (or timed out) and failed
    CANCELLED     // not run because of failure policy / interruption
}
