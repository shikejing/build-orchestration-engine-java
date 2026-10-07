package com.github.shikejing.buildorchestrationenginejava.cache;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.github.shikejing.buildorchestrationenginejava.config.CacheRetention;
import com.github.shikejing.buildorchestrationenginejava.fingerprint.FingerprintService;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.FileVisitResult;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.SimpleFileVisitor;
import java.nio.file.StandardCopyOption;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.attribute.BasicFileAttributes;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.TreeMap;
import java.util.stream.Stream;

public class LocalArtifactCache implements ArtifactCache {
    private static final String SCHEMA = "build-cache-entry-v1";
    private static final String MANIFEST = "manifest.json";

    private final Path cacheRoot;
    private final Path entriesRoot;
    private final Path temporaryRoot;
    private final ObjectMapper mapper;
    private final FingerprintService fingerprints;

    public LocalArtifactCache(Path cacheRoot) {
        try {
            this.cacheRoot = cacheRoot.toAbsolutePath().normalize();
            this.entriesRoot = this.cacheRoot.resolve("entries");
            this.temporaryRoot = this.cacheRoot.resolve("tmp");
            Files.createDirectories(entriesRoot);
            Files.createDirectories(temporaryRoot);
            this.mapper = new ObjectMapper().findAndRegisterModules();
            this.fingerprints = new FingerprintService();
        } catch (IOException e) {
            throw new UncheckedIOException("Failed to initialize artifact cache at " + cacheRoot, e);
        }
    }

    @Override
    public CacheLookupResult inspect(CacheKey key, String targetName) {
        Path entryDirectory = entryDirectory(key);
        if (!Files.isDirectory(entryDirectory)) {
            return CacheLookupResult.missing();
        }
        try {
            return CacheLookupResult.hit(loadAndVerify(entryDirectory, key, targetName));
        } catch (Exception e) {
            return CacheLookupResult.invalid();
        }
    }

    @Override
    public void store(CacheKey key, String targetName, Path stagingRoot, List<String> relativeOutputs) {
        if (key == null || targetName == null || stagingRoot == null || relativeOutputs == null) {
            throw new IllegalArgumentException("cache store arguments must not be null");
        }
        Path prepared = temporaryRoot.resolve(key.value() + "-" + System.nanoTime());
        try {
            Files.createDirectories(prepared);
            Map<String, String> hashes = new TreeMap<>();
            for (String output : relativeOutputs.stream().sorted().distinct().toList()) {
                Path source = safeResolve(stagingRoot, output);
                if (!Files.isRegularFile(source, LinkOption.NOFOLLOW_LINKS)) {
                    throw new IOException("Missing regular output file in staging: " + output);
                }
                Path destination = safeResolve(prepared, output);
                Files.createDirectories(destination.getParent());
                Files.copy(source, destination, StandardCopyOption.COPY_ATTRIBUTES);
                hashes.put(normalize(output), fingerprints.fingerprintFile(prepared, output).sha256());
            }
            CacheEntry entry = new CacheEntry(key.value(), targetName, Map.copyOf(hashes), Instant.now(), null);
            writeManifest(prepared.resolve(MANIFEST), entry);
            publishPrepared(prepared, entryDirectory(key));
        } catch (IOException e) {
            deleteQuietly(prepared);
            throw new UncheckedIOException("Failed to store cache entry for target " + targetName, e);
        }
    }

