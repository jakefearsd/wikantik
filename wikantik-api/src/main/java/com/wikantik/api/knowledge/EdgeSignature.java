package com.wikantik.api.knowledge;

import java.util.Locale;
import java.util.Map;

/**
 * Canonical signature for an edge proposal. Combines normalized source,
 * target, and a normalized predicate (with a small synonym map collapsing
 * underscore vs hyphen variants of common predicates).
 */
public record EdgeSignature(String normalizedSource,
                            String normalizedTarget,
                            String normalizedPredicate) {

    private static final Map<String, String> PREDICATE_SYNONYMS = Map.of(
        "is_a",        "is-a",
        "created_by",  "created-by",
        "part_of",     "part-of",
        "depends_on",  "depends-on",
        "located_in",  "located-in",
        "used_by",     "used-by",
        "owned_by",    "owned-by",
        "competes_with","competes-with"
    );

    public EdgeSignature {
        if (normalizedSource == null || normalizedSource.isBlank()) {
            throw new IllegalArgumentException("normalizedSource must not be blank");
        }
        if (normalizedTarget == null || normalizedTarget.isBlank()) {
            throw new IllegalArgumentException("normalizedTarget must not be blank");
        }
        if (normalizedPredicate == null || normalizedPredicate.isBlank()) {
            throw new IllegalArgumentException("normalizedPredicate must not be blank");
        }
    }

    public static EdgeSignature of(String source, String target, String predicate) {
        return new EdgeSignature(NodeSignature.normalize(source),
                                 NodeSignature.normalize(target),
                                 normalizePredicate(predicate));
    }

    public String asHash() {
        return NodeSignature.sha256Hex("edge:" + normalizedSource + "|" + normalizedTarget
                                       + "|" + normalizedPredicate);
    }

    static String normalizePredicate(String predicate) {
        if (predicate == null) {
            throw new IllegalArgumentException("predicate must not be null");
        }
        String lower = predicate.trim().toLowerCase(Locale.ROOT).replaceAll("\\s+", "_");
        return PREDICATE_SYNONYMS.getOrDefault(lower, lower);
    }
}
