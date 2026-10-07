package com.github.shikejing.buildorchestrationenginejava.graph;

import java.util.List;

public record ExecutionPlan(
        List<String> targetNamesInOrder
) {
}
