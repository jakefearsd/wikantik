package com.wikantik.knowledge;

import com.wikantik.api.knowledge.KgNode;
import com.wikantik.api.knowledge.Provenance;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.Map;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;

class GraphRoleClassifierTest {

    private static KgNode node( final String name, final String sourcePage ) {
        return new KgNode( UUID.randomUUID(), name, "page", sourcePage,
                Provenance.HUMAN_AUTHORED, Map.of(), Instant.now(), Instant.now() );
    }

    @Test
    void restricted_takesPrecedenceOverAll() {
        assertEquals( "restricted",
                GraphRoleClassifier.classify( node( "A", "A" ), 5, 5, 10, true ) );
    }

    @Test
    void stub_whenSourcePageNull() {
        assertEquals( "stub",
                GraphRoleClassifier.classify( node( "X", null ), 2, 0, 10, false ) );
    }

    @Test
    void orphan_whenZeroDegree() {
        assertEquals( "orphan",
                GraphRoleClassifier.classify( node( "O", "O" ), 0, 0, 10, false ) );
    }

    @Test
    void hub_atThreshold() {
        assertEquals( "hub",
                GraphRoleClassifier.classify( node( "H", "H" ), 5, 5, 10, false ) );
    }

    @Test
    void hub_aboveThreshold() {
        assertEquals( "hub",
                GraphRoleClassifier.classify( node( "H", "H" ), 8, 8, 10, false ) );
    }

    @Test
    void normal_belowThreshold() {
        assertEquals( "normal",
                GraphRoleClassifier.classify( node( "N", "N" ), 3, 2, 10, false ) );
    }

    @Test
    void stub_precedesOrphan() {
        assertEquals( "stub",
                GraphRoleClassifier.classify( node( "S", null ), 0, 0, 10, false ) );
    }

    @Test
    void restricted_precedesStub() {
        assertEquals( "restricted",
                GraphRoleClassifier.classify( node( "R", null ), 0, 0, 10, true ) );
    }
}
