package com.github.shikejing.buildorchestrationenginejava.engine.cache;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.github.shikejing.buildorchestrationenginejava.engine.fingerprint.Fingerprint;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Local on-disk cache. Layout per key:
 *   cacheDir/<key>/manifest.json   (key + output path -> sha256)
 *   cacheDir/<key>/blobs/<sha256>  (content-addressed output bytes)
 * Lookups verify every blob against the manifest; any corruption drops the
 * entry so callers fall back to re-execution. Bad cache is never used silently.
 */
public class LocalDiskCache implements BuildCache {

    private final Path cacheDir;
    private final ObjectMapper mapper = new ObjectMapper();

    public LocalDiskCache(Path cacheDir) {
        this.cacheDir = cacheDir;
    }

    private Path entryDir(String key) {
        return cacheDir.resolve(key);
    }

    @Override
    public synchronized void put(String key, Map<String, Path> outputs) throws IOException {
        Path dir = entryDir(key);
        deleteRecursively(dir);
        Path blobs = dir.resolve("blobs");
        Files.createDirectories(blobs);

        CacheEntry entry = new CacheEntry();
        entry.setKey(key);
        entry.setCreatedAtEpochMs(System.currentTimeMillis());
        Map<String, String> hashes = new LinkedHashMap<>();
        for (Map.Entry<String, Path> out : outputs.entrySet()) {
            byte[] bytes = Files.readAllBytes(out.getValue());
            String hash = Fingerprint.ofBytes(bytes);
            Files.write(blobs.resolve(hash), bytes);
            hashes.put(out.getKey(), hash);
        }
        entry.setOutputHashes(hashes);

        Path tmp = dir.resolve("manifest.tmp");
        Files.write(tmp, mapper.writeValueAsBytes(entry));
        try {
            Files.move(tmp, dir.resolve("manifest.json"),
                    StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
        } catch (java.nio.file.AtomicMoveNotSupportedException e) {
            Files.move(tmp, dir.resolve("manifest.json"), StandardCopyOption.REPLACE_EXISTING);
        }
    }

    @Override
    public CacheLookup lookup(String key) {
        Path dir = entryDir(key);
        Path manifest = dir.resolve("manifest.json");
        if (!Files.isRegularFile(manifest)) {
            return CacheLookup.miss();
        }
        try {
            CacheEntry entry = mapper.readValue(Files.readAllBytes(manifest), CacheEntry.class);
            if (entry == null || !key.equals(entry.getKey()) || entry.getOutputHashes().isEmpty()) {
                throw new IOException("manifest key mismatch or empty");
            }
            // Verify every blob exists and matches its recorded hash.
            for (String hash : entry.getOutputHashes().values()) {
                Path blob = dir.resolve("blobs").resolve(hash);
                if (!Files.isRegularFile(blob)
                        || !Fingerprint.ofBytes(Files.readAllBytes(blob)).equals(hash)) {
                    throw new IOException("blob " + hash + " missing or corrupted");
                }
            }
            return CacheLookup.hit(entry);
        } catch (Exception e) {
            // Corrupted entry: drop it, never serve it.
            try {
                deleteRecursively(dir);
            } catch (IOException ignored) {
                // best effort cleanup
            }
            return CacheLookup.corrupt();
        }
    }

    @Override
    public boolean restore(CacheEntry entry, Path outputRoot) throws IOException {
        Path blobs = entryDir(entry.getKey()).resolve("blobs");
        for (Map.Entry<String, String> out : entry.getOutputHashes().entrySet()) {
            Path blob = blobs.resolve(out.getValue());
            if (!Files.isRegularFile(blob)) {
                return false;
            }
            Path dst = outputRoot.resolve(out.getKey()).normalize();
            Files.createDirectories(dst.getParent());
            Files.copy(blob, dst, StandardCopyOption.REPLACE_EXISTING);
            // Verify what we actually wrote; never trust a bad restore.
            if (!Fingerprint.ofBytes(Files.readAllBytes(dst)).equals(out.getValue())) {
                Files.deleteIfExists(dst);
                return false;
            }
        }
        return true;
    }

    @Override
    public synchronized void evictToSize(int maxEntries) throws IOException {
        if (!Files.isDirectory(cacheDir)) {
            return;
        }
        record Entry(Path dir, long createdAt) {}
        List<Entry> entries = new ArrayList<>();
        try (var stream = Files.list(cacheDir)) {
            for (Path dir : stream.filter(Files::isDirectory).toList()) {
                long created = 0;
                try {
                    CacheEntry e = mapper.readValue(
                            Files.readAllBytes(dir.resolve("manifest.json")), CacheEntry.class);
                    created = e.getCreatedAtEpochMs();
                } catch (Exception ignored) {
                    // unparseable entries are evicted first
                }
                entries.add(new Entry(dir, created));
            }
        }
        entries.sort(Comparator.comparingLong(Entry::createdAt));
        int toRemove = entries.size() - maxEntries;
        for (int i = 0; i < toRemove; i++) {
            deleteRecursively(entries.get(i).dir());
        }
    }

    private static void deleteRecursively(Path dir) throws IOException {
        if (!Files.exists(dir)) {
            return;
        }
        try (var walk = Files.walk(dir)) {
            for (Path p : walk.sorted(Comparator.reverseOrder()).toList()) {
                Files.deleteIfExists(p);
            }
        }
    }
}
