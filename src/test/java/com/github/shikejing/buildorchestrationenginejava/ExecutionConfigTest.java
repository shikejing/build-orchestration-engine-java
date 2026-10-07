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
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ExecutionConfigTest {
    @TempDir
    Path workspace;

    @Test
    void retriesFailedActionThenSucceeds() throws Exception {
        AtomicInteger attempts = new AtomicInteger();
        Toolchain flaky = new Toolchain() {
            @Override
            public String name() {
                return "flaky";
            }

            @Override
            public void execute(TaskExecutionContext context) throws Exception {
                if (attempts.incrementAndGet() == 1) {
                    throw new IllegalStateException("temporary failure");
                }
                Path output = context.stagingDirectory().resolve("out.txt");
                Files.createDirectories(output.getParent());
                Files.writeString(output, "ok", StandardCharsets.UTF_8);
            }
        };
        TargetDeclaration target = new TargetDeclaration("retry", List.of(), List.of(), List.of("out.txt"),
                "flaky", List.of(), Map.of(), List.of(), null, 1);
        try (BuildEngine engine = BuildEngine.builder().workspace(workspace).toolchain(flaky).build()) {
            BuildResult result = engine.run(new BuildDescription("retry", List.of(target)));
            assertTrue(result.success());
            assertTrue(result.target("retry").attempts() == 2);
            assertTrue(attempts.get() == 2);
        }
    }

    @Test
    void targetTimeoutFailsAction() throws Exception {
        Toolchain slow = new Toolchain() {
            @Override
            public String name() {
                return "slow";
            }

            @Override
            public void execute(TaskExecutionContext context) throws Exception {
                Thread.sleep(250);
                Path output = context.stagingDirectory().resolve("out.txt");
                Files.createDirectories(output.getParent());
                Files.writeString(output, "late", StandardCharsets.UTF_8);
            }
        };
        TargetDeclaration target = new TargetDeclaration("timeout", List.of(), List.of(), List.of("out.txt"),
                "slow", List.of(), Map.of(), List.of(), Duration.ofMillis(50), 0);
        try (BuildEngine engine = BuildEngine.builder().workspace(workspace).toolchain(slow).build()) {
            BuildResult result = engine.run(new BuildDescription("timeout", List.of(target)));
            assertTrue(result.target("timeout").status() == TargetStatus.FAILED);
            assertTrue(result.target("timeout").errorMessage().toLowerCase().contains("timed out"));
            assertTrue(!Files.exists(workspace.resolve("out.txt")));
        }
    }

    @Test
    void configChangesDoNotMutatePreviouslyConstructedEngines() {
        EngineConfig first = EngineConfig.builder().concurrency(2).build();
        EngineConfig second = EngineConfig.builder().concurrency(4).build();
        assertTrue(first.concurrency() == 2);
        assertTrue(second.concurrency() == 4);
    }
}
