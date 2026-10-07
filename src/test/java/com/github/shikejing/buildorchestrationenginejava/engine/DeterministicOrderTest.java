package com.github.shikejing.buildorchestrationenginejava.engine;

import com.github.shikejing.buildorchestrationenginejava.engine.exec.BuildReport;
import com.github.shikejing.buildorchestrationenginejava.engine.exec.TargetStatus;
import com.github.shikejing.buildorchestrationenginejava.engine.graph.DependencyGraph;
import com.github.shikejing.buildorchestrationenginejava.engine.model.BuildSpec;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class DeterministicOrderTest {

    @TempDir
    Path tempDir;

    /** Diamond plus independent chain, declared in non-alphabetical order. */
    private static final String SPEC = """
            {"targets":[
              {"name":"zeta", "outputs":["z.out"],"action":{"kind":"WRITE","params":{"content":"z"}}},
              {"name":"mid2", "deps":["base"],"outputs":["m2.out"],"action":{"kind":"WRITE","params":{"content":"m2"}}},
              {"name":"mid1", "deps":["base"],"outputs":["m1.out"],"action":{"kind":"WRITE","params":{"content":"m1"}}},
              {"name":"top",  "deps":["mid1","mid2"],"outputs":["t.out"],"action":{"kind":"WRITE","params":{"content":"t"}}},
              {"name":"base", "outputs":["b.out"],"action":{"kind":"WRITE","params":{"content":"b"}}}
            ]}""";

    @Test
    void topologicalOrderIsDeterministicAndRespectsDeclarationOrder() {
        DependencyGraph graph = new DependencyGraph(TestSupport.parseAndValidate(SPEC));
        List<String> order = graph.executionOrder();
        // ready set starts as {zeta, base}; declaration order wins over alphabet
        assertEquals(List.of("zeta", "base", "mid2", "mid1", "top"), order);
        // same result on repeated construction
        for (int i = 0; i < 5; i++) {
            assertEquals(order, new DependencyGraph(TestSupport.parseAndValidate(SPEC)).executionOrder());
        }
    }

    @Test
    void reportedOrderIsIdenticalAtAnyParallelism() {
        BuildSpec spec = TestSupport.parseAndValidate(SPEC);
        List<String> orderAtOne = runWith(spec, 1);
        List<String> orderAtEight = runWith(spec, 8);
        assertEquals(orderAtOne, orderAtEight);
        // dependency constraints hold in the reported order
        assertTrue(orderAtOne.indexOf("base") < orderAtOne.indexOf("mid1"));
        assertTrue(orderAtOne.indexOf("base") < orderAtOne.indexOf("mid2"));
        assertTrue(orderAtOne.indexOf("mid1") < orderAtOne.indexOf("top"));
        assertTrue(orderAtOne.indexOf("mid2") < orderAtOne.indexOf("top"));
    }

    private List<String> runWith(BuildSpec spec, int parallelism) {
        Path root = tempDir.resolve("p" + parallelism);
        TestSupport.Harness h = TestSupport.harness(spec, root);
        h.config().setParallelism(parallelism);
        BuildReport report = h.newEngine().run(Set.of(), false);
        assertTrue(report.success());
        return report.resultsInOrder().stream()
                .filter(r -> r.getStatus() == TargetStatus.EXECUTED)
                .map(r -> r.getName())
                .collect(Collectors.toList());
    }
}
