package com.github.shikejing.buildorchestrationenginejava.execution;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

public class WriteTextToolchain implements Toolchain {
    @Override
    public String name() {
        return "write-text";
    }

    @Override
    public void execute(TaskExecutionContext context) throws Exception {
        for (String output : context.outputs()) {
            Path target = context.stagingDirectory().resolve(output).normalize();
            Files.createDirectories(target.getParent());
            String content = context.environment().getOrDefault("CONTENT", String.join("\n", context.arguments()));
            Files.writeString(target, content, StandardCharsets.UTF_8);
        }
    }
}
