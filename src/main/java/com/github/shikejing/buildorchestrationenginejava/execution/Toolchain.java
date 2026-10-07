package com.github.shikejing.buildorchestrationenginejava.execution;

public interface Toolchain {
    String name();

    void execute(TaskExecutionContext context) throws Exception;
}
