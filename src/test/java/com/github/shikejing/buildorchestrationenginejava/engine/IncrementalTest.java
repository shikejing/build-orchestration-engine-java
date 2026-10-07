package com.github.shikejing.buildorchestrationenginejava.engine;

import com.github.shikejing.buildorchestrationenginejava.engine.exec.BuildReport;
import com.github.shikejing.buildorchestrationenginejava.engine.exec.DecisionReason;
import com.github.shikejing.buildorchestrationenginejava.engine.exec.TargetStatus;
import com.github.shikejing.buildorchestrationenginejava.engine.model.BuildSpec;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class IncrementalTest {

    @TempDir
    Path tempDir;

    private BuildSpec spec;
    private TestSupport.Harness h;

    @BeforeEach
    void setUp() {
        spec = TestSupport.parseAndValidate("""
                {"targets":[
                  {"name":"gen","inputs":["src/in.txt"],"outputs":["gen.out"],
                   "action":{"kind":"COPY"}},
                  {"name":"use","deps":["gen"],"inputs":["src/other.txt"],"outputs":["use.out"],
                   "action":{"kind":"CONCAT"}}
                ]}""");
        h = TestSupport.harness(spec, tempDir);
        h.memory().put("src/in.txt", "hello".getBytes(StandardCharsets.UTF_8));
        h.memory().put("src/other.txt", "world".getBytes(StandardCharsets.UTF_8));
    }

    @Test
    void firstRunExecutesEverythingThenEverythingIsUpToDate() {
        BuildReport first = h.newEngine().run(Set.of(), false);
        assertEquals(TargetStatus.EXECUTED, first.target("gen").orElseThrow().getStatus());
        assertEquals(DecisionReason.NO_PRIOR_STATE, first.target("gen").orElseThrow().getReason());

        BuildReport second = h.newEngine().run(Set.of(), false);
        assertEquals(TargetStatus.UP_TO_DATE, second.target("gen").orElseThrow().getStatus());
        assertEquals(TargetStatus.UP_TO_DATE, second.target("use").orElseThrow().getStatus());
        assertEquals(DecisionReason.INPUTS_UNCHANGED, second.target("use").orElseThrow().getReason());
        assertEquals(0, second.target("use").orElseThrow().getDurationMs());
    }

    @Test
    void changingInputRerunsOnlyAffectedTargets() {
        h.newEngine().run(Set.of(), false);
        h.memory().put("src/in.txt", "changed".getBytes(StandardCharsets.UTF_8));

        BuildReport report = h.newEngine().run(Set.of(), false);
        assertEquals(TargetStatus.EXECUTED, report.target("gen").orElseThrow().getStatus());
        assertEquals(DecisionReason.INPUTS_CHANGED, report.target("gen").orElseThrow().getReason());
        // dependent reruns because its dependency's fingerprint changed
        assertEquals(TargetStatus.EXECUTED, report.target("use").orElseThrow().getStatus());
    }

    @Test
    void changingOnlyDependentsOwnInputDoesNotRerunDependency() {
        h.newEngine().run(Set.of(), false);
        h.memory().put("src/other.txt", "changed".getBytes(StandardCharsets.UTF_8));

        BuildReport report = h.newEngine().run(Set.of(), false);
        assertEquals(TargetStatus.UP_TO_DATE, report.target("gen").orElseThrow().getStatus());
        assertEquals(TargetStatus.EXECUTED, report.target("use").orElseThrow().getStatus());
    }

    @Test
    void externallyModifiedOutputIsDetectedAndRerun() throws IOException {
        h.config().setCacheEnabled(false); // isolate the incremental decision from cache
        h.newEngine().run(Set.of(), false);
        Path out = h.outputRoot().resolve("gen.out");
        Files.writeString(out, "tampered-by-outsider");

        BuildReport report = h.newEngine().run(Set.of(), false);
        assertEquals(TargetStatus.EXECUTED, report.target("gen").orElseThrow().getStatus());
        assertEquals(DecisionReason.OUTPUT_TAMPERED, report.target("gen").orElseThrow().getReason());
        // restored to the correct content
        assertEquals("hello", Files.readString(out));
    }

    @Test
    void tamperedOutputIsRepairedFromVerifiedCacheWhenEnabled() throws IOException {
        h.newEngine().run(Set.of(), false);
        Path out = h.outputRoot().resolve("gen.out");
        Files.writeString(out, "tampered-by-outsider");

        BuildReport report = h.newEngine().run(Set.of(), false);
        assertEquals(TargetStatus.CACHE_HIT, report.target("gen").orElseThrow().getStatus());
        assertEquals(DecisionReason.CACHE_RESTORED, report.target("gen").orElseThrow().getReason());
        // the original decision reason is preserved in the detail
        assertEquals("decision=OUTPUT_TAMPERED", report.target("gen").orElseThrow().getDetail());
        assertEquals("hello", Files.readString(out));
    }

    @Test
    void deletedOutputIsDetectedAndRerun() throws IOException {
        h.config().setCacheEnabled(false);
        h.newEngine().run(Set.of(), false);
        Files.delete(h.outputRoot().resolve("gen.out"));

        BuildReport report = h.newEngine().run(Set.of(), false);
        assertEquals(DecisionReason.OUTPUT_MISSING, report.target("gen").orElseThrow().getReason());
        assertEquals(TargetStatus.EXECUTED, report.target("gen").orElseThrow().getStatus());
    }

    @Test
    void forcedRunIgnoresUpToDateState() {
        h.newEngine().run(Set.of(), false);
        BuildReport report = h.newEngine().run(Set.of(), true);
        assertEquals(TargetStatus.EXECUTED, report.target("gen").orElseThrow().getStatus());
        assertEquals(DecisionReason.FORCED_RERUN, report.target("gen").orElseThrow().getReason());
    }

    @Test
    void runningSubsetOnlyTouchesRequestedClosure() {
        h.newEngine().run(Set.of(), false);
        h.memory().put("src/in.txt", "v2".getBytes(StandardCharsets.UTF_8));
        BuildReport report = h.newEngine().run(Set.of("gen"), false);
        assertEquals(TargetStatus.EXECUTED, report.target("gen").orElseThrow().getStatus());
        assertTrue(report.target("use").isEmpty(), "unrequested target must not appear");
    }
}
