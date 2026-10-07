package com.github.shikejing.buildorchestrationenginejava.model;

import java.time.Duration;
import java.util.List;
import java.util.Map;

public record TargetDeclaration(
        String name,
        List<String> dependsOn,
        List<String> inputs,
        List<String> outputs,
        String toolchain,
        List<String> arguments,
        Map<String, String> environment,
        List<String> exclusiveResources,
        Duration timeout,
        Integer retries
) {
    public TargetDeclaration {
        dependsOn = dependsOn == null ? List.of() : List.copyOf(dependsOn);
        inputs = inputs == null ? List.of() : List.copyOf(inputs);
        outputs = outputs == null ? List.of() : List.copyOf(outputs);
        arguments = arguments == null ? List.of() : List.copyOf(arguments);
        environment = environment == null ? Map.of() : Map.copyOf(environment);
        exclusiveResources = exclusiveResources == null ? List.of() : List.copyOf(exclusiveResources);
    }
}
