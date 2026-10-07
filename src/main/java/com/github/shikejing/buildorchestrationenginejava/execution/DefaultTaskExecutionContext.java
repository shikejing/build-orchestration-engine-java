package com.github.shikejing.buildorchestrationenginejava.execution;

import java.nio.file.Path;
import java.util.List;
import java.util.Map;

record DefaultTaskExecutionContext(
        String targetName,
        Path workspace,
        Path stagingDirectory,
        List<String> inputs,
        List<String> outputs,
        List<String> arguments,
        Map<String, String> environment
) implements TaskExecutionContext {
    DefaultTaskExecutionContext {
        inputs = List.copyOf(inputs);
        outputs = List.copyOf(outputs);
        arguments = List.copyOf(arguments);
        environment = Map.copyOf(environment);
    }
}
