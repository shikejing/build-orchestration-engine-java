package com.github.shikejing.buildorchestrationenginejava.execution;

import java.io.IOException;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

public class AtomicArtifactPublisher {
    public void publish(Path stagingRoot, Path workspaceRoot, List<String> relativeOutputs) {
        if (stagingRoot == null || workspaceRoot == null || relativeOutputs == null) {
            throw new IllegalArgumentException("publish arguments must not be null");
        }
        List<String> normalizedOutputs = relativeOutputs.stream().sorted().distinct().toList();
        Map<String, Path> backups = new HashMap<>();
        List<String> published = new ArrayList<>();
        try {
            Files.createDirectories(workspaceRoot);
            for (String relativeOutput : normalizedOutputs) {
                Path source = safeResolve(stagingRoot, relativeOutput);
                Path target = safeResolve(workspaceRoot, relativeOutput);
                if (!Files.isRegularFile(source, LinkOption.NOFOLLOW_LINKS)) {
                    throw new IOException("Staged output is missing: " + relativeOutput);
                }
                Files.createDirectories(target.getParent());
                if (Files.isSymbolicLink(target)) {
                    throw new IOException("Refusing to replace symbolic link output: " + relativeOutput);
                }
                if (Files.exists(target, LinkOption.NOFOLLOW_LINKS)) {
                    Path backup = target.resolveSibling(target.getFileName() + ".backup-" + System.nanoTime());
                    Files.move(target, backup);
                    backups.put(relativeOutput, backup);
                }
                moveAtomically(source, target);
                published.add(relativeOutput);
            }
            backups.values().forEach(this::deleteQuietly);
        } catch (IOException | RuntimeException e) {
            rollback(normalizedOutputs, published, backups, stagingRoot, workspaceRoot);
            throw new IllegalStateException("Atomic publish failed; workspace outputs were rolled back", e);
        }
    }

    private void moveAtomically(Path source, Path target) throws IOException {
        try {
            Files.move(source, target, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
        } catch (AtomicMoveNotSupportedException e) {
            Files.move(source, target, StandardCopyOption.REPLACE_EXISTING);
        }
    }

    private void rollback(List<String> normalizedOutputs, List<String> published,
                          Map<String, Path> backups, Path stagingRoot, Path workspaceRoot) {
        Set<String> publishedSet = new HashSet<>(published);
        for (String relativeOutput : normalizedOutputs) {
            Path target;
            try {
                target = safeResolve(workspaceRoot, relativeOutput);
            } catch (IOException ignored) {
                continue;
            }
            Path backup = backups.get(relativeOutput);
            if (publishedSet.contains(relativeOutput)) {
                if (backup != null && Files.exists(backup)) {
                    try {
                        moveAtomically(backup, target);
                    } catch (IOException ignored) {
                    }
                } else {
                    deleteQuietly(target);
                }
            }
            try {
                Path staged = safeResolve(stagingRoot, relativeOutput);
                if (!Files.exists(staged) && Files.exists(target)) {
                    Files.copy(target, staged);
                }
            } catch (IOException ignored) {
            }
            deleteEmptyParents(target, workspaceRoot);
        }
        backups.values().forEach(this::deleteQuietly);
    }

    private Path safeResolve(Path root, String relativePath) throws IOException {
        String normalized = relativePath.replace('\\', '/');
        Path path = Path.of(normalized);
        if (path.isAbsolute() || path.normalize().startsWith("..")) {
            throw new IOException("Relative output escapes root: " + relativePath);
        }
        Path resolved = root.resolve(path).normalize();
        Path normalizedRoot = root.toAbsolutePath().normalize();
        if (!resolved.toAbsolutePath().normalize().startsWith(normalizedRoot)) {
            throw new IOException("Relative output escapes root: " + relativePath);
        }
        return resolved;
    }

    private void deleteEmptyParents(Path file, Path root) {
        Path parent = file.getParent();
        while (parent != null && parent.startsWith(root) && !parent.equals(root)) {
            try {
                Files.deleteIfExists(parent);
            } catch (IOException e) {
                return;
            }
            parent = parent.getParent();
        }
    }

    private void deleteQuietly(Path path) {
        try {
            Files.deleteIfExists(path);
        } catch (IOException ignored) {
        }
    }
}
