package com.github.shikejing.buildorchestrationenginejava.model;

import java.util.List;

public record BuildDescription(
        String name,
        List<TargetDeclaration> targets
) {
    public BuildDescription {
        targets = targets == null ? List.of() : List.copyOf(targets);
    }
}
