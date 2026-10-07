package com.github.shikejing.buildorchestrationenginejava.engine;

import com.github.shikejing.buildorchestrationenginejava.engine.cache.CacheLookup;
import com.github.shikejing.buildorchestrationenginejava.engine.exec.BuildReport;
import com.github.shikejing.buildorchestrationenginejava.engine.exec.DecisionReason;
import com.github.shikejing.buildorchestrationenginejava.engine.exec.TargetStatus;
import com.github.shikejing.buildorchestrationenginejava.engine.model.BuildSpec;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Comparator;
import java.util.Set;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class CacheCorruptionTest {

    @TempDir
    Path tempDir;

    private TestSupport.Harness h;

    @BeforeEach
    void setUp() {
        BuildSpec spec = TestSupport.parseAndValidate("""
                {"targets":[
                  {"name":"gen","inputs":["src/in.txt"],"outputs":["gen.out"],
                   "action":{"kind":"COPY"}}
                ]}""");
        h = TestSupport.harness(spec, tempDir);
        h.memory().put("src/in.txt", "payload".getBytes());
    }

    /** Wipe state + outputs so the next run must consult the cache. */
    private void wipeStateAndOutputs() throws IOException {
        h.stateStore().clear();
        Files.deleteIfExists(h.outputRoot().resolve("gen.out"));
    }

    @Test
    void cacheHitRestoresOutputsWithoutExecuting() throws IOException {
        BuildReport first = h.newEngine().run(Set.of(), false);
        assertEquals(TargetStatus.EXECUTED, first.target("gen").orElseThrow().getStatus());

        wipeStateAndOutputs();
        BuildReport second = h.newEngine().run(Set.of(), false);
        assertEquals(TargetStatus.CACHE_HIT, second.target("gen").orElseThrow().getStatus());
        assertEquals(DecisionReason.CACHE_RESTORED, second.target("gen").orElseThrow().getReason());
        assertEquals("payload", Files.readString(h.outputRoot().resolve("gen.out")));
    }

    @Test
    void corruptedBlobFallsBackToReexecution() throws IOException {
        h.newEngine().run(Set.of(), false);
        // corrupt every blob in the cache
        Path cacheDir = tempDir.resolve("cache");
        try (Stream<Path> walk = Files.walk(cacheDir)) {
            for (Path blob : walk.filter(p -> p.getParent() != null
                    && p.getParent().getFileName().toString().equals("blobs")).toList()) {
                Files.writeString(blob, "corrupted-bytes");
            }
        }
        wipeStateAndOutputs();

        BuildReport report = h.newEngine().run(Set.of(), false);
        // bad cache must not be served: target re-executes and output is correct
        assertEquals(TargetStatus.EXECUTED, report.target("gen").orElseThrow().getStatus());
        assertEquals(DecisionReason.CACHE_CORRUPT, report.target("gen").orElseThrow().getReason());
        assertEquals("payload", Files.readString(h.outputRoot().resolve("gen.out")));
    }

    @Test
    void corruptedManifestIsReportedAsCorrupt() throws IOException {
        h.newEngine().run(Set.of(), false);
        Path cacheDir = tempDir.resolve("cache");
        try (Stream<Path> walk = Files.walk(cacheDir)) {
            for (Path manifest : walk.filter(p -> p.getFileName().toString().equals("manifest.json")).toList()) {
                Files.writeString(manifest, "{not json");
            }
        }
        String key;
        try (Stream<Path> list = Files.list(cacheDir)) {
            key = list.filter(Files::isDirectory).findFirst().orElseThrow().getFileName().toString();
        }
        assertEquals(CacheLookup.Status.CORRUPT, h.cache().lookup(key).status());
    }

    @Test
    void wrongKeyMappingIsRejected() throws IOException {
        h.newEngine().run(Set.of(), false);
        Path cacheDir = tempDir.resolve("cache");
        Path entryDir;
        try (Stream<Path> list = Files.list(cacheDir)) {
            entryDir = list.filter(Files::isDirectory).findFirst().orElseThrow();
        }
        // rewrite the manifest so it claims a different key than its directory
        String manifest = Files.readString(entryDir.resolve("manifest.json"));
        String key = entryDir.getFileName().toString();
        Files.writeString(entryDir.resolve("manifest.json"),
                manifest.replace(key, "f".repeat(key.length())));
        assertEquals(CacheLookup.Status.CORRUPT, h.cache().lookup(key).status());
    }

    @Test
    void retentionPolicyEvictsOldestEntries() throws IOException {
        h.config().setCacheMaxEntries(1);
        h.newEngine().run(Set.of(), false);
        h.memory().put("src/in.txt", "payload-v2".getBytes());
        wipeStateAndOutputs();
        h.newEngine().run(Set.of(), false);

        Path cacheDir = tempDir.resolve("cache");
        try (Stream<Path> list = Files.list(cacheDir)) {
            assertEquals(1, list.filter(Files::isDirectory).count());
        }
    }

    @Test
    void cacheDisabledByConfig() throws IOException {
        h.config().setCacheEnabled(false);
        h.newEngine().run(Set.of(), false);
        wipeStateAndOutputs();
        BuildReport report = h.newEngine().run(Set.of(), false);
        assertEquals(TargetStatus.EXECUTED, report.target("gen").orElseThrow().getStatus());
        assertTrue(Files.notExists(tempDir.resolve("cache")) || isEmpty(tempDir.resolve("cache")));
    }

    private static boolean isEmpty(Path dir) throws IOException {
        try (Stream<Path> list = Files.list(dir)) {
            return list.findFirst().isEmpty();
        }
    }
}
