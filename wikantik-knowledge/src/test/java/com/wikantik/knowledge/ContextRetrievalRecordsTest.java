/*
    Licensed to the Apache Software Foundation (ASF) under one
    or more contributor license agreements.  See the NOTICE file
    distributed with this work for additional information
    regarding copyright ownership.  The ASF licenses this file
    to you under the Apache License, Version 2.0 (the
    "License"); you may not use this file except in compliance
    with the License.  You may obtain a copy of the License at

       http://www.apache.org/licenses/LICENSE-2.0

    Unless required by applicable law or agreed to in writing,
    software distributed under the License is distributed on an
    "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY
    KIND, either express or implied.  See the License for the
    specific language governing permissions and limitations
    under the License.
 */
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

    @Test
    void retrievedPage_rejectsBlankName() {
        assertThrows( IllegalArgumentException.class,
            () -> new RetrievedPage( "", "url", 0.0, "", null, List.of(),
                List.of(), List.of(), null, null ) );
    }

    @Test
    void retrievedPage_defaultsCollections() {
        final RetrievedPage p = new RetrievedPage(
            "P", "url", 0.5, "summary", null, null, null, null, null, null );
        assertEquals( List.of(), p.tags() );
        assertEquals( List.of(), p.contributingChunks() );
        assertEquals( List.of(), p.relatedPages() );
        assertNull( p.author() );
        assertNull( p.lastModified() );
    }

    @Test
    void retrievalResult_rejectsNullPages() {
        assertThrows( IllegalArgumentException.class,
            () -> new RetrievalResult( "q", null, 0 ) );
    }

    @Test
    void retrievalResult_rejectsNegativeTotal() {
        assertThrows( IllegalArgumentException.class,
            () -> new RetrievalResult( "q", List.of(), -1 ) );
    }

    @Test
    void pageList_defaultsPagesToEmpty() {
        final PageList pl = new PageList( null, 0, 50, 0 );
        assertEquals( List.of(), pl.pages() );
    }
}
