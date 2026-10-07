package com.github.shikejing.buildorchestrationenginejava.engine.fingerprint;

import java.io.IOException;
import java.util.Optional;

/** Resolves declared inputs from memory or disk. */
public interface InputSource {
    Optional<byte[]> read(String path) throws IOException;
}
