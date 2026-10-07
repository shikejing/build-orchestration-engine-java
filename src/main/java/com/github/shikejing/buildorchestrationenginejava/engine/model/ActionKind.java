package com.github.shikejing.buildorchestrationenginejava.engine.model;

/** Kinds of build actions supported by the engine (all simulated locally). */
public enum ActionKind {
    WRITE,
    CONCAT,
    COPY,
    SLEEP,
    APPEND,
    FAIL
}
