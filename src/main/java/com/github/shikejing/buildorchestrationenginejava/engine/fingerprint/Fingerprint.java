package com.github.shikejing.buildorchestrationenginejava.engine.fingerprint;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;

/** SHA-256 fingerprint builder for inputs, actions and toolchains. */
public final class Fingerprint {

    private Fingerprint() {}

    public static String ofBytes(byte[] data) {
        return hex(digest().digest(data));
    }

    /** Hash a sequence of parts with length prefixes so concatenation is unambiguous. */
    public static String ofStrings(Iterable<String> parts) {
        MessageDigest md = digest();
        for (String part : parts) {
            byte[] bytes = part.getBytes(StandardCharsets.UTF_8);
            md.update((byte) (bytes.length >>> 24));
            md.update((byte) (bytes.length >>> 16));
            md.update((byte) (bytes.length >>> 8));
            md.update((byte) bytes.length);
            md.update(bytes);
        }
        return hex(md.digest());
    }

    public static MessageDigest digest() {
        try {
            return MessageDigest.getInstance("SHA-256");
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 unavailable", e);
        }
    }

    public static String hex(byte[] bytes) {
        StringBuilder sb = new StringBuilder(bytes.length * 2);
        for (byte b : bytes) {
            sb.append(Character.forDigit((b >> 4) & 0xF, 16));
            sb.append(Character.forDigit(b & 0xF, 16));
        }
        return sb.toString();
    }
}
