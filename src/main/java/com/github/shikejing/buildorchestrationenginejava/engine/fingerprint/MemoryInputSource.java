package com.github.shikejing.buildorchestrationenginejava.engine.fingerprint;

import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

/** In-memory inputs, keyed by logical path; useful for tests and simulation. */
public class MemoryInputSource implements InputSource {

    private final Map<String, byte[]> data = new ConcurrentHashMap<>();

    public MemoryInputSource() {}

    public MemoryInputSource(Map<String, byte[]> initial) {
        data.putAll(initial);
    }

    public void put(String path, byte[] content) {
        data.put(path, content);
    }

    public void remove(String path) {
        data.remove(path);
    }

    @Override
    public Optional<byte[]> read(String path) {
        return Optional.ofNullable(data.get(path));
    }
}
