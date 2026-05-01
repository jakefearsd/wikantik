package com.wikantik.knowledge.extraction;

import com.wikantik.api.knowledge.*;
import org.junit.jupiter.api.Test;
import java.time.Duration;
import java.util.List;
import static org.junit.jupiter.api.Assertions.*;

class ProposalConsolidatorTest {

    private final ProposalConsolidator consolidator = new ProposalConsolidator();

    @Test
    void duplicateEntitiesCollapseToOneProposalWithMultipleSupport() {
        PageExtractionResult r1 = pageResult("Page1", "Python is a language.",
            new ExtractedEntity("Python", "Technology", "Python is a language.", 0.9));
        PageExtractionResult r2 = pageResult("Page2", "I learned Python.",
            new ExtractedEntity("python", "technology", "I learned Python.", 0.8));
        List<ConsolidatedProposal> out = consolidator.consolidate(List.of(r1, r2).stream());
        assertEquals(1, out.size());
        ConsolidatedProposal p = out.get(0);
        assertEquals(ConsolidatedProposal.Kind.NEW_NODE, p.kind());
        assertEquals(2, p.support().size());
        assertEquals("Python", p.displayName());
    }

    @Test
    void displayNameVoteWinsByCount() {
        PageExtractionResult r1 = pageResult("P1", "GitHub", new ExtractedEntity("GitHub", "Organization", "GitHub", 0.9));
        PageExtractionResult r2 = pageResult("P2", "GitHub", new ExtractedEntity("GitHub", "Organization", "GitHub", 0.9));
        PageExtractionResult r3 = pageResult("P3", "github", new ExtractedEntity("github", "Organization", "github", 0.9));
        List<ConsolidatedProposal> out = consolidator.consolidate(List.of(r1, r2, r3).stream());
        assertEquals(1, out.size());
        assertEquals("GitHub", out.get(0).displayName());
    }

    @Test
    void differentTypesProduceSeparateProposals() {
        PageExtractionResult r1 = pageResult("P1", "Java island", new ExtractedEntity("Java", "Place", "Java island", 0.9));
        PageExtractionResult r2 = pageResult("P2", "Java code", new ExtractedEntity("Java", "Technology", "Java code", 0.9));
        List<ConsolidatedProposal> out = consolidator.consolidate(List.of(r1, r2).stream());
        assertEquals(2, out.size());
    }

    @Test
    void edgesConsolidateOnSourceTargetPredicate() {
        PageExtractionResult r1 = pageResult("P1", "Python created by Guido.",
            new ExtractedEntity("Python", "Technology", "Python", 0.9),
            new ExtractedEntity("Guido", "Person", "Guido", 0.9));
        r1 = withRelation(r1, new ExtractedRelation("Python", "Guido", "created_by", "Python created by Guido.", 0.95));
        PageExtractionResult r2 = pageResult("P2", "Python was created by Guido in 1991.",
            new ExtractedEntity("Python", "Technology", "Python", 0.9),
            new ExtractedEntity("Guido", "Person", "Guido", 0.9));
        r2 = withRelation(r2, new ExtractedRelation("python", "guido", "created-by", "Python was created by Guido", 0.85));
        List<ConsolidatedProposal> out = consolidator.consolidate(List.of(r1, r2).stream());
        assertEquals(3, out.size());
        ConsolidatedProposal edge = out.stream().filter(p -> p.kind() == ConsolidatedProposal.Kind.NEW_EDGE).findFirst().orElseThrow();
        assertEquals(2, edge.support().size());
    }

    @Test
    void aggregateConfidenceIsMeanOfSupports() {
        PageExtractionResult r1 = pageResult("P1", "x", new ExtractedEntity("X", "Concept", "x", 1.0));
        PageExtractionResult r2 = pageResult("P2", "x", new ExtractedEntity("X", "Concept", "x", 0.5));
        List<ConsolidatedProposal> out = consolidator.consolidate(List.of(r1, r2).stream());
        assertEquals(0.75, out.get(0).aggregateConfidence(), 0.001);
    }

    private PageExtractionResult pageResult(String page, String body, ExtractedEntity... ents) {
        return new PageExtractionResult("ollama:fake", page, List.of(ents), List.of(),
            new PageExtractionResult.Stats(ents.length, 0, 0, 0, Duration.ZERO));
    }

    private PageExtractionResult withRelation(PageExtractionResult r, ExtractedRelation rel) {
        return new PageExtractionResult(r.extractorCode(), r.pageName(), r.entities(), List.of(rel), r.stats());
    }
}
