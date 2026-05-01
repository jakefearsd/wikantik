package com.wikantik.api.knowledge;

import org.junit.jupiter.api.Test;
import java.util.List;
import static org.junit.jupiter.api.Assertions.*;

class ConsolidatedProposalTest {

    @Test
    void newNode_holdsTypeAndName() {
        SupportEvidence e = new SupportEvidence("Pg1", "Python is a language.", 0.9, "ollama:gemma4");
        ConsolidatedProposal p = ConsolidatedProposal.newNode(
            "sig123", "Python", "Technology", List.of(e), 0.9);
        assertEquals(ConsolidatedProposal.Kind.NEW_NODE, p.kind());
        assertEquals("Python", p.displayName());
        assertEquals("Technology", p.type());
        assertNull(p.source());
        assertEquals(1, p.support().size());
    }

    @Test
    void newEdge_holdsTriple() {
        SupportEvidence e = new SupportEvidence("Pg1", "Python created by Guido.", 0.95, "ollama:gemma4");
        ConsolidatedProposal p = ConsolidatedProposal.newEdge(
            "sig456", "Python", "Guido van Rossum", "created-by", List.of(e), 0.95);
        assertEquals(ConsolidatedProposal.Kind.NEW_EDGE, p.kind());
        assertEquals("Python", p.source());
        assertEquals("Guido van Rossum", p.target());
        assertEquals("created-by", p.predicate());
        assertNull(p.type());
    }

    @Test
    void verdict_acceptCarriesConfidenceAndRationale() {
        Verdict.Accept a = new Verdict.Accept(0.8, "evidence is solid");
        assertEquals(0.8, a.finalConfidence());
        assertEquals("evidence is solid", a.rationale());
    }

    @Test
    void verdict_rejectCarriesReasonCode() {
        Verdict.Reject r = new Verdict.Reject("ungrounded", "quote not in page");
        assertEquals("ungrounded", r.reasonCode());
    }
}
