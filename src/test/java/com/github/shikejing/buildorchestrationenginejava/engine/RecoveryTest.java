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

class RecoveryTest {

    @TempDir
    Path tempDir;

    private String specJson(Path log, String bAction) {
        return """
                {"targets":[
                  {"name":"a","outputs":["a.out"],
                   "action":{"kind":"APPEND","params":{"file":"%s","content":"a-ran"}}},
                  {"name":"b","deps":["a"],"outputs":["b.out"],
                   "action":%s}
                ]}""".formatted(log.toString().replace("\\", "\\\\"), bAction);
    }

    @Test
    void restartResumesFromLastCommittedTargetWithoutReexecuting() throws IOException {
        Path log = tempDir.resolve("side-effects.log");
        // first "process": b fails, a is committed
        BuildSpec spec1 = TestSupport.parseAndValidate(
                specJson(log, "{\"kind\":\"FAIL\"}"));
        TestSupport.Harness h1 = TestSupport.harness(spec1, tempDir);
        h1.config().setFailurePolicy(FailurePolicy.CONTINUE);
        BuildReport first = h1.newEngine().run(Set.of(), false);
        assertEquals(TargetStatus.EXECUTED, first.target("a").orElseThrow().getStatus());
        assertEquals(TargetStatus.FAILED, first.target("b").orElseThrow().getStatus());
        assertEquals(1, Files.readAllLines(log).size());

        // second "process": same dirs, b's action fixed; a must NOT re-execute
        BuildSpec spec2 = TestSupport.parseAndValidate(
                specJson(log, "{\"kind\":\"WRITE\",\"params\":{\"content\":\"b-ok\"}}"));
        TestSupport.Harness h2 = TestSupport.harness(spec2, tempDir);
        BuildReport second = h2.newEngine().run(Set.of(), false);

        assertTrue(second.success());
        assertEquals(TargetStatus.UP_TO_DATE, second.target("a").orElseThrow().getStatus());
        assertEquals(TargetStatus.EXECUTED, second.target("b").orElseThrow().getStatus());
        // side-effecting action of a ran exactly once across both processes
        assertEquals(1, Files.readAllLines(log).size());
        assertEquals("b-ok", Files.readString(h2.outputRoot().resolve("b.out")));
    }

    @Test
    void uncommittedTargetIsRerunAfterCrash() throws IOException {
        Path log = tempDir.resolve("side-effects.log");
        BuildSpec spec = TestSupport.parseAndValidate(
                specJson(log, "{\"kind\":\"WRITE\",\"params\":{\"content\":\"b\"}}"));
        TestSupport.Harness h = TestSupport.harness(spec, tempDir);
        h.config().setCacheEnabled(false); // isolate state-store recovery from cache
        h.newEngine().run(Set.of(), false);
        assertEquals(1, Files.readAllLines(log).size());

        // simulate a crash after a's action ran but before its state was committed:
        // remove a's committed state and its output
        h.stateStore().clear();
        Files.delete(h.outputRoot().resolve("a.out"));
        Files.delete(h.outputRoot().resolve("b.out"));

        BuildReport report = h.newEngine().run(Set.of(), false);
        // a has no committed state, so it must run again (side effect repeats only
        // because the commit never happened - the engine never re-runs committed work)
        assertEquals(TargetStatus.EXECUTED, report.target("a").orElseThrow().getStatus());
        assertEquals(DecisionReason.NO_PRIOR_STATE, report.target("a").orElseThrow().getReason());
        assertEquals(2, Files.readAllLines(log).size());
    }

    @Test
    void corruptedStateFileIsTreatedAsUncommitted() throws IOException {
        Path log = tempDir.resolve("side-effects.log");
        BuildSpec spec = TestSupport.parseAndValidate(
                specJson(log, "{\"kind\":\"WRITE\",\"params\":{\"content\":\"b\"}}"));
        TestSupport.Harness h = TestSupport.harness(spec, tempDir);
        h.config().setCacheEnabled(false);
        h.newEngine().run(Set.of(), false);

        // corrupt a's state file as a crashed write would
        Files.writeString(tempDir.resolve("state").resolve("a.state.json"), "{broken");

        BuildReport report = h.newEngine().run(Set.of(), false);
        assertEquals(TargetStatus.EXECUTED, report.target("a").orElseThrow().getStatus());
        assertEquals(DecisionReason.NO_PRIOR_STATE, report.target("a").orElseThrow().getReason());
        // b depends on a; a's fingerprint is unchanged so b stays up to date
        assertEquals(TargetStatus.UP_TO_DATE, report.target("b").orElseThrow().getStatus());
    }

    @Test
    void completedAndIncompleteTargetsAreDistinguishable() {
        Path log = tempDir.resolve("side-effects.log");
        BuildSpec spec = TestSupport.parseAndValidate(
                specJson(log, "{\"kind\":\"FAIL\"}"));
        TestSupport.Harness h = TestSupport.harness(spec, tempDir);
        h.config().setFailurePolicy(FailurePolicy.CONTINUE);
        h.newEngine().run(Set.of(), false);

        assertTrue(h.stateStore().load("a").isPresent(), "a committed");
        assertFalse(h.stateStore().load("b").isPresent(), "b never committed");
    }
}
