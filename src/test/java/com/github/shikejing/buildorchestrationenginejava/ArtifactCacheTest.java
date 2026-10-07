package com.github.shikejing.buildorchestrationenginejava;

import com.github.shikejing.buildorchestrationenginejava.config.EngineConfig;
import com.github.shikejing.buildorchestrationenginejava.execution.BuildEngine;
import com.github.shikejing.buildorchestrationenginejava.execution.TaskExecutionContext;
import com.github.shikejing.buildorchestrationenginejava.execution.Toolchain;
import com.github.shikejing.buildorchestrationenginejava.model.BuildDescription;
import com.github.shikejing.buildorchestrationenginejava.model.BuildResult;
import com.github.shikejing.buildorchestrationenginejava.model.DecisionReason;
import com.github.shikejing.buildorchestrationenginejava.model.TargetDeclaration;
import com.github.shikejing.buildorchestrationenginejava.model.TargetStatus;
import java.nio.file.Files;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ArtifactCacheTest {
    @TempDir
    Path workspace;

    @Test
    void restoresMissingOutputFromVerifiedCacheWithoutRerunningAction() throws Exception {
        AtomicInteger runs = new AtomicInteger();
        BuildDescription description = description();
        try (BuildEngine engine = engine(runs, EngineConfig.defaults())) {
            BuildResult first = engine.run(description);
            assertEquals(TargetStatus.SUCCESS, first.target("cached").status());
        }
        Files.delete(workspace.resolve("out.txt"));
        try (BuildEngine engine = engine(runs, EngineConfig.defaults())) {
            BuildResult restored = engine.run(description);
            assertEquals(TargetStatus.CACHED, restored.target("cached").status());
            assertEquals(DecisionReason.CACHE_HIT, restored.target("cached").reason());
            assertEquals("cached-output", Files.readString(workspace.resolve("out.txt"), StandardCharsets.UTF_8));
            assertEquals(1, runs.get());
        }
    }

    @Test
    void fallsBackWhenCachedArtifactContentIsCorrupt() throws Exception {
        AtomicInteger runs = new AtomicInteger();
        BuildDescription description = description();
        try (BuildEngine engine = engine(runs, EngineConfig.defaults())) {
            assertTrue(engine.run(description).success());
        }
        Files.delete(workspace.resolve("out.txt"));
        Path entry = Files.list(workspace.resolve(".build-engine/cache/entries"))
                .filter(Files::isDirectory)
                .findFirst()
                .orElseThrow();
        Files.writeString(entry.resolve("out.txt"), "corrupt", StandardCharsets.UTF_8);
        try (BuildEngine engine = engine(runs, EngineConfig.defaults())) {
            BuildResult result = engine.run(description);
            assertEquals(TargetStatus.SUCCESS, result.target("cached").status());
            assertEquals(DecisionReason.CORRUPT_CACHE_FALLBACK, result.target("cached").reason());
            assertEquals(2, runs.get());
            assertEquals("cached-output", Files.readString(workspace.resolve("out.txt"), StandardCharsets.UTF_8));
        }
    }

    @Test
    void fallsBackWhenManifestIsCorrupt() throws Exception {
        AtomicInteger runs = new AtomicInteger();
        try (BuildEngine engine = engine(runs, EngineConfig.defaults())) {
            assertTrue(engine.run(description()).success());
        }
        Files.delete(workspace.resolve("out.txt"));
        Path manifest = Files.list(workspace.resolve(".build-engine/cache/entries"))
                .filter(Files::isDirectory)
                .findFirst()
                .orElseThrow()
                .resolve("manifest.json");
        Files.writeString(manifest, "{\"schema\":\"build-cache-entry-v1\"", StandardCharsets.UTF_8);
        try (BuildEngine engine = engine(runs, EngineConfig.defaults())) {
            BuildResult result = engine.run(description());
            assertEquals(DecisionReason.CORRUPT_CACHE_FALLBACK, result.target("cached").reason());
            assertEquals(2, runs.get());
        }
    }

    @Test
    void cacheDisabledConfigDoesNotCreateOrUseCache() throws Exception {
        AtomicInteger runs = new AtomicInteger();
        EngineConfig disabled = EngineConfig.builder().cacheEnabled(false).build();
        try (BuildEngine engine = engine(runs, disabled)) {
            assertTrue(engine.run(description()).success());
        }
        Files.delete(workspace.resolve("out.txt"));
        try (BuildEngine engine = engine(runs, disabled)) {
            BuildResult result = engine.run(description());
            assertEquals(TargetStatus.SUCCESS, result.target("cached").status());
            assertEquals(DecisionReason.OUTPUT_MISSING, result.target("cached").reason());
            assertEquals(2, runs.get());
        }
        assertFalse(Files.exists(workspace.resolve(".build-engine/cache/entries")));
    }

    private BuildEngine engine(AtomicInteger runs, EngineConfig config) {
        Toolchain toolchain = new Toolchain() {
            @Override
            public String name() {
                return "counted";
            }

            @Override
            public void execute(TaskExecutionContext context) {
                try {
                    runs.incrementAndGet();
                    Path output = context.stagingDirectory().resolve("out.txt");
                    Files.createDirectories(output.getParent());
                    Files.writeString(output, "cached-output", StandardCharsets.UTF_8);
                } catch (Exception e) {
                    throw new RuntimeException(e);
                }
            }
        };
        return BuildEngine.builder().workspace(workspace).config(config).toolchain(toolchain).build();
    }

    private BuildDescription description() {
        TargetDeclaration target = new TargetDeclaration("cached", List.of(), List.of(),
                List.of("out.txt"), "counted", List.of(), Map.of(), List.of(), null, null);
        return new BuildDescription("cache", List.of(target));
    }
}
