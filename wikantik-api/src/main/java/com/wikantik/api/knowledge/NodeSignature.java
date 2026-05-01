package com.wikantik.api.knowledge;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.text.Normalizer;
import java.util.HexFormat;
import java.util.Locale;

/**
 * Canonical signature for a node proposal. Two proposals with the same
 * (normalized name, normalized type) collapse into the same row in
 * {@code kg_proposals} via the partial unique index on {@code signature}.
 *
 * <p>Normalization rules: NFC-normalize, trim, collapse internal whitespace,
 * strip surrounding punctuation, lower-case for comparison. The original
 * casing is preserved by the consolidator's display-name vote — this class
 * cares only about identity.
 */
public record NodeSignature(String normalizedName, String normalizedType) {

    public NodeSignature {
        if (normalizedName == null || normalizedName.isBlank()) {
            throw new IllegalArgumentException("normalizedName must not be blank");
        }
        if (normalizedType == null || normalizedType.isBlank()) {
            throw new IllegalArgumentException("normalizedType must not be blank");
        }
    }

    public static NodeSignature of(String name, String type) {
        return new NodeSignature(normalize(name), normalize(type));
    }

    public String asHash() {
        return sha256Hex("node:" + normalizedName + "|" + normalizedType);
    }

    static String normalize(String s) {
        if (s == null) {
            throw new IllegalArgumentException("input must not be null");
        }
        String nfc = Normalizer.normalize(s, Normalizer.Form.NFC);
        String trimmed = nfc.trim();
        trimmed = trimmed.replaceAll("^[\\p{Punct}]+|[\\p{Punct}]+$", "");
        String collapsed = trimmed.replaceAll("\\s+", " ");
        return collapsed.toLowerCase(Locale.ROOT);
    }

    static String sha256Hex(String input) {
        try {
            MessageDigest md = MessageDigest.getInstance("SHA-256");
            byte[] digest = md.digest(input.getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(digest);
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 unavailable", e);
        }
    }
}
