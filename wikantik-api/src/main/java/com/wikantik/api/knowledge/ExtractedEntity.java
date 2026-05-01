package com.wikantik.api.knowledge;

/**
 * One entity emitted by a {@link PageExtractor}, post-grounding. The
 * {@code evidenceSpan} is guaranteed to be a substring of the source page
 * body — extractors run the grounding verifier before returning.
 */
public record ExtractedEntity(String name, String type, String evidenceSpan, double confidence) {
    public ExtractedEntity {
        if (name == null || name.isBlank()) {
            throw new IllegalArgumentException("name must not be blank");
        }
        if (type == null || type.isBlank()) {
            throw new IllegalArgumentException("type must not be blank");
        }
        if (evidenceSpan == null) {
            throw new IllegalArgumentException("evidenceSpan must not be null");
        }
        if (confidence < 0.0 || confidence > 1.0) {
            throw new IllegalArgumentException("confidence must be in [0,1], got " + confidence);
        }
    }
}
