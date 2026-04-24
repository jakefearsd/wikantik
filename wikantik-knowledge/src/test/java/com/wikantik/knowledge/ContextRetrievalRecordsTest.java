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

    @Test
    void pageListFilter_allFieldsOptional() {
        final PageListFilter f = new PageListFilter( null, null, null, null, null, null, 50, 0 );
        assertNull( f.cluster() );
        assertEquals( List.of(), f.tags() );
        assertNull( f.type() );
        assertEquals( 50, f.limit() );
    }

    @Test
    void pageListFilter_rejectsNegativeLimit() {
        assertThrows( IllegalArgumentException.class,
            () -> new PageListFilter( null, null, null, null, null, null, -1, 0 ) );
    }

    @Test
    void pageListFilter_rejectsLimitOverMax() {
        assertThrows( IllegalArgumentException.class,
            () -> new PageListFilter( null, null, null, null, null, null, 201, 0 ) );
    }

    @Test
    void pageListFilter_rejectsNegativeOffset() {
        assertThrows( IllegalArgumentException.class,
            () -> new PageListFilter( null, null, null, null, null, null, 50, -1 ) );
    }

    @Test
    void contextQuery_rejectsBlankQuery() {
        assertThrows( IllegalArgumentException.class,
            () -> new ContextQuery( "", 5, 3, null ) );
    }

    @Test
    void contextQuery_rejectsNullQuery() {
        assertThrows( IllegalArgumentException.class,
            () -> new ContextQuery( null, 5, 3, null ) );
    }

    @Test
    void contextQuery_clampsMaxPages() {
        assertThrows( IllegalArgumentException.class,
            () -> new ContextQuery( "q", 21, 3, null ) );
    }

    @Test
    void contextQuery_rejectsZeroChunksPerPage() {
        assertThrows( IllegalArgumentException.class,
            () -> new ContextQuery( "q", 5, 0, null ) );
    }

    @Test
    void contextQuery_filterDefaultsToEmpty() {
        final ContextQuery q = new ContextQuery( "q", 5, 3, null );
        assertNotNull( q.filter() );
        assertNull( q.filter().cluster() );
    }
}
