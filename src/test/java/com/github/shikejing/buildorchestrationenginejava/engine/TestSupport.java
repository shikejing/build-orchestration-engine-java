package com.github.shikejing.buildorchestrationenginejava.engine;

import com.github.shikejing.buildorchestrationenginejava.engine.cache.LocalDiskCache;
import com.github.shikejing.buildorchestrationenginejava.engine.exec.BuildEngine;
import com.github.shikejing.buildorchestrationenginejava.engine.exec.EngineConfig;
import com.github.shikejing.buildorchestrationenginejava.engine.fingerprint.CompositeInputSource;
import com.github.shikejing.buildorchestrationenginejava.engine.fingerprint.DiskInputSource;
import com.github.shikejing.buildorchestrationenginejava.engine.fingerprint.MemoryInputSource;
import com.github.shikejing.buildorchestrationenginejava.engine.graph.DependencyGraph;
import com.github.shikejing.buildorchestrationenginejava.engine.model.BuildSpec;
import com.github.shikejing.buildorchestrationenginejava.engine.spec.BuildSpecParser;
import com.github.shikejing.buildorchestrationenginejava.engine.spec.SpecValidator;
import com.github.shikejing.buildorchestrationenginejava.engine.state.BuildStateStore;

import java.nio.file.Path;

/** Shared helpers for engine tests. */
final class TestSupport {

    private TestSupport() {}

    static BuildSpec parseAndValidate(String json) {
        BuildSpec spec = new BuildSpecParser().parse(json);
        new SpecValidator().validate(spec);
        return spec;
    }

    record Harness(BuildSpec spec, DependencyGraph graph, MemoryInputSource memory,
                   Path outputRoot, BuildStateStore stateStore, LocalDiskCache cache,
                   EngineConfig config) {

        BuildEngine newEngine() {
            return new BuildEngine(spec, graph,
                    new CompositeInputSource(memory, new DiskInputSource(outputRoot)),
                    outputRoot, stateStore, cache, config);
        }
    }

    static Harness harness(BuildSpec spec, Path root) {
        DependencyGraph graph = new DependencyGraph(spec);
        return new Harness(spec, graph, new MemoryInputSource(),
                root.resolve("out"),
                new BuildStateStore(root.resolve("state")),
                new LocalDiskCache(root.resolve("cache")),
                new EngineConfig());
    }
}
