package com.github.shikejing.buildorchestrationenginejava.engine.fingerprint;

import java.io.IOException;
import java.util.List;
import java.util.Optional;

/** Tries each delegate in order (e.g. memory overlay first, then disk). */
public class CompositeInputSource implements InputSource {

    private final List<InputSource> delegates;

    public CompositeInputSource(InputSource... delegates) {
        this.delegates = List.of(delegates);
    }

    @Override
    public Optional<byte[]> read(String path) throws IOException {
        for (InputSource delegate : delegates) {
            Optional<byte[]> hit = delegate.read(path);
            if (hit.isPresent()) {
                return hit;
            }
        }
        return Optional.empty();
    }
}
