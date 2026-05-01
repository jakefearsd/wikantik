package com.wikantik.api.knowledge;

/**
 * One piece of evidence backing a consolidated proposal: which page produced
 * it, the verbatim quote (already grounded by EvidenceGroundingVerifier), the
 * model's confidence, and the extractor that emitted it.
 */
public record SupportEvidence(String sourcePage,
                              String evidenceSpan,
                              double confidence,
                              String extractorCode) {
    public SupportEvidence {
        if (sourcePage == null || sourcePage.isBlank()) {
            throw new IllegalArgumentException("sourcePage must not be blank");
        }
        if (evidenceSpan == null) {
            throw new IllegalArgumentException("evidenceSpan must not be null");
        }
        if (extractorCode == null || extractorCode.isBlank()) {
            throw new IllegalArgumentException("extractorCode must not be blank");
        }
        if (confidence < 0.0 || confidence > 1.0) {
            throw new IllegalArgumentException("confidence must be in [0,1], got " + confidence);
        }
    }
}
