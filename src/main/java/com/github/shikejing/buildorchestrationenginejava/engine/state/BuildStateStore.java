package com.github.shikejing.buildorchestrationenginejava.engine.state;

import com.fasterxml.jackson.databind.ObjectMapper;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.Optional;

/**
 * Durable store of per-target states. Each committed state is written
 * atomically (tmp file + rename) so a crash mid-write never leaves a
 * half-written record. Corrupted records are treated as absent, which
 * safely forces a re-run instead of trusting bad data.
 */
public class BuildStateStore {

    private final Path stateDir;
    private final ObjectMapper mapper = new ObjectMapper();

    public BuildStateStore(Path stateDir) {
        this.stateDir = stateDir;
    }

    private Path fileFor(String target) {
        return stateDir.resolve(target + ".state.json");
    }

    public Optional<TargetState> load(String target) {
        Path file = fileFor(target);
        if (!Files.isRegularFile(file)) {
            return Optional.empty();
        }
        try {
            TargetState state = mapper.readValue(Files.readAllBytes(file), TargetState.class);
            if (state == null || !state.isCommitted()) {
                return Optional.empty();
            }
            return Optional.of(state);
        } catch (Exception e) {
            // Corrupted state: never trust it, force re-execution.
            return Optional.empty();
        }
    }

    /** Atomically persist a committed state (write tmp + rename). */
    public synchronized void commit(TargetState state) throws IOException {
        Files.createDirectories(stateDir);
        Path tmp = stateDir.resolve(state.getName() + ".state.tmp");
        Path dst = fileFor(state.getName());
        Files.write(tmp, mapper.writerWithDefaultPrettyPrinter().writeValueAsBytes(state));
        try {
            Files.move(tmp, dst, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
        } catch (java.nio.file.AtomicMoveNotSupportedException e) {
            Files.move(tmp, dst, StandardCopyOption.REPLACE_EXISTING);
        }
    }

    public void clear() throws IOException {
        if (!Files.isDirectory(stateDir)) {
            return;
        }
        try (var stream = Files.list(stateDir)) {
            for (Path p : stream.filter(f -> f.getFileName().toString().endsWith(".state.json")).toList()) {
                Files.deleteIfExists(p);
            }
        }
    }
}
