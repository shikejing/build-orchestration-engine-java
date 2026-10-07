package com.github.shikejing.buildorchestrationenginejava.engine.fingerprint;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Optional;

/** Resolves inputs from the local file system under a root directory. */
public class DiskInputSource implements InputSource {

    private final Path root;

    public DiskInputSource(Path root) {
        this.root = root;
    }

    @Override
    public Optional<byte[]> read(String path) throws IOException {
        Path resolved = root.resolve(path).normalize();
        if (!resolved.startsWith(root.normalize()) || !Files.isRegularFile(resolved)) {
            return Optional.empty();
        }
        return Optional.of(Files.readAllBytes(resolved));
    }
}
