package com.github.shikejing.buildorchestrationenginejava.fingerprint;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.List;

public class FingerprintService {
    private static final int BUFFER_SIZE = 8192;

    public String hashFiles(Path root, List<String> relativePaths) {
        if (root == null) {
            throw new IllegalArgumentException("root must not be null");
        }
        List<String> ordered = relativePaths == null ? List.of() : relativePaths.stream()
                .filter(path -> path != null && !path.isBlank())
                .map(path -> path.replace('\\', '/'))
                .sorted()
                .distinct()
                .toList();
        try {
            MessageDigest aggregate = newSha256();
            for (String relativePath : ordered) {
                FileFingerprint fingerprint = fingerprintFile(root, relativePath);
                updateText(aggregate, "entry\n");
                updateText(aggregate, "path=" + fingerprint.relativePath() + "\n");
                updateText(aggregate, "size=" + fingerprint.size() + "\n");
                updateText(aggregate, "sha256=" + fingerprint.sha256() + "\n");
            }
            updateText(aggregate, "count=" + ordered.size());
            return HexFormat.of().formatHex(aggregate.digest());
        } catch (IOException e) {
            throw new UncheckedIOException("Failed to calculate file fingerprint under " + root, e);
        }
    }

    public FileFingerprint fingerprintFile(Path root, String relativePath) throws IOException {
        String normalized = normalizeRelative(relativePath);
        Path file = root.resolve(normalized).normalize();
        Path normalizedRoot = root.toAbsolutePath().normalize();
        if (!file.toAbsolutePath().normalize().startsWith(normalizedRoot)) {
            throw new IOException("Path escapes workspace: " + relativePath);
        }
        if (!Files.exists(file, LinkOption.NOFOLLOW_LINKS) || Files.isDirectory(file, LinkOption.NOFOLLOW_LINKS)) {
            String missingHash = hashString("MISSING:" + normalized);
            return new FileFingerprint(normalized, missingHash, 0);
        }
        if (Files.isSymbolicLink(file)) {
            throw new IOException("Symbolic links are not allowed as fingerprinted paths: " + relativePath);
        }
        long size = Files.size(file);
        MessageDigest digest = newSha256();
        try (var input = Files.newInputStream(file)) {
            byte[] buffer = new byte[BUFFER_SIZE];
            int read;
            while ((read = input.read(buffer)) >= 0) {
                if (read > 0) {
                    digest.update(buffer, 0, read);
                }
            }
        }
        return new FileFingerprint(normalized, HexFormat.of().formatHex(digest.digest()), size);
    }

    public String hashText(String text) {
        return hashString(text == null ? "null" : text);
    }

    private String hashString(String text) {
        MessageDigest digest = newSha256();
        updateText(digest, text);
        return HexFormat.of().formatHex(digest.digest());
    }

    private void updateText(MessageDigest digest, String text) {
        digest.update(text.getBytes(StandardCharsets.UTF_8));
    }

    private String normalizeRelative(String relativePath) {
        String normalized = relativePath.replace('\\', '/');
        Path path = Path.of(normalized).normalize();
        if (path.isAbsolute() || path.startsWith("..")) {
            throw new IllegalArgumentException("Relative path must stay inside workspace: " + relativePath);
        }
        return path.toString().replace('\\', '/');
    }

    private MessageDigest newSha256() {
        try {
            return MessageDigest.getInstance("SHA-256");
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 digest is unavailable", e);
        }
    }
}
