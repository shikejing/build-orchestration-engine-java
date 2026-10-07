package com.github.shikejing.buildorchestrationenginejava.engine.spec;

import java.util.List;

/** Raised when a build spec is invalid; carries all detected problems. */
public class SpecException extends RuntimeException {
    private final List<String> problems;

    public SpecException(List<String> problems) {
        super(String.join("; ", problems));
        this.problems = List.copyOf(problems);
    }

    public List<String> getProblems() { return problems; }
}