    @Override
    public void restore(CacheEntry entry, Path destinationRoot) {
        if (entry == null || destinationRoot == null) {
            throw new IllegalArgumentException("entry and destinationRoot must not be null");
        }
        Path entryDirectory = entriesRoot.resolve(entry.key());
        try {
            CacheEntry verified = loadAndVerify(entryDirectory, new CacheKey(entry.key(), Map.of()), entry.targetName());
            if (!verified.equals(entry) && entry.manifestChecksum() != null) {
                throw new IOException("Provided cache entry does not match stored manifest");
            }
            Files.createDirectories(destinationRoot);
            for (String relativeOutput : verified.outputHashes().keySet()) {
                Path source = safeResolve(entryDirectory, relativeOutput);
                Path destination = safeResolve(destinationRoot, relativeOutput);
                if (!Files.isRegularFile(source, LinkOption.NOFOLLOW_LINKS)) {
                    throw new IOException("Cached output is missing: " + relativeOutput);
                }
                Files.createDirectories(destination.getParent());
                Files.copy(source, destination, StandardCopyOption.COPY_ATTRIBUTES, StandardCopyOption.REPLACE_EXISTING);
                if (!fingerprints.fingerprintFile(destinationRoot, relativeOutput).sha256()
                        .equals(verified.outputHashes().get(relativeOutput))) {
                    throw new IOException("Restored output failed checksum verification: " + relativeOutput);
                }
            }
        } catch (IOException | RuntimeException e) {
            deleteQuietly(destinationRoot);
            throw new IllegalStateException("Cannot restore corrupt cache entry " + entry.key(), e);
        }
    }

    @Override
    public void prune(CacheRetention retention) {
        if (retention == null) {
            return;
        }
        Instant cutoff = retention.maxAge() == null ? Instant.EPOCH : Instant.now().minus(retention.maxAge());
        List<Path> invalid = new ArrayList<>();
        List<CacheMetadata> metadata = new ArrayList<>();
        try (Stream<Path> directories = Files.list(entriesRoot)) {
            for (Path directory : directories.filter(Files::isDirectory).toList()) {
                try {
                    JsonNode node = readManifestNode(directory);
                    String key = node.path("key").asText(null);
                    String target = node.path("targetName").asText(null);
                    Instant createdAt = Instant.parse(node.path("createdAt").asText());
                    if (!directory.getFileName().toString().equals(key) || key == null || target == null
                            || createdAt.isBefore(cutoff)) {
                        invalid.add(directory);
                    } else {
                        metadata.add(new CacheMetadata(directory, createdAt));
                    }
                } catch (Exception e) {
                    invalid.add(directory);
                }
            }
        } catch (IOException e) {
            throw new UncheckedIOException("Failed to inspect cache entries", e);
        }
        invalid.forEach(this::deleteQuietly);
        if (retention.maxEntries() != null && metadata.size() > retention.maxEntries()) {
            metadata.sort(Comparator.comparing(CacheMetadata::createdAt).thenComparing(m -> m.directory().getFileName().toString()));
            int removeCount = metadata.size() - retention.maxEntries().intValue();
            metadata.stream().limit(removeCount).map(CacheMetadata::directory).forEach(this::deleteQuietly);
        }
    }

    private CacheEntry loadAndVerify(Path entryDirectory, CacheKey expectedKey, String expectedTarget) throws IOException {
        JsonNode root = readManifestNode(entryDirectory);
        JsonNode withoutChecksum = root.deepCopy();
        String storedChecksum = withoutChecksum.path("manifestChecksum").asText(null);
        ((ObjectNode) withoutChecksum).remove("manifestChecksum");
        String actualChecksum = fingerprints.hashText(mapper.writeValueAsString(withoutChecksum));
        if (storedChecksum == null || !storedChecksum.equals(actualChecksum)) {
            throw new IOException("Cache manifest checksum mismatch");
        }
        String key = root.path("key").asText(null);
        String targetName = root.path("targetName").asText(null);
        if (key == null || targetName == null || !entryDirectory.getFileName().toString().equals(key)
                || !key.equals(expectedKey.value()) || !targetName.equals(expectedTarget)) {
            throw new IOException("Cache key or target mapping is invalid");
        }
        Map<String, String> hashes = new TreeMap<>();
        root.path("outputHashes").fields().forEachRemaining(entry -> hashes.put(entry.getKey(), entry.getValue().asText()));
        if (hashes.isEmpty()) {
            throw new IOException("Cache manifest contains no outputs");
        }
        for (Map.Entry<String, String> output : hashes.entrySet()) {
            Path file = safeResolve(entryDirectory, output.getKey());
            if (!Files.isRegularFile(file, LinkOption.NOFOLLOW_LINKS)) {
                throw new IOException("Cached file is missing or is not a regular file: " + output.getKey());
            }
            String actualHash = fingerprints.fingerprintFile(entryDirectory, output.getKey()).sha256();
            if (!actualHash.equals(output.getValue())) {
                throw new IOException("Cached file checksum mismatch: " + output.getKey());
            }
        }
        return new CacheEntry(key, targetName, Map.copyOf(hashes),
                Instant.parse(root.path("createdAt").asText()), storedChecksum);
    }

