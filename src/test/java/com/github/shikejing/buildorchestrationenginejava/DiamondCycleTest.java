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

class DiamondCycleTest {
    @TempDir
    Path workspace;

    @Test
    void detectsBackEdgeWhenNodeWasAlreadyReachedThroughAcyclicPath() {
        BuildDescription description = new BuildDescription("diamond-cycle", List.of(
                target("a", List.of("b", "c")),
                target("b", List.of("c", "d")),
                target("c", List.of("d")),
                target("d", List.of("b"))
        ));
        try (BuildEngine engine = BuildEngine.builder().workspace(workspace).build()) {
            assertThrows(BuildValidationException.class, () -> engine.run(description));
        }
    }

    @Test
    void acceptsAcyclicDiamondWithSharedDependency() {
        BuildDescription description = new BuildDescription("diamond", List.of(
                target("a", List.of("b", "c")),
                target("b", List.of("d")),
                target("c", List.of("d")),
                target("d", List.of())
        ));
        try (BuildEngine engine = BuildEngine.builder().workspace(workspace).build()) {
            org.junit.jupiter.api.Assertions.assertTrue(engine.run(description).success());
        }
    }

    private TargetDeclaration target(String name, List<String> deps) {
        return new TargetDeclaration(name, deps, List.of(), List.of(name + ".txt"),
                "write-text", List.of(name), Map.of(), List.of(), null, null);
    }
}
