package com.wikantik.knowledge;

import com.wikantik.api.knowledge.*;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class ContextRetrievalRecordsTest {

    @Test
    void retrievedChunk_rejectsNullHeadingPath() {
        assertThrows( IllegalArgumentException.class,
            () -> new RetrievedChunk( null, "text", 0.5, List.of() ) );
    }

    @Test
    void retrievedChunk_rejectsNullText() {
        assertThrows( IllegalArgumentException.class,
            () -> new RetrievedChunk( List.of( "H1" ), null, 0.5, List.of() ) );
    }

    @Test
    void retrievedChunk_defaultsMatchedTermsWhenNull() {
        final RetrievedChunk chunk = new RetrievedChunk( List.of( "H1" ), "body", 0.5, null );
        assertEquals( List.of(), chunk.matchedTerms() );
    }

    @Test
    void retrievedChunk_copiesListsDefensively() {
        final var mutable = new java.util.ArrayList< String >();
        mutable.add( "a" );
        final RetrievedChunk chunk = new RetrievedChunk( mutable, "body", 0.5, List.of() );
        mutable.add( "b" );
        assertEquals( 1, chunk.headingPath().size() );
    }

    @Test
    void relatedPage_rejectsBlankName() {
        assertThrows( IllegalArgumentException.class,
            () -> new RelatedPage( "", "reason" ) );
    }

    @Test
    void relatedPage_acceptsEmptyReason() {
        final RelatedPage rp = new RelatedPage( "Other", "" );
        assertEquals( "", rp.reason() );
    }

    @Test
    void metadataValue_rejectsNegativeCount() {
        assertThrows( IllegalArgumentException.class,
            () -> new MetadataValue( "search", -1 ) );
    }

    @Test
    void metadataValue_rejectsNullValue() {
        assertThrows( IllegalArgumentException.class,
            () -> new MetadataValue( null, 1 ) );
    }
}
