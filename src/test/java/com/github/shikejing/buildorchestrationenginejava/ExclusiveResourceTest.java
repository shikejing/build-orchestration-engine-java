package com.github.shikejing.buildorchestrationenginejava;

import com.github.shikejing.buildorchestrationenginejava.config.EngineConfig;
import com.github.shikejing.buildorchestrationenginejava.execution.BuildEngine;
import com.github.shikejing.buildorchestrationenginejava.execution.TaskExecutionContext;
import com.github.shikejing.buildorchestrationenginejava.execution.Toolchain;
import com.github.shikejing.buildorchestrationenginejava.model.BuildDescription;
import com.github.shikejing.buildorchestrationenginejava.model.BuildResult;
import com.github.shikejing.buildorchestrationenginejava.model.TargetDeclaration;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ExclusiveResourceTest {
    @TempDir
    Path workspace;

    @Test
    void exclusiveResourcePreventsOverlappingActions() throws Exception {
        AtomicInteger active = new AtomicInteger();
        AtomicInteger maxActive = new AtomicInteger();
        Toolchain toolchain = new Toolchain() {
            @Override
            public String name() {
                return "guarded";
            }

            @Override
            public void execute(TaskExecutionContext context) throws Exception {
                int now = active.incrementAndGet();
                maxActive.accumulateAndGet(now, Math::max);
                Thread.sleep(120);
                active.decrementAndGet();
                Path output = context.stagingDirectory().resolve(context.outputs().get(0));
                Files.createDirectories(output.getParent());
                Files.writeString(output, context.targetName(), StandardCharsets.UTF_8);
            }
        };
        List<TargetDeclaration> targets = List.of("a", "b", "c").stream()
                .map(name -> new TargetDeclaration(name, List.of(), List.of(), List.of(name + ".txt"),
                        "guarded", List.of(), Map.of(), List.of("database"), null, null))
                .toList();
        EngineConfig config = EngineConfig.builder().concurrency(4).build();
        try (BuildEngine engine = BuildEngine.builder()
                .workspace(workspace)
                .config(config)
                .toolchain(toolchain)
                .build()) {
            BuildResult result = engine.run(new BuildDescription("resources", targets));
            assertTrue(result.success());
        }
        assertEquals(1, maxActive.get());
    }
}
