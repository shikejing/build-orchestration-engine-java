package com.github.shikejing.buildorchestrationenginejava.state;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;

public class StateStore {
    private static final String SCHEMA = "build-execution-state-v1";

    private final Path stateFile;
    private final ObjectMapper mapper;

    public StateStore(Path stateFile) {
        if (stateFile == null) {
            throw new IllegalArgumentException("stateFile must not be null");
        }
        this.stateFile = stateFile.toAbsolutePath().normalize();
        this.mapper = new ObjectMapper().findAndRegisterModules();
    }

    public ExecutionState load() {
        if (!Files.exists(stateFile)) {
            return new ExecutionState(Map.of());
        }
        try {
            JsonState json = mapper.readValue(Files.readAllBytes(stateFile), JsonState.class);
            if (json == null || !SCHEMA.equals(json.schema())) {
                throw new IOException("Unsupported or missing execution state schema");
            }
            Map<String, TargetStateRecord> records = new LinkedHashMap<>();
            for (Map.Entry<String, JsonTarget> entry : json.targets().entrySet()) {
                JsonTarget value = entry.getValue();
                if (value == null || value.completedAt() == null) {
                    throw new IOException("Invalid state record for target " + entry.getKey());
                }
                records.put(entry.getKey(), new TargetStateRecord(
                        entry.getKey(),
                        value.inputFingerprint(),
                        value.outputFingerprint(),
                        value.actionFingerprint(),
                        value.dependencyFingerprint(),
                        value.cacheKey(),
                        Instant.parse(value.completedAt())
                ));
            }
            return new ExecutionState(Map.copyOf(records));
        } catch (IOException e) {
            throw new UncheckedIOException("Execution state is corrupt: " + stateFile, e);
        }
    }

    public void save(ExecutionState state) {
        if (state == null) {
            throw new IllegalArgumentException("state must not be null");
        }
        try {
            Files.createDirectories(stateFile.getParent());
            Map<String, JsonTarget> targets = new LinkedHashMap<>();
            for (Map.Entry<String, TargetStateRecord> entry : state.targets().entrySet()) {
                TargetStateRecord record = entry.getValue();
                targets.put(entry.getKey(), new JsonTarget(
                        record.inputFingerprint(),
                        record.outputFingerprint(),
                        record.actionFingerprint(),
                        record.dependencyFingerprint(),
                        record.cacheKey(),
                        record.completedAt().toString()
                ));
            }
            JsonState json = new JsonState(SCHEMA, targets);
            Path temporary = stateFile.resolveSibling(".state-" + System.nanoTime() + ".tmp");
            Files.write(temporary, mapper.writerWithDefaultPrettyPrinter().writeValueAsBytes(json));
            try {
                Files.move(temporary, stateFile, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
            } catch (java.nio.file.AtomicMoveNotSupportedException e) {
                Files.move(temporary, stateFile, StandardCopyOption.REPLACE_EXISTING);
            }
        } catch (IOException e) {
            throw new UncheckedIOException("Failed to save execution state", e);
        }
    }

    private record JsonState(String schema, Map<String, JsonTarget> targets) {
        private JsonState {
            targets = targets == null ? Map.of() : Map.copyOf(targets);
        }
    }

    private record JsonTarget(
            String inputFingerprint,
            String outputFingerprint,
            String actionFingerprint,
            String dependencyFingerprint,
            String cacheKey,
            String completedAt
    ) {
    }
}
