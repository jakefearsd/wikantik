package com.wikantik.api.knowledge;

import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class ProvenanceTest {

    @Test
    void humanCuratedRoundtripsThroughFromValue() {
        assertEquals( Provenance.HUMAN_CURATED, Provenance.fromValue( "human-curated" ) );
        assertEquals( "human-curated", Provenance.HUMAN_CURATED.value() );
    }

    @Test
    void existingValuesStillRoundtrip() {
        assertEquals( Provenance.HUMAN_AUTHORED, Provenance.fromValue( "human-authored" ) );
        assertEquals( Provenance.AI_INFERRED, Provenance.fromValue( "ai-inferred" ) );
        assertEquals( Provenance.AI_REVIEWED, Provenance.fromValue( "ai-reviewed" ) );
    }

    @Test
    void unknownValueThrows() {
        assertThrows( IllegalArgumentException.class, () -> Provenance.fromValue( "bogus" ) );
    }
}
