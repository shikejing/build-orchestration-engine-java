package com.github.shikejing.buildorchestrationenginejava;

import com.github.shikejing.buildorchestrationenginejava.execution.BuildEngine;
import com.github.shikejing.buildorchestrationenginejava.execution.TaskExecutionContext;
import com.github.shikejing.buildorchestrationenginejava.execution.Toolchain;
import com.github.shikejing.buildorchestrationenginejava.model.BuildDescription;
import com.github.shikejing.buildorchestrationenginejava.model.BuildResult;
import com.github.shikejing.buildorchestrationenginejava.model.DecisionReason;
import com.github.shikejing.buildorchestrationenginejava.model.TargetDeclaration;
import com.github.shikejing.buildorchestrationenginejava.model.TargetStatus;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class InterruptionTest {
    @TempDir
    Path workspace;

    @Test
    void interruptsRunningActionAndDoesNotPublishOutput() throws Exception {
        CountDownLatch started = new CountDownLatch(1);
        CountDownLatch interrupted = new CountDownLatch(1);
        Toolchain slow = new Toolchain() {
            @Override
            public String name() {
                return "interruptible";
            }

            @Override
            public void execute(TaskExecutionContext context) throws Exception {
                started.countDown();
                try {
                    Thread.sleep(10_000);
                } catch (InterruptedException e) {
                    interrupted.countDown();
                    throw e;
                }
                Path output = context.stagingDirectory().resolve("out.txt");
                Files.writeString(output, "should not publish");
            }
        };
        TargetDeclaration target = new TargetDeclaration("slow", List.of(), List.of(), List.of("out.txt"),
                "interruptible", List.of(), Map.of(), List.of(), null, null);
        BuildEngine engine = BuildEngine.builder().workspace(workspace).toolchain(slow).build();
        AtomicReference<BuildResult> resultReference = new AtomicReference<>();
        Thread runner = new Thread(() -> resultReference.set(engine.run(new BuildDescription("interrupt", List.of(target)))));
        runner.start();
        assertTrue(started.await(5, java.util.concurrent.TimeUnit.SECONDS));
        engine.close();
        runner.join(5000);
        assertFalse(runner.isAlive());
        assertTrue(interrupted.await(1, java.util.concurrent.TimeUnit.SECONDS));
        BuildResult result = resultReference.get();
        assertTrue(result.interrupted());
        assertTrue(result.target("slow").status() == TargetStatus.CANCELLED);
        assertTrue(result.target("slow").reason() == DecisionReason.INTERRUPTED);
        assertFalse(Files.exists(workspace.resolve("out.txt")));
    }
}
