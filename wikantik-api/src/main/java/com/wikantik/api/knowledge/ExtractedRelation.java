package com.wikantik.api.knowledge;

public record ExtractedRelation(String source, String target, String predicate,
                                String evidenceSpan, double confidence) {
    public ExtractedRelation {
        if (source == null || source.isBlank()) {
            throw new IllegalArgumentException("source must not be blank");
        }
        if (target == null || target.isBlank()) {
            throw new IllegalArgumentException("target must not be blank");
        }
        if (predicate == null || predicate.isBlank()) {
            throw new IllegalArgumentException("predicate must not be blank");
        }
        if (evidenceSpan == null) {
            throw new IllegalArgumentException("evidenceSpan must not be null");
        }
        if (confidence < 0.0 || confidence > 1.0) {
            throw new IllegalArgumentException("confidence must be in [0,1], got " + confidence);
        }
    }
}
