package com.github.shikejing.buildorchestrationenginejava.execution;

import com.github.shikejing.buildorchestrationenginejava.model.DecisionReason;

public record IncrementalDecision(
        boolean execute,
        DecisionReason reason
) {
}
