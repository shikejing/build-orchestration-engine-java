package com.github.shikejing.buildorchestrationenginejava.state;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;

public class ExecutionJournal {
    private static final String SCHEMA = "build-execution-journal-v1";

    private final Path journalFile;
    private final ObjectMapper mapper;

    public ExecutionJournal(Path journalFile) {
        if (journalFile == null) {
            throw new IllegalArgumentException("journalFile must not be null");
        }
        this.journalFile = journalFile.toAbsolutePath().normalize();
        this.mapper = new ObjectMapper().findAndRegisterModules();
        try {
            Files.createDirectories(this.journalFile.getParent());
        } catch (IOException e) {
            throw new UncheckedIOException("Failed to initialize execution journal", e);
        }
    }

    public TransactionRecord begin(String targetName, String cacheKey, Path stagingDirectory) {
        TransactionRecord record = new TransactionRecord(
                targetName,
                TransactionPhase.RUNNING,
                cacheKey,
                stagingDirectory.toAbsolutePath().normalize().toString(),
                Map.of(),
                Instant.now()
        );
        update(targetName, record);
        return record;
    }

    public TransactionRecord staged(TransactionRecord record) {
        return changePhase(record, TransactionPhase.STAGED);
    }

    public TransactionRecord published(TransactionRecord record) {
        return changePhase(record, TransactionPhase.PUBLISHED);
    }

    private TransactionRecord changePhase(TransactionRecord record, TransactionPhase phase) {
        TransactionRecord updated = new TransactionRecord(
                record.targetName(),
                phase,
                record.cacheKey(),
                record.stagingDirectory(),
                Map.copyOf(record.outputHashes()),
                Instant.now(),
                record.inputFingerprint(),
                record.outputFingerprint(),
                record.actionFingerprint(),
                record.dependencyFingerprint()
        );
        update(record.targetName(), updated);
        return updated;
    }

    public void complete(String targetName) {
        Map<String, JsonTransaction> records = readAll();
        if (records.remove(targetName) != null) {
            writeAll(records);
        }
    }

    public TransactionRecord find(String targetName) {
        JsonTransaction json = readAll().get(targetName);
        if (json == null) {
            return null;
        }
        return new TransactionRecord(
                targetName,
                TransactionPhase.valueOf(json.phase()),
                json.cacheKey(),
                json.stagingDirectory(),
                json.outputHashes() == null ? Map.of() : Map.copyOf(json.outputHashes()),
                Instant.parse(json.updatedAt()),
                json.inputFingerprint(),
                json.outputFingerprint(),
                json.actionFingerprint(),
                json.dependencyFingerprint()
        );
    }

    private void update(String targetName, TransactionRecord record) {
        Map<String, JsonTransaction> records = readAll();
        records.put(targetName, new JsonTransaction(
                record.phase().name(),
                record.cacheKey(),
                record.stagingDirectory(),
                record.outputHashes(),
                record.updatedAt().toString(),
                record.inputFingerprint(),
                record.outputFingerprint(),
                record.actionFingerprint(),
                record.dependencyFingerprint()
        ));
        writeAll(records);
    }

    private Map<String, JsonTransaction> readAll() {
        if (!Files.exists(journalFile)) {
            return new LinkedHashMap<>();
        }
        try {
            JsonJournal journal = mapper.readValue(Files.readAllBytes(journalFile), JsonJournal.class);
            if (journal == null || !SCHEMA.equals(journal.schema()) || journal.targets() == null) {
                throw new IOException("Invalid execution journal schema");
            }
            return new LinkedHashMap<>(journal.targets());
        } catch (IOException e) {
            throw new UncheckedIOException("Execution journal is corrupt: " + journalFile, e);
        }
    }

    private void writeAll(Map<String, JsonTransaction> records) {
        try {
            Path temporary = journalFile.resolveSibling(".journal-" + System.nanoTime() + ".tmp");
            mapper.writerWithDefaultPrettyPrinter().writeValue(temporary.toFile(),
                    new JsonJournal(SCHEMA, new LinkedHashMap<>(records)));
            try {
                Files.move(temporary, journalFile, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
            } catch (AtomicMoveNotSupportedException e) {
                Files.move(temporary, journalFile, StandardCopyOption.REPLACE_EXISTING);
            }
        } catch (IOException e) {
            throw new UncheckedIOException("Failed to update execution journal", e);
        }
    }

    private record JsonJournal(String schema, Map<String, JsonTransaction> targets) {
    }

    private record JsonTransaction(
            String phase,
            String cacheKey,
            String stagingDirectory,
            Map<String, String> outputHashes,
            String updatedAt,
            String inputFingerprint,
            String outputFingerprint,
            String actionFingerprint,
            String dependencyFingerprint
    ) {
    }
}
