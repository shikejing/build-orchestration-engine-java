package com.github.shikejing.buildorchestrationenginejava.state;

import java.util.Map;

public record ExecutionState(
        Map<String, TargetStateRecord> targets
) {
    public ExecutionState {
        targets = targets == null ? Map.of() : Map.copyOf(targets);
    }
}
