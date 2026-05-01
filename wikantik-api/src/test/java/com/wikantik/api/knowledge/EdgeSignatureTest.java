package com.wikantik.api.knowledge;

import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class EdgeSignatureTest {

    @Test
    void normalizesEndpointsAndPredicate() {
        EdgeSignature a = EdgeSignature.of("Python", "Programming Language", "is_a");
        EdgeSignature b = EdgeSignature.of("python", "programming language", "IS-A");
        assertEquals(a.asHash(), b.asHash());
    }

    @Test
    void predicateSynonymsCollapse() {
        EdgeSignature a = EdgeSignature.of("Python", "Guido", "created_by");
        EdgeSignature b = EdgeSignature.of("Python", "Guido", "created-by");
        assertEquals(a.asHash(), b.asHash());
    }

    @Test
    void direction_matters() {
        EdgeSignature a = EdgeSignature.of("Python", "Guido", "created_by");
        EdgeSignature b = EdgeSignature.of("Guido", "Python", "created_by");
        assertNotEquals(a.asHash(), b.asHash());
    }

    @Test
    void distinguishesPredicates() {
        EdgeSignature a = EdgeSignature.of("Kafka", "Confluent", "owned_by");
        EdgeSignature b = EdgeSignature.of("Kafka", "Confluent", "competes_with");
        assertNotEquals(a.asHash(), b.asHash());
    }
}
