package com.github.shikejing.buildorchestrationenginejava;

import com.github.shikejing.buildorchestrationenginejava.config.EngineConfig;
import com.github.shikejing.buildorchestrationenginejava.config.FailureStrategy;
import com.github.shikejing.buildorchestrationenginejava.execution.BuildEngine;
import com.github.shikejing.buildorchestrationenginejava.execution.TaskExecutionContext;
import com.github.shikejing.buildorchestrationenginejava.execution.Toolchain;
import com.github.shikejing.buildorchestrationenginejava.model.BuildDescription;
import com.github.shikejing.buildorchestrationenginejava.model.BuildResult;
import com.github.shikejing.buildorchestrationenginejava.model.DecisionReason;
import com.github.shikejing.buildorchestrationenginejava.model.TargetDeclaration;
import com.github.shikejing.buildorchestrationenginejava.model.TargetStatus;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class FailurePolicyTest {
    @TempDir
    Path workspace;

    @Test
    void cancelStrategyLeavesPendingIndependentTargetsCancelledAndNoPartialOutput() throws Exception {
        Toolchain controlled = new Toolchain() {
            @Override
            public String name() {
                return "controlled";
            }

            @Override
            public void execute(TaskExecutionContext context) throws Exception {
                if (context.targetName().equals("fail")) {
                    throw new IllegalStateException("intentional failure");
                }
                Path output = context.stagingDirectory().resolve(context.outputs().get(0));
                Files.createDirectories(output.getParent());
                Files.writeString(output, context.targetName(), StandardCharsets.UTF_8);
            }
        };
        BuildDescription description = description("fail", "later");
        EngineConfig config = EngineConfig.builder()
                .concurrency(1)
                .failureStrategy(FailureStrategy.CANCEL)
                .build();
        try (BuildEngine engine = BuildEngine.builder().workspace(workspace).config(config).toolchain(controlled).build()) {
            BuildResult result = engine.run(description);
            assertFalse(result.success());
            assertTrue(result.target("fail").errorMessage().contains("intentional failure"));
        }
        assertFalse(Files.exists(workspace.resolve("fail.txt")));
        assertFalse(Files.exists(workspace.resolve("later.txt")));
    }

    @Test
    void continueStrategyRunsIndependentTargetsAndSkipsDependents() throws Exception {
        Toolchain failingOrWriting = new Toolchain() {
            @Override
            public String name() {
                return "mixed";
            }

            @Override
            public void execute(TaskExecutionContext context) throws Exception {
                if (context.targetName().equals("bad")) {
                    throw new IllegalStateException("intentional failure");
                }
                Path output = context.stagingDirectory().resolve(context.outputs().get(0));
                Files.createDirectories(output.getParent());
                Files.writeString(output, context.targetName(), StandardCharsets.UTF_8);
            }
        };
        BuildDescription description = new BuildDescription("continue", List.of(
                new TargetDeclaration("bad", List.of(), List.of(), List.of("bad.txt"),
                        "mixed", List.of(), Map.of(), List.of(), null, null),
                new TargetDeclaration("independent", List.of(), List.of(), List.of("independent.txt"),
                        "mixed", List.of(), Map.of(), List.of(), null, null),
                new TargetDeclaration("dependent", List.of("bad"), List.of(), List.of("dependent.txt"),
                        "mixed", List.of(), Map.of(), List.of(), null, null)
        ));
        EngineConfig config = EngineConfig.builder()
                .concurrency(1)
                .failureStrategy(FailureStrategy.CONTINUE)
                .build();
        try (BuildEngine engine = BuildEngine.builder().workspace(workspace).config(config).toolchain(failingOrWriting).build()) {
            BuildResult result = engine.run(description);
            assertFalse(result.success());
            assertTrue(result.target("independent").status() == TargetStatus.SUCCESS);
            assertTrue(result.target("dependent").status() == TargetStatus.SKIPPED);
            assertTrue(result.target("dependent").reason() == DecisionReason.DEPENDENCY_FAILED);
            assertFalse(Files.exists(workspace.resolve("bad.txt")));
            assertTrue(Files.exists(workspace.resolve("independent.txt")));
        }
    }

    private BuildDescription description(String first, String second) {
        return new BuildDescription("failure", List.of(
                new TargetDeclaration(first, List.of(), List.of(), List.of(first + ".txt"),
                        "controlled", List.of(), Map.of(), List.of(), null, null),
                new TargetDeclaration(second, List.of(), List.of(), List.of(second + ".txt"),
                        "controlled", List.of(), Map.of(), List.of(), null, null)
        ));
    }
}
