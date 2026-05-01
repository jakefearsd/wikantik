package com.wikantik.knowledge.extraction;

import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class EvidenceGroundingVerifierTest {

    private final EvidenceGroundingVerifier v = new EvidenceGroundingVerifier();

    @Test
    void verbatimMatchIsGrounded() {
        EvidenceGroundingVerifier.Decision d = v.evaluate(
            "Python created by Guido", "Programming languages: Python created by Guido in 1991.");
        assertTrue(d.grounded());
        assertEquals("ok", d.reason());
    }

    @Test
    void whitespaceVariantIsGrounded() {
        EvidenceGroundingVerifier.Decision d = v.evaluate(
            "Python  created   by Guido", "Python created by Guido in 1991.");
        assertTrue(d.grounded());
    }

    @Test
    void paraphraseIsRejected() {
        EvidenceGroundingVerifier.Decision d = v.evaluate(
            "Python invented by Guido", "Python created by Guido.");
        assertFalse(d.grounded());
        assertEquals("not_in_page", d.reason());
    }

    @Test
    void emptySpanRejected() {
        EvidenceGroundingVerifier.Decision d = v.evaluate("", "anything");
        assertFalse(d.grounded());
        assertEquals("empty_span", d.reason());
    }

    @Test
    void overlongSpanRejected() {
        String span = "x".repeat(201);
        String body = "x".repeat(500);
        EvidenceGroundingVerifier.Decision d = v.evaluate(span, body);
        assertFalse(d.grounded());
        assertEquals("span_too_long", d.reason());
    }

    @Test
    void nfcEquivalenceMatches() {
        String composed = "Café terrace";
        String decomposed = "Café terrace";
        EvidenceGroundingVerifier.Decision d = v.evaluate(composed,
            "We met at a Café terrace in Paris.");
        assertTrue(d.grounded());
        EvidenceGroundingVerifier.Decision d2 = v.evaluate(decomposed,
            "We met at a Café terrace in Paris.");
        assertTrue(d2.grounded());
    }
}
