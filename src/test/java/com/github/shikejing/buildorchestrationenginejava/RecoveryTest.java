package com.github.shikejing.buildorchestrationenginejava;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.github.shikejing.buildorchestrationenginejava.cache.CacheKeyFactory;
import com.github.shikejing.buildorchestrationenginejava.execution.BuildEngine;
import com.github.shikejing.buildorchestrationenginejava.execution.TaskExecutionContext;
import com.github.shikejing.buildorchestrationenginejava.execution.Toolchain;
import com.github.shikejing.buildorchestrationenginejava.fingerprint.FingerprintService;
import com.github.shikejing.buildorchestrationenginejava.fingerprint.TargetFingerprints;
import com.github.shikejing.buildorchestrationenginejava.model.BuildDescription;
import com.github.shikejing.buildorchestrationenginejava.model.BuildResult;
import com.github.shikejing.buildorchestrationenginejava.model.DecisionReason;
import com.github.shikejing.buildorchestrationenginejava.model.TargetDeclaration;
import com.github.shikejing.buildorchestrationenginejava.model.TargetStatus;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class RecoveryTest {
    @TempDir
    Path workspace;

    @Test
    void resumesFromPublishedTransactionWithoutRerunningAction() throws Exception {
        AtomicInteger runs = new AtomicInteger();
        Toolchain counted = countedToolchain(runs);
        BuildDescription description = description(counted.name());
        Files.createDirectories(workspace.resolve(".build-engine/staging/recovered"));
        Files.createDirectories(workspace.resolve(".build-engine"));
        Files.writeString(workspace.resolve("out.txt"), "recovered-output", StandardCharsets.UTF_8);
        TargetDeclaration target = description(counted.name()).targets().get(0);
        FingerprintService fingerprints = new FingerprintService();
        String input = fingerprints.hashFiles(workspace, List.of());
        String output = fingerprints.hashFiles(workspace, List.of("out.txt"));
        String action = new FingerprintService().hashText(actionCanonical(counted.name()));
        String dependency = fingerprints.hashText("");
        TargetFingerprints current = new TargetFingerprints(input, output, action, dependency, null, true);
        String key = new CacheKeyFactory(fingerprints).create(target, current).value();
        writePublishedJournal(workspace.resolve(".build-engine/journal.json"),
                workspace.resolve(".build-engine/staging/recovered"), key, input, output, action, dependency,
                Map.of("out.txt", fingerprints.fingerprintFile(workspace, "out.txt").sha256()));

        try (BuildEngine engine = BuildEngine.builder().workspace(workspace).toolchain(counted).build()) {
            BuildResult result = engine.run(description);
            assertEquals(TargetStatus.SUCCESS, result.target("recover").status());
            assertEquals(DecisionReason.RECOVERED_PUBLISHED, result.target("recover").reason());
            assertEquals(0, runs.get());
        }
        String journal = Files.readString(workspace.resolve(".build-engine/journal.json"), StandardCharsets.UTF_8);
        assertFalse(journal.contains("recover"));
        assertTrue(Files.readString(workspace.resolve(".build-engine/state.json"), StandardCharsets.UTF_8)
                .contains("recover"));
    }

    @Test
    void rerunsWhenPublishedArtifactDisappearsAfterRestart() throws Exception {
        AtomicInteger runs = new AtomicInteger();
        Toolchain counted = countedToolchain(runs);
        TargetDeclaration target = description(counted.name()).targets().get(0);
        FingerprintService fingerprints = new FingerprintService();
        String input = fingerprints.hashFiles(workspace, List.of());
        String action = fingerprints.hashText(actionCanonical(counted.name()));
        String dependency = fingerprints.hashText("");
        String missingOutput = fingerprints.hashFiles(workspace, List.of("out.txt"));
        TargetFingerprints current = new TargetFingerprints(input, missingOutput, action, dependency, null, false);
        String key = new CacheKeyFactory(fingerprints).create(target, current).value();
        Files.createDirectories(workspace.resolve(".build-engine/staging/lost"));
        writePublishedJournal(workspace.resolve(".build-engine/journal.json"),
                workspace.resolve(".build-engine/staging/lost"),
                key, input, "missing", action, dependency, Map.of("out.txt", "missing"));

        try (BuildEngine engine = BuildEngine.builder().workspace(workspace).toolchain(counted).build()) {
            BuildResult result = engine.run(description(counted.name()));
            assertEquals(TargetStatus.SUCCESS, result.target("recover").status());
            assertEquals(1, runs.get());
            assertEquals("recovered-output", Files.readString(workspace.resolve("out.txt"), StandardCharsets.UTF_8));
        }
    }

    private void writePublishedJournal(Path journal, Path staging, String cacheKey, String input,
                                       String output, String action, String dependency,
                                       Map<String, String> hashes) throws Exception {
        Files.createDirectories(journal.getParent());
        Map<String, Object> target = new LinkedHashMap<>();
        target.put("phase", "PUBLISHED");
        target.put("cacheKey", cacheKey);
        target.put("stagingDirectory", staging.toString());
        target.put("outputHashes", hashes);
        target.put("updatedAt", Instant.now().toString());
        target.put("inputFingerprint", input);
        target.put("outputFingerprint", output);
        target.put("actionFingerprint", action);
        target.put("dependencyFingerprint", dependency);
        Map<String, Object> root = new LinkedHashMap<>();
        root.put("schema", "build-execution-journal-v1");
        root.put("targets", Map.of("recover", target));
        new ObjectMapper().findAndRegisterModules().writerWithDefaultPrettyPrinter()
                .writeValue(journal.toFile(), root);
    }

    private Toolchain countedToolchain(AtomicInteger runs) {
        return new Toolchain() {
            @Override
            public String name() {
                return "counted-recovery";
            }

            @Override
            public void execute(TaskExecutionContext context) throws Exception {
                runs.incrementAndGet();
                Path output = context.stagingDirectory().resolve("out.txt");
                Files.createDirectories(output.getParent());
                Files.writeString(output, "recovered-output", StandardCharsets.UTF_8);
            }
        };
    }

    private BuildDescription description(String toolchain) {
        return new BuildDescription("recovery", List.of(new TargetDeclaration("recover", List.of(),
                List.of(), List.of("out.txt"), toolchain, List.of(), Map.of(), List.of(), null, null)));
    }

    private String actionCanonical(String toolchain) {
        return "toolchain=" + toolchain + "\narguments=\noutputs=out.txt\nresources=\n";
    }
}
