package com.wikantik.api.knowledge;

import org.junit.jupiter.api.Test;
import java.util.List;
import java.util.Map;
import java.time.Duration;
import static org.junit.jupiter.api.Assertions.*;

class PageExtractorTest {

    @Test
    void canImplementInterfaceWithFake() {
        PageExtractor fake = new PageExtractor() {
            public String code() { return "fake"; }
            public PageExtractionResult extract(Page page, ExtractionContext ctx) {
                return PageExtractionResult.empty("fake", page.name(), Duration.ZERO);
            }
        };
        Page p = new Page("X", null, "body", "", List.of());
        ExtractionContext ctx = new ExtractionContext(p.name(), List.of(), Map.of());
        assertEquals("fake", fake.code());
        assertEquals("X", fake.extract(p, ctx).pageName());
    }

    @Test
    void canImplementJudgeWithFake() {
        ProposalJudge fake = new ProposalJudge() {
            public String code() { return "fake-judge"; }
            public Verdict judge(ConsolidatedProposal p, JudgeContext c) {
                return new Verdict.Accept(p.aggregateConfidence(), "ok");
            }
        };
        SupportEvidence e = new SupportEvidence("Pg1", "x", 0.9, "fake");
        ConsolidatedProposal p = ConsolidatedProposal.newNode("sig", "X", "Concept", List.of(e), 0.9);
        JudgeContext jc = new JudgeContext(Map.of(), List.of());
        Verdict v = fake.judge(p, jc);
        assertInstanceOf(Verdict.Accept.class, v);
    }
}