    private JsonNode readManifestNode(Path entryDirectory) throws IOException {
        Path manifest = entryDirectory.resolve(MANIFEST);
        if (!Files.isRegularFile(manifest, LinkOption.NOFOLLOW_LINKS)) {
            throw new IOException("Cache manifest is missing");
        }
        JsonNode root = mapper.readTree(Files.readAllBytes(manifest));
        if (!SCHEMA.equals(root.path("schema").asText(null))) {
            throw new IOException("Unsupported cache schema");
        }
        return root;
    }

    private void writeManifest(Path manifest, CacheEntry entry) throws IOException {
        ObjectNode root = mapper.createObjectNode();
        root.put("schema", SCHEMA);
        root.put("key", entry.key());
        root.put("targetName", entry.targetName());
        root.put("createdAt", entry.createdAt().toString());
        ObjectNode outputs = root.putObject("outputHashes");
        new TreeMap<>(entry.outputHashes()).forEach(outputs::put);
        String checksum = fingerprints.hashText(mapper.writeValueAsString(root));
        root.put("manifestChecksum", checksum);
        Files.write(manifest, mapper.writerWithDefaultPrettyPrinter().writeValueAsBytes(root));
    }

    private void publishPrepared(Path prepared, Path destination) throws IOException {
        Path old = destination.resolveSibling(destination.getFileName() + "-old-" + System.nanoTime());
        boolean replaced = Files.isDirectory(destination);
        if (replaced) {
            Files.move(destination, old, StandardCopyOption.ATOMIC_MOVE);
        }
        try {
            Files.move(prepared, destination, StandardCopyOption.ATOMIC_MOVE);
            if (replaced) {
                deleteQuietly(old);
            }
        } catch (IOException | RuntimeException moveError) {
            if (replaced && Files.exists(old) && !Files.exists(destination)) {
                Files.move(old, destination, StandardCopyOption.ATOMIC_MOVE);
            }
            throw moveError;
        }
    }

    private Path entryDirectory(CacheKey key) {
        return entriesRoot.resolve(key.value());
    }

    private Path safeResolve(Path root, String relativePath) throws IOException {
        String normalized = normalize(relativePath);
        Path resolved = root.resolve(normalized).normalize();
        Path normalizedRoot = root.toAbsolutePath().normalize();
        if (!resolved.toAbsolutePath().normalize().startsWith(normalizedRoot)) {
            throw new IOException("Path escapes cache directory: " + relativePath);
        }
        return resolved;
    }

    private String normalize(String path) {
        return path.replace('\\', '/');
    }

    private void deleteQuietly(Path path) {
        if (path == null || !Files.exists(path, LinkOption.NOFOLLOW_LINKS)) {
            return;
        }
        try {
            Files.walkFileTree(path, new SimpleFileVisitor<>() {
                @Override
                public FileVisitResult visitFile(Path file, BasicFileAttributes attrs) throws IOException {
                    Files.delete(file);
                    return FileVisitResult.CONTINUE;
                }

                @Override
                public FileVisitResult postVisitDirectory(Path directory, IOException exc) throws IOException {
                    Files.delete(directory);
                    return FileVisitResult.CONTINUE;
                }
            });
        } catch (IOException ignored) {
        }
    }

    private record CacheMetadata(Path directory, Instant createdAt) {
    }
}
