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
        // NFC → strip wrapper noise from the outer edges, preserving
        // identifier-bearing punctuation (.NET, C++, C#, @user) → collapse
        // internal whitespace → trim → lowercase. The collapse + trim happen
        // last because edge stripping can re-expose whitespace.
        String nfc = Normalizer.normalize(s, Normalizer.Form.NFC);
        String stripped = stripEdgeNoise(nfc);
        String collapsed = stripped.replaceAll("\\s+", " ").trim();
        return collapsed.toLowerCase(Locale.ROOT);
    }

    /**
     * Trims wrapper/sentence punctuation and whitespace from the edges while
     * preserving identifier-bearing prefixes/suffixes adjacent to alphanumeric
     * content. Examples: {@code ".NET"} keeps its leading dot; {@code "C++"}
     * and {@code "C#"} keep their trailing {@code +}/{@code #}; {@code "GitHub."}
     * loses its trailing dot; {@code " ., GitHub .,  "} reduces to
     * {@code "GitHub"}.
     */
    static String stripEdgeNoise(String s) {
        int start = 0;
        int end = s.length();
        while (start < end) {
            char c = s.charAt(start);
            if (Character.isLetterOrDigit(c)) break;
            // Preserve a leading identifier prefix (.NET, #tag, @user) when the
            // very next char is alphanumeric.
            if ((c == '.' || c == '#' || c == '@')
                && start + 1 < end
                && Character.isLetterOrDigit(s.charAt(start + 1))) {
                break;
            }
            start++;
        }
        while (end > start) {
            char c = s.charAt(end - 1);
            if (Character.isLetterOrDigit(c)) break;
            // Preserve a trailing identifier suffix (++ on C++, # on C#) when
            // the prior char is alphanumeric or another instance of the same
            // suffix character (so "C++" keeps both '+' chars).
            if ((c == '+' || c == '#')
                && end - 2 >= start
                && (Character.isLetterOrDigit(s.charAt(end - 2)) || s.charAt(end - 2) == c)) {
                break;
            }
            end--;
        }
        return s.substring(start, end);
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
