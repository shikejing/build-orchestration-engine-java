package com.github.shikejing.buildorchestrationenginejava.model;

import java.util.List;

public record BuildResult(
        boolean success,
        boolean interrupted,
        List<TargetResult> targets
) {
    public BuildResult {
        targets = targets == null ? List.of() : List.copyOf(targets);
    }

    public TargetResult target(String name) {
        return targets.stream()
                .filter(result -> result.targetName().equals(name))
                .findFirst()
                .orElse(null);
    }
}
