package com.wikantik.knowledge.extraction;

import com.wikantik.api.knowledge.ExtractionContext;
import com.wikantik.api.knowledge.KgNode;
import com.wikantik.api.knowledge.Page;
import com.wikantik.api.knowledge.Provenance;
import org.junit.jupiter.api.Test;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import static org.junit.jupiter.api.Assertions.*;

class PageExtractionPromptBuilderTest {

    @Test
    void systemPromptIsByteStable() {
        assertEquals(PageExtractionPromptBuilder.SYSTEM_PROMPT,
                     PageExtractionPromptBuilder.SYSTEM_PROMPT);
        assertTrue(PageExtractionPromptBuilder.SYSTEM_PROMPT.contains("evidence_span"));
        assertTrue(PageExtractionPromptBuilder.SYSTEM_PROMPT.contains("max 12"));
        assertTrue(PageExtractionPromptBuilder.SYSTEM_PROMPT.contains("max 8"));
        for (String banned : List.of("Concept", "Agent", "Process", "System", "User", "Software", "Data")) {
            assertTrue(PageExtractionPromptBuilder.SYSTEM_PROMPT.contains("\"" + banned + "\""),
                       "system prompt missing banned name: " + banned);
        }
        assertTrue(PageExtractionPromptBuilder.SYSTEM_PROMPT.contains("Person|Organization|Place|Event|Product|Technology|Concept"));
        assertFalse(PageExtractionPromptBuilder.SYSTEM_PROMPT.contains("|Project|"));
    }

    @Test
    void userPromptIncludesPageDictionaryAndBody() {
        Page page = new Page("Kafka", null, "Kafka is a streaming platform.", "summary",
                             List.of("Overview"));
        KgNode existing = new KgNode(UUID.randomUUID(), "PostgreSQL", "Technology",
                                     "PostgreSQL", Provenance.HUMAN_AUTHORED,
                                     Map.of(), Instant.now(), Instant.now(), "human", null);
        ExtractionContext ctx = new ExtractionContext("Kafka", List.of(existing), Map.of());
        String prompt = PageExtractionPromptBuilder.buildUserPrompt(page, ctx);
        assertTrue(prompt.contains("Page: Kafka"));
        assertTrue(prompt.contains("Kafka is a streaming platform."));
        assertTrue(prompt.contains("PostgreSQL"));
    }

    @Test
    void emptyDictionaryOmitsKnownEntitiesSection() {
        Page page = new Page("X", null, "body", "", List.of());
        ExtractionContext ctx = new ExtractionContext("X", List.of(), Map.of());
        String prompt = PageExtractionPromptBuilder.buildUserPrompt(page, ctx);
        assertFalse(prompt.contains("Known Entities"));
    }
}
