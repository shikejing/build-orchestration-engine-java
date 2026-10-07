package com.github.shikejing.buildorchestrationenginejava.engine;

import com.github.shikejing.buildorchestrationenginejava.engine.exec.BuildReport;
import com.github.shikejing.buildorchestrationenginejava.engine.exec.DecisionReason;
import com.github.shikejing.buildorchestrationenginejava.engine.exec.FailurePolicy;
import com.github.shikejing.buildorchestrationenginejava.engine.exec.TargetStatus;
import com.github.shikejing.buildorchestrationenginejava.engine.model.BuildSpec;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ConcurrencyResourceTest {

    @TempDir
    Path tempDir;

    private static BuildSpec sleepers(int count, String resource) {
        StringBuilder sb = new StringBuilder("{\"targets\":[");
        for (int i = 0; i < count; i++) {
            if (i > 0) {
                sb.append(',');
            }
            sb.append("{\"name\":\"t").append(i).append("\",");
            if (resource != null) {
                sb.append("\"resources\":[\"").append(resource).append("\"],");
            }
            sb.append("\"outputs\":[\"t").append(i).append(".out\"],")
                    .append("\"action\":{\"kind\":\"SLEEP\",\"params\":{\"ms\":\"250\"}}}");
        }
        return TestSupport.parseAndValidate(sb.append("]}").toString());
    }

    @Test
    void independentTargetsRunInParallel() {
        BuildSpec spec = sleepers(4, null);
        TestSupport.Harness h = TestSupport.harness(spec, tempDir);
        h.config().setParallelism(4);
        long start = System.nanoTime();
        BuildReport report = h.newEngine().run(Set.of(), false);
        long elapsedMs = (System.nanoTime() - start) / 1_000_000;
        assertTrue(report.success());
        assertTrue(elapsedMs < 900, "4x250ms in parallel should finish well under 900ms, took " + elapsedMs);
    }

    @Test
    void exclusiveResourceSerializesActions() {
        BuildSpec spec = sleepers(4, "exclusive-io");
        TestSupport.Harness h = TestSupport.harness(spec, tempDir);
        h.config().setParallelism(4);
        long start = System.nanoTime();
        BuildReport report = h.newEngine().run(Set.of(), false);
        long elapsedMs = (System.nanoTime() - start) / 1_000_000;
        assertTrue(report.success());
        assertTrue(elapsedMs >= 950,
                "4x250ms on one exclusive resource must serialize, took " + elapsedMs);
    }

    @Test
    void cancelRemainingPolicyStopsSiblingsAndLeavesNoHalfProducts() {
        BuildSpec spec = TestSupport.parseAndValidate("""
                {"targets":[
                  {"name":"bad","outputs":["bad.out"],"action":{"kind":"FAIL"}},
                  {"name":"victim","deps":["bad"],"outputs":["victim.out"],
                   "action":{"kind":"WRITE","params":{"content":"v"}}},
                  {"name":"slow","outputs":["slow.out"],
                   "action":{"kind":"SLEEP","params":{"ms":"2000"}}}
                ]}""");
        TestSupport.Harness h = TestSupport.harness(spec, tempDir);
        h.config().setParallelism(2).setFailurePolicy(FailurePolicy.CANCEL_REMAINING);
        BuildReport report = h.newEngine().run(Set.of(), false);

        assertFalse(report.success());
        assertEquals(TargetStatus.FAILED, report.target("bad").orElseThrow().getStatus());
        assertEquals(TargetStatus.CANCELLED, report.target("victim").orElseThrow().getStatus());
        assertEquals(DecisionReason.DEPENDENCY_FAILED, report.target("victim").orElseThrow().getReason());
        // failed action left no partial output behind
        assertFalse(Files.exists(h.outputRoot().resolve("bad.out")));
        assertFalse(Files.exists(h.outputRoot().resolve("victim.out")));
        assertFalse(Files.exists(h.outputRoot().resolve(".staging").resolve("bad")));
    }

    @Test
    void continuePolicyKeepsIndependentTargetsGoing() {
        BuildSpec spec = TestSupport.parseAndValidate("""
                {"targets":[
                  {"name":"bad","outputs":["bad.out"],"action":{"kind":"FAIL"}},
                  {"name":"victim","deps":["bad"],"outputs":["victim.out"],
                   "action":{"kind":"WRITE","params":{"content":"v"}}},
                  {"name":"fine","outputs":["fine.out"],
                   "action":{"kind":"WRITE","params":{"content":"ok"}}}
                ]}""");
        TestSupport.Harness h = TestSupport.harness(spec, tempDir);
        h.config().setParallelism(2).setFailurePolicy(FailurePolicy.CONTINUE);
        BuildReport report = h.newEngine().run(Set.of(), false);

        assertFalse(report.success());
        assertEquals(TargetStatus.FAILED, report.target("bad").orElseThrow().getStatus());
        assertEquals(TargetStatus.CANCELLED, report.target("victim").orElseThrow().getStatus());
        assertEquals(TargetStatus.EXECUTED, report.target("fine").orElseThrow().getStatus());
        assertTrue(Files.exists(h.outputRoot().resolve("fine.out")));
    }

    @Test
    void timeoutFailsTargetAndRetriesAreBounded() throws IOException {
        Path attemptLog = tempDir.resolve("attempts.log");
        BuildSpec spec = TestSupport.parseAndValidate("""
                {"targets":[
                  {"name":"slow","timeoutMs":150,"retries":2,"outputs":["slow.out"],
                   "action":{"kind":"APPEND","params":{"file":"%s","content":"try","fail":"true"}}}
                ]}""".formatted(attemptLog.toString().replace("\\", "\\\\")));
        TestSupport.Harness h = TestSupport.harness(spec, tempDir);
        BuildReport report = h.newEngine().run(Set.of(), false);

        assertEquals(TargetStatus.FAILED, report.target("slow").orElseThrow().getStatus());
        // 1 initial attempt + 2 retries = 3 appended lines
        assertEquals(3, Files.readAllLines(attemptLog).size());
        assertFalse(Files.exists(h.outputRoot().resolve("slow.out")));
    }

    @Test
    void sleepingActionTimesOut() {
        BuildSpec spec = TestSupport.parseAndValidate("""
                {"targets":[
                  {"name":"slow","timeoutMs":100,"outputs":["slow.out"],
                   "action":{"kind":"SLEEP","params":{"ms":"5000"}}}
                ]}""");
        TestSupport.Harness h = TestSupport.harness(spec, tempDir);
        long start = System.nanoTime();
        BuildReport report = h.newEngine().run(Set.of(), false);
        long elapsedMs = (System.nanoTime() - start) / 1_000_000;
        assertEquals(TargetStatus.FAILED, report.target("slow").orElseThrow().getStatus());
        assertEquals(DecisionReason.TIMEOUT, report.target("slow").orElseThrow().getReason());
        assertTrue(elapsedMs < 3000, "timeout should cut the 5s action, took " + elapsedMs);
    }
}
