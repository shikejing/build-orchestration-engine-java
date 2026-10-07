package com.github.shikejing.buildorchestrationenginejava;

import com.github.shikejing.buildorchestrationenginejava.config.CacheRetention;
import com.github.shikejing.buildorchestrationenginejava.config.EngineConfig;
import com.github.shikejing.buildorchestrationenginejava.execution.BuildEngine;
import com.github.shikejing.buildorchestrationenginejava.execution.TaskExecutionContext;
import com.github.shikejing.buildorchestrationenginejava.execution.Toolchain;
import com.github.shikejing.buildorchestrationenginejava.model.BuildDescription;
import com.github.shikejing.buildorchestrationenginejava.model.TargetDeclaration;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import static org.junit.jupiter.api.Assertions.assertEquals;

class CacheRetentionTest {
    @TempDir
    Path workspace;

    @Test
    void retainsAtMostConfiguredNumberOfEntries() throws Exception {
        Toolchain writer = new Toolchain() {
            @Override
            public String name() {
                return "writer";
            }

            @Override
            public void execute(TaskExecutionContext context) throws Exception {
                Path output = context.stagingDirectory().resolve(context.outputs().get(0));
                Files.createDirectories(output.getParent());
                Files.writeString(output, context.targetName(), StandardCharsets.UTF_8);
            }
        };
        BuildDescription description = new BuildDescription("retention", List.of("a", "b", "c").stream()
                .map(name -> new TargetDeclaration(name, List.of(), List.of(), List.of(name + ".txt"),
                        "writer", List.of(name), Map.of(), List.of(), null, null))
                .toList());
        EngineConfig config = EngineConfig.builder()
                .cacheEnabled(true)
                .retention(new CacheRetention(1L, Duration.ofDays(1)))
                .build();
        try (BuildEngine engine = BuildEngine.builder().workspace(workspace).config(config).toolchain(writer).build()) {
            engine.run(description);
        }
        Path entries = workspace.resolve(".build-engine/cache/entries");
        try (Stream<Path> stream = Files.list(entries)) {
            assertEquals(1, stream.filter(Files::isDirectory).count());
        }
    }
}
