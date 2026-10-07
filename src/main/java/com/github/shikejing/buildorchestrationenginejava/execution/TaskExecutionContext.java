package com.github.shikejing.buildorchestrationenginejava.execution;

import java.nio.file.Path;
import java.util.List;
import java.util.Map;

public interface TaskExecutionContext {
    String targetName();

    Path workspace();

    Path stagingDirectory();

    List<String> inputs();

    List<String> outputs();

    List<String> arguments();

    Map<String, String> environment();
}
