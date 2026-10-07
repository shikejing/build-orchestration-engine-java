package com.github.shikejing.buildorchestrationenginejava.execution;

import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.TreeMap;

public class ToolchainRegistry {
    private final Map<String, Toolchain> toolchains = new TreeMap<>();

    public ToolchainRegistry register(Toolchain toolchain) {
        if (toolchain == null || toolchain.name() == null || toolchain.name().isBlank()) {
            throw new IllegalArgumentException("toolchain and its name must not be blank");
        }
        toolchains.put(toolchain.name(), toolchain);
        return this;
    }

    public Optional<Toolchain> find(String name) {
        return Optional.ofNullable(toolchains.get(name));
    }

    public Set<String> names() {
        return Set.copyOf(toolchains.keySet());
    }
}
