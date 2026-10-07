package com.github.shikejing.buildorchestrationenginejava.execution;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;

public class CopyFileToolchain implements Toolchain {
    @Override
    public String name() {
        return "copy";
    }

    @Override
    public void execute(TaskExecutionContext context) throws Exception {
        if (context.inputs().size() != 1 || context.outputs().size() != 1) {
            throw new IllegalArgumentException("copy toolchain requires exactly one input and one output");
        }
        Path input = context.workspace().resolve(context.inputs().get(0)).normalize();
        Path output = context.stagingDirectory().resolve(context.outputs().get(0)).normalize();
        Files.createDirectories(output.getParent());
        if (!Files.isRegularFile(input, LinkOption.NOFOLLOW_LINKS)) {
            throw new IOException("Input is missing or not a regular file: " + context.inputs().get(0));
        }
        Files.copy(input, output, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.COPY_ATTRIBUTES);
    }
}
