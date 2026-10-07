package com.github.shikejing.buildorchestrationenginejava;

import com.github.shikejing.buildorchestrationenginejava.config.EngineConfig;
import com.github.shikejing.buildorchestrationenginejava.execution.BuildEngine;
import com.github.shikejing.buildorchestrationenginejava.execution.TaskExecutionContext;
import com.github.shikejing.buildorchestrationenginejava.execution.Toolchain;
import com.github.shikejing.buildorchestrationenginejava.model.BuildDescription;
import com.github.shikejing.buildorchestrationenginejava.model.BuildResult;
import com.github.shikejing.buildorchestrationenginejava.model.TargetDeclaration;
import com.github.shikejing.buildorchestrationenginejava.model.TargetStatus;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class DeterministicOrderTest {
    @TempDir
    Path workspace;

    @Test
    void reportsIndependentTargetsInDeclarationOrderAtHighConcurrency() throws Exception {
        List<String> startOrder = Collections.synchronizedList(new ArrayList<>());
        Toolchain slow = new Toolchain() {
            @Override
            public String name() {
                return "slow";
            }

            @Override
            public void execute(TaskExecutionContext context) throws Exception {
                synchronized (startOrder) {
                    startOrder.add(context.targetName());
                }
                Thread.sleep(100);
                Path output = context.stagingDirectory().resolve(context.outputs().get(0));
                Files.createDirectories(output.getParent());
                Files.writeString(output, context.targetName(), StandardCharsets.UTF_8);
            }
        };
        List<TargetDeclaration> targets = List.of("z", "a", "m", "b").stream()
                .map(name -> new TargetDeclaration(name, List.of(), List.of(), List.of(name + ".out"),
                        "slow", List.of(), Map.of(), List.of(), null, null))
                .toList();
        EngineConfig config = EngineConfig.builder().concurrency(4).build();
        try (BuildEngine engine = BuildEngine.builder()
                .workspace(workspace)
                .config(config)
                .toolchain(slow)
                .build()) {
            BuildResult result = engine.run(new BuildDescription("order", targets));
            assertTrue(result.success());
            assertEquals(List.of("z", "a", "m", "b"), result.targets().stream()
                    .map(target -> target.targetName())
                    .toList());
            assertEquals(4, startOrder.size());
        }
    }

    @Test
    void dependencyIsAlwaysStartedBeforeDependent() throws Exception {
        List<String> order = Collections.synchronizedList(new ArrayList<>());
        Toolchain recorded = new Toolchain() {
            @Override
            public String name() {
                return "recorded";
            }

            @Override
            public void execute(TaskExecutionContext context) throws Exception {
                order.add(context.targetName());
                Path output = context.stagingDirectory().resolve(context.outputs().get(0));
                Files.createDirectories(output.getParent());
                Files.writeString(output, context.targetName(), StandardCharsets.UTF_8);
            }
        };
        BuildDescription description = new BuildDescription("deps", List.of(
                target(recorded.name(), "app", List.of("lib"), "app.out"),
                target(recorded.name(), "test", List.of("app"), "test.out"),
                target(recorded.name(), "lib", List.of(), "lib.out")
        ));
        try (BuildEngine engine = BuildEngine.builder().workspace(workspace).toolchain(recorded).build()) {
            BuildResult result = engine.run(description);
            assertTrue(result.success());
        }
        assertEquals(List.of("lib", "app", "test"), order);
    }

    private static TargetDeclaration target(String toolchain, String name, List<String> deps, String output) {
        return new TargetDeclaration(name, deps, List.of(), List.of(output),
                toolchain, List.of(), Map.of(), List.of(), null, null);
    }
}
