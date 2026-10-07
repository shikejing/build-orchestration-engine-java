package com.github.shikejing.buildorchestrationenginejava;

import com.github.shikejing.buildorchestrationenginejava.error.BuildValidationException;
import com.github.shikejing.buildorchestrationenginejava.execution.BuildEngine;
import com.github.shikejing.buildorchestrationenginejava.model.BuildDescription;
import com.github.shikejing.buildorchestrationenginejava.model.TargetDeclaration;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class DescriptionValidationTest {
    @TempDir
    Path workspace;

    @Test
    void rejectsDuplicateTargetWithTargetLocation() {
        BuildDescription description = new BuildDescription("demo", List.of(target("a"), target("a")));
        try (BuildEngine engine = BuildEngine.builder().workspace(workspace).build()) {
            BuildValidationException error = assertThrows(BuildValidationException.class, () -> engine.run(description));
            assertTrue(error.getMessage().contains("target 'a'"));
            assertTrue(error.getMessage().contains("defined more than once"));
        }
    }

    @Test
    void rejectsMissingDependency() {
        BuildDescription description = new BuildDescription("demo", List.of(
                new TargetDeclaration("a", List.of("missing"), List.of("in.txt"), List.of("a.txt"),
                        "write-text", List.of(), Map.of(), List.of(), null, null)
        ));
        try (BuildEngine engine = BuildEngine.builder().workspace(workspace).build()) {
            BuildValidationException error = assertThrows(BuildValidationException.class, () -> engine.run(description));
            assertTrue(error.getMessage().contains("target 'a'"));
            assertTrue(error.getMessage().contains("Dependency 'missing' does not exist"));
        }
    }

    @Test
    void rejectsSelfDependency() {
        BuildDescription description = new BuildDescription("demo", List.of(
                new TargetDeclaration("a", List.of("a"), List.of("in.txt"), List.of("a.txt"),
                        "write-text", List.of(), Map.of(), List.of(), null, null)
        ));
        try (BuildEngine engine = BuildEngine.builder().workspace(workspace).build()) {
            BuildValidationException error = assertThrows(BuildValidationException.class, () -> engine.run(description));
            assertTrue(error.getMessage().contains("depends on itself"));
            assertTrue(error.issues().stream().anyMatch(issue -> issue.targetName().equals("a")));
        }
    }

    @Test
    void rejectsCycleAndMentionsEveryParticipatingTarget() {
        BuildDescription description = new BuildDescription("demo", List.of(
                new TargetDeclaration("a", List.of("c"), List.of(), List.of("a.txt"),
                        "write-text", List.of(), Map.of(), List.of(), null, null),
                new TargetDeclaration("b", List.of("a"), List.of(), List.of("b.txt"),
                        "write-text", List.of(), Map.of(), List.of(), null, null),
                new TargetDeclaration("c", List.of("b"), List.of(), List.of("c.txt"),
                        "write-text", List.of(), Map.of(), List.of(), null, null)
        ));
        try (BuildEngine engine = BuildEngine.builder().workspace(workspace).build()) {
            BuildValidationException error = assertThrows(BuildValidationException.class, () -> engine.run(description));
            assertTrue(error.issues().stream().anyMatch(issue -> issue.targetName().equals("a")));
            assertTrue(error.issues().stream().anyMatch(issue -> issue.targetName().equals("b")));
            assertTrue(error.issues().stream().anyMatch(issue -> issue.targetName().equals("c")));
        }
    }

    @Test
    void rejectsUnknownToolchain() {
        BuildDescription description = new BuildDescription("demo", List.of(
                new TargetDeclaration("a", List.of(), List.of("in.txt"), List.of("a.txt"),
                        "unknown", List.of(), Map.of(), List.of(), null, null)
        ));
        try (BuildEngine engine = BuildEngine.builder().workspace(workspace).build()) {
            BuildValidationException error = assertThrows(BuildValidationException.class, () -> engine.run(description));
            assertTrue(error.getMessage().contains("Unknown toolchain 'unknown'"));
        }
    }

    private static TargetDeclaration target(String name) {
        return new TargetDeclaration(name, List.of(), List.of(name + ".in"),
                List.of(name + ".out"), "write-text", List.of("x"), Map.of(), List.of(), null, null);
    }
}
