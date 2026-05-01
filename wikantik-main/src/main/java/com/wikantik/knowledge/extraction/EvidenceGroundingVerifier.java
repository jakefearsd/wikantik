package com.wikantik.knowledge.extraction;

import java.text.Normalizer;

/**
 * Bright-line filter against hallucinated entities/relations: an
 * evidence_span must be a verbatim substring of the source page (after NFC
 * + whitespace normalization) and shorter than 200 characters.
 *
 * <p>Pure function; no LLM, no I/O. Decisions are paired with a stable
 * reason code so {@code wikantik_kg_extractor_rejected_total{reason=...}}
 * gauges work.
 */
public final class EvidenceGroundingVerifier {

    private static final int MAX_SPAN_LEN = 200;

    public Decision evaluate(String evidenceSpan, String pageBody) {
        if (evidenceSpan == null || evidenceSpan.isBlank()) {
            return new Decision(false, "empty_span");
        }
        if (evidenceSpan.length() > MAX_SPAN_LEN) {
            return new Decision(false, "span_too_long");
        }
        String spanNorm = normalize(evidenceSpan);
        String bodyNorm = normalize(pageBody);
        return bodyNorm.contains(spanNorm)
            ? new Decision(true,  "ok")
            : new Decision(false, "not_in_page");
    }

    private static String normalize(String s) {
        if (s == null) return "";
        return Normalizer.normalize(s, Normalizer.Form.NFC).replaceAll("\\s+", " ").trim();
    }

    public record Decision(boolean grounded, String reason) {}
}
