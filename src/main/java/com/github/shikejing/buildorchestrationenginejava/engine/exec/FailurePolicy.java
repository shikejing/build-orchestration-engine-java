package com.github.shikejing.buildorchestrationenginejava.engine.exec;

/** What to do with remaining targets when one fails. */
public enum FailurePolicy {
    CANCEL_REMAINING,
    CONTINUE
}
