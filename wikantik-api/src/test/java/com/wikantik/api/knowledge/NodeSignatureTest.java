package com.wikantik.api.knowledge;

import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class NodeSignatureTest {

    @Test
    void normalizesNameAndType() {
        NodeSignature a = NodeSignature.of(" GitHub ", "Organization");
        NodeSignature b = NodeSignature.of("github", "organization");
        NodeSignature c = NodeSignature.of("GitHub.", "ORGANIZATION");
        assertEquals(a.asHash(), b.asHash());
        assertEquals(a.asHash(), c.asHash());
    }

    @Test
    void distinguishesDifferentNames() {
        NodeSignature a = NodeSignature.of("Spark", "Technology");
        NodeSignature b = NodeSignature.of("Apache Spark", "Technology");
        assertNotEquals(a.asHash(), b.asHash());
    }

    @Test
    void distinguishesDifferentTypes() {
        NodeSignature a = NodeSignature.of("Java", "Technology");
        NodeSignature b = NodeSignature.of("Java", "Place");
        assertNotEquals(a.asHash(), b.asHash());
    }

    @Test
    void hashIsStableAcrossJvms() {
        assertEquals(NodeSignature.of("Kafka", "Technology").asHash(),
                     NodeSignature.of("Kafka", "Technology").asHash());
        assertEquals(64, NodeSignature.of("Kafka", "Technology").asHash().length());
    }

    @Test
    void nfcUnicodeEquivalence() {
        String composed = "Café";
        String decomposed = "Café";
        assertEquals(NodeSignature.of(composed, "Place").asHash(),
                     NodeSignature.of(decomposed, "Place").asHash());
    }

    @Test
    void rejectsBlankName() {
        assertThrows(IllegalArgumentException.class,
            () -> NodeSignature.of("", "Concept"));
        assertThrows(IllegalArgumentException.class,
            () -> NodeSignature.of("   ", "Concept"));
    }

    @Test
    void noisyLlmExtractionMatchesCleanForm() {
        // Regression: LLM emits noisy mention strings like ", GitHub, " or
        // "His company, GitHub,". After normalization these should match the
        // clean "GitHub" form so the consolidator sees one proposal, not many.
        assertEquals(NodeSignature.of("GitHub", "Organization").asHash(),
                     NodeSignature.of(" ., GitHub .,  ", "Organization").asHash());
        assertEquals(NodeSignature.of("GitHub", "Organization").asHash(),
                     NodeSignature.of(",GitHub,", "Organization").asHash());
    }

    @Test
    void preservesIdentifierPunctuation() {
        // Regression: tech identifiers like C++, C#, .NET, F# carry semantic
        // punctuation. They must NOT collide with each other or with bare 'C'.
        assertNotEquals(NodeSignature.of("C", "Technology").asHash(),
                        NodeSignature.of("C++", "Technology").asHash());
        assertNotEquals(NodeSignature.of("C", "Technology").asHash(),
                        NodeSignature.of("C#", "Technology").asHash());
        assertNotEquals(NodeSignature.of("C++", "Technology").asHash(),
                        NodeSignature.of("C#", "Technology").asHash());
        assertNotEquals(NodeSignature.of("net", "Technology").asHash(),
                        NodeSignature.of(".NET", "Technology").asHash());
    }
}
