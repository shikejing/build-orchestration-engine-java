package com.github.shikejing.buildorchestrationenginejava;

import com.github.shikejing.buildorchestrationenginejava.execution.BuildEngine;
import com.github.shikejing.buildorchestrationenginejava.model.BuildResult;
import com.github.shikejing.buildorchestrationenginejava.model.TargetStatus;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import static org.junit.jupiter.api.Assertions.assertTrue;

class JsonDescriptionFileTest {
    @TempDir
    Path workspace;

    @Test
    void parsesBuildDescriptionFromLocalFile() throws Exception {
        Files.writeString(workspace.resolve("build.json"), """
                {
                  "name": "from-file",
                  "targets": [
                    {
                      "name": "generate",
                      "toolchain": "write-text",
                      "arguments": ["hello-file"],
                      "outputs": ["generated.txt"]
                    }
                  ]
                }
                """, StandardCharsets.UTF_8);
        try (BuildEngine engine = BuildEngine.builder().workspace(workspace).build()) {
            BuildResult result = engine.run(workspace.resolve("build.json"));
            assertTrue(result.success());
            assertTrue(result.target("generate").status() == TargetStatus.SUCCESS);
            assertTrue(Files.readString(workspace.resolve("generated.txt"), StandardCharsets.UTF_8)
                    .contains("hello-file"));
        }
    }
}
