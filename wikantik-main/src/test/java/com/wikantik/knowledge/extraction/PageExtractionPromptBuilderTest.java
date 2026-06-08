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
        // The type enum must list all 9 canonical entity classes (incl. Project + Version),
        // generated from EntityTypeVocabulary so the prompt cannot drift from the parser allowlist.
        final String expectedTypeEnum = com.wikantik.api.knowledge.EntityTypeVocabulary.ENTITY_CLASSES.stream()
                .map(t -> Character.toUpperCase(t.charAt(0)) + t.substring(1))
                .collect(java.util.stream.Collectors.joining("|"));
        assertTrue(PageExtractionPromptBuilder.SYSTEM_PROMPT.contains(expectedTypeEnum),
                   "type enum must list all 9 entity classes in vocab order: " + expectedTypeEnum);
        assertTrue(PageExtractionPromptBuilder.SYSTEM_PROMPT.contains("|Project|"),
                   "Project must now be an allowed entity type");
        assertTrue(PageExtractionPromptBuilder.SYSTEM_PROMPT.contains("|Version"),
                   "Version must now be an allowed entity type");
    }

    @Test
    void systemPromptDeclaresClosedRelationVocabulary() {
        // Predicate is now an enum, not a free-form str — see V027 CHECK
        // constraint. The DB will reject anything not in this list, so the
        // prompt must enumerate the same 20 values.
        assertTrue(PageExtractionPromptBuilder.SYSTEM_PROMPT.contains("\"predicate\": ENUM"),
                   "predicate field must be declared as ENUM, not free-form str");
        assertFalse(PageExtractionPromptBuilder.SYSTEM_PROMPT.contains("\"predicate\": str"),
                    "predicate field must no longer be declared as free-form str");
        for (String t : List.of("related_to", "part_of", "contains", "is_a", "instance_of",
                                "requires", "enables", "uses", "produces", "replaces",
                                "precedes", "extends", "implements", "alternative_to", "contrasts_with",
                                "compatible_with", "mitigates", "defines", "applies_to", "located_in")) {
            assertTrue(PageExtractionPromptBuilder.SYSTEM_PROMPT.contains(t),
                       "system prompt missing closed-vocab predicate: " + t);
        }
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
