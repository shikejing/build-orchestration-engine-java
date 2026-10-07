package com.github.shikejing.buildorchestrationenginejava;

import com.github.shikejing.buildorchestrationenginejava.config.EngineConfig;
import com.github.shikejing.buildorchestrationenginejava.execution.BuildEngine;
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
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class IncrementalBuildTest {
    @TempDir
    Path workspace;

    @Test
    void skipsUnchangedTargetAndReRunsWhenInputChanges() throws Exception {
        Path input = workspace.resolve("input.txt");
        Files.writeString(input, "one", StandardCharsets.UTF_8);
        BuildDescription description = description("copy", List.of());

        try (BuildEngine engine = BuildEngine.builder().workspace(workspace).build()) {
            BuildResult first = engine.run(description);
            assertEquals(TargetStatus.SUCCESS, first.target("copy").status());
            assertEquals(DecisionReason.NO_PREVIOUS_STATE, first.target("copy").reason());
        }
        try (BuildEngine engine = BuildEngine.builder().workspace(workspace).build()) {
            BuildResult second = engine.run(description);
            assertEquals(TargetStatus.UP_TO_DATE, second.target("copy").status());
            assertEquals(DecisionReason.UP_TO_DATE, second.target("copy").reason());
        }
        Files.writeString(input, "two", StandardCharsets.UTF_8);
        try (BuildEngine engine = BuildEngine.builder().workspace(workspace).build()) {
            BuildResult third = engine.run(description);
            assertEquals(TargetStatus.SUCCESS, third.target("copy").status());
            assertEquals(DecisionReason.INPUT_CHANGED, third.target("copy").reason());
            assertEquals("two", Files.readString(workspace.resolve("build/output.txt"), StandardCharsets.UTF_8));
        }
    }

    @Test
    void detectsExternallyModifiedOutput() throws Exception {
        Files.writeString(workspace.resolve("input.txt"), "one", StandardCharsets.UTF_8);
        BuildDescription description = description("copy", List.of());
        try (BuildEngine engine = BuildEngine.builder().workspace(workspace).build()) {
            assertTrue(engine.run(description).success());
        }
        Files.writeString(workspace.resolve("build/output.txt"), "tampered", StandardCharsets.UTF_8);
        try (BuildEngine engine = BuildEngine.builder().workspace(workspace).build()) {
            BuildResult result = engine.run(description);
            assertEquals(TargetStatus.SUCCESS, result.target("copy").status());
            assertEquals(DecisionReason.OUTPUT_MODIFIED, result.target("copy").reason());
            assertEquals("one", Files.readString(workspace.resolve("build/output.txt"), StandardCharsets.UTF_8));
        }
    }

    @Test
    void detectsActionChangeAndForcedRebuild() throws Exception {
        try (BuildEngine engine = BuildEngine.builder().workspace(workspace).build()) {
            BuildResult first = engine.run(writeDescription("hello"));
            assertEquals(DecisionReason.NO_PREVIOUS_STATE, first.target("generate").reason());
        }
        try (BuildEngine engine = BuildEngine.builder().workspace(workspace).build()) {
            BuildResult changed = engine.run(writeDescription("world"));
            assertEquals(DecisionReason.ACTION_CHANGED, changed.target("generate").reason());
        }
        EngineConfig forced = EngineConfig.builder().forceRebuild(true).build();
        try (BuildEngine engine = BuildEngine.builder().workspace(workspace).config(forced).build()) {
            BuildResult result = engine.run(writeDescription("world"));
            assertEquals(TargetStatus.SUCCESS, result.target("generate").status());
            assertEquals(DecisionReason.FORCED_REBUILD, result.target("generate").reason());
        }
    }

    @Test
    void dependencyOutputChangeReRunsDependentButNotUnrelatedTarget() throws Exception {
        Files.writeString(workspace.resolve("source.txt"), "v1", StandardCharsets.UTF_8);
        BuildDescription first = new BuildDescription("deps", List.of(
                target("lib", List.of(), List.of("source.txt"), List.of("lib.txt"), "copy", List.of()),
                target("app", List.of("lib"), List.of(), List.of("app.txt"), "write-text", List.of("app")),
                target("other", List.of(), List.of(), List.of("other.txt"), "write-text", List.of("other"))
        ));
        try (BuildEngine engine = BuildEngine.builder().workspace(workspace).build()) {
            assertTrue(engine.run(first).success());
        }
        Files.writeString(workspace.resolve("source.txt"), "v2", StandardCharsets.UTF_8);
        try (BuildEngine engine = BuildEngine.builder().workspace(workspace).build()) {
            BuildResult result = engine.run(first);
            assertEquals(DecisionReason.INPUT_CHANGED, result.target("lib").reason());
            assertEquals(DecisionReason.DEPENDENCY_CHANGED, result.target("app").reason());
            assertEquals(DecisionReason.UP_TO_DATE, result.target("other").reason());
        }
    }

    private BuildDescription description(String name, List<String> deps) {
        return new BuildDescription("incremental", List.of(target(name, deps,
                List.of("input.txt"), List.of("build/output.txt"), "copy", List.of())));
    }

    private BuildDescription writeDescription(String content) {
        TargetDeclaration target = new TargetDeclaration("generate", List.of(), List.of(),
                List.of("generated.txt"), "write-text", List.of(content),
                Map.of(), List.of(), null, null);
        return new BuildDescription("actions", List.of(target));
    }

    private TargetDeclaration target(String name, List<String> deps, List<String> inputs,
                                     List<String> outputs, String toolchain, List<String> arguments) {
        return new TargetDeclaration(name, deps, inputs, outputs, toolchain, arguments,
                Map.of(), List.of(), null, null);
    }
}
