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
package com.wikantik.api.knowledge;

import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.Date;
import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;

class RetrievalRecordsValidationTest {

    @Test
    void extractedRelationRejectsBadInputs() {
        assertThrows( IllegalArgumentException.class, () -> new ExtractedRelation( " ", "b", "p", "e", 0.5 ) );
        assertThrows( IllegalArgumentException.class, () -> new ExtractedRelation( "a", null, "p", "e", 0.5 ) );
        assertThrows( IllegalArgumentException.class, () -> new ExtractedRelation( "a", "b", "", "e", 0.5 ) );
        assertThrows( IllegalArgumentException.class, () -> new ExtractedRelation( "a", "b", "p", null, 0.5 ) );
        assertThrows( IllegalArgumentException.class, () -> new ExtractedRelation( "a", "b", "p", "e", 1.01 ) );
        assertThrows( IllegalArgumentException.class, () -> new ExtractedRelation( "a", "b", "p", "e", -0.1 ) );
        assertEquals( 1.0, new ExtractedRelation( "a", "b", "p", "", 1.0 ).confidence() );
    }

    @Test
    void judgeVerdictValidatesAndDetectsTransientUnavailability() {
        assertThrows( IllegalArgumentException.class, () -> new JudgeVerdict( "maybe", 0.5, "r", "m" ) );
        assertThrows( IllegalArgumentException.class, () -> new JudgeVerdict( null, 0.5, "r", "m" ) );
        assertThrows( IllegalArgumentException.class, () -> new JudgeVerdict( "approved", 2.0, "r", "m" ) );
        assertThrows( IllegalArgumentException.class, () -> new JudgeVerdict( "approved", 0.5, "r", " " ) );
        assertTrue( new JudgeVerdict( JudgeVerdict.ABSTAIN, 0, "judge_unavailable: timeout", "m" )
                .isTransientUnavailable() );
        assertFalse( new JudgeVerdict( JudgeVerdict.ABSTAIN, 0, "unsure", "m" ).isTransientUnavailable() );
        assertFalse( new JudgeVerdict( JudgeVerdict.ABSTAIN, 0, null, "m" ).isTransientUnavailable() );
        assertFalse( new JudgeVerdict( JudgeVerdict.REJECTED, 0, "judge_unavailable: x", "m" )
                .isTransientUnavailable() );
    }

    @Test
    void nodeMentionRequiresFieldsAndDefaultsHeadingPath() {
        final UUID id = UUID.randomUUID();
        assertThrows( IllegalArgumentException.class, () -> new NodeMention( null, "P", 0, null, "t", 1, "x" ) );
        assertThrows( IllegalArgumentException.class, () -> new NodeMention( id, null, 0, null, "t", 1, "x" ) );
        assertThrows( IllegalArgumentException.class, () -> new NodeMention( id, "P", 0, null, null, 1, "x" ) );
        assertEquals( List.of(), new NodeMention( id, "P", 0, null, "t", 1, "x" ).headingPath() );
    }

    @Test
    void contextQueryEnforcesCapsAndDefaultsFilter() {
        assertThrows( IllegalArgumentException.class, () -> new ContextQuery( " ", 5, 2, null ) );
        assertThrows( IllegalArgumentException.class, () -> new ContextQuery( "q", 0, 2, null ) );
        assertThrows( IllegalArgumentException.class,
                () -> new ContextQuery( "q", ContextQuery.MAX_PAGES_CAP + 1, 2, null ) );
        assertThrows( IllegalArgumentException.class, () -> new ContextQuery( "q", 5, 0, null ) );
        assertThrows( IllegalArgumentException.class,
                () -> new ContextQuery( "q", 5, ContextQuery.MAX_CHUNKS_PER_PAGE_CAP + 1, null ) );
        final ContextQuery q = new ContextQuery( "q", ContextQuery.MAX_PAGES_CAP, 5, null );
        assertEquals( PageListFilter.unfiltered(), q.filter() );
    }

    @Test
    void pageListFilterValidatesLimitAndOffset() {
        assertThrows( IllegalArgumentException.class,
                () -> new PageListFilter( null, null, null, null, null, null, -1, 0 ) );
        assertThrows( IllegalArgumentException.class,
                () -> new PageListFilter( null, null, null, null, null, null, PageListFilter.MAX_LIMIT + 1, 0 ) );
        assertThrows( IllegalArgumentException.class,
                () -> new PageListFilter( null, null, null, null, null, null, 10, -1 ) );
        final PageListFilter f = PageListFilter.unfiltered();
        assertEquals( 50, f.limit() );
        assertEquals( List.of(), f.tags() );
    }

    @Test
    void retrievedChunkAndRelatedPageNormalise() {
        assertThrows( IllegalArgumentException.class, () -> new RetrievedChunk( null, "t", 1, null ) );
        assertThrows( IllegalArgumentException.class, () -> new RetrievedChunk( List.of(), null, 1, null ) );
        assertEquals( List.of(), new RetrievedChunk( List.of( "H" ), "t", 1, null ).matchedTerms() );
        assertThrows( IllegalArgumentException.class, () -> new RelatedPage( " ", "r" ) );
        assertEquals( "", new RelatedPage( "P", null ).reason() );
    }

    @Test
    void retrievedPageDefaultsAndDefensivelyCopies() {
        assertThrows( IllegalArgumentException.class, () -> RetrievedPage.builder( " ", 1.0 ).build() );
        final Date d = new Date( 1000L );
        final List< String > tags = new ArrayList<>( List.of( "a" ) );
        final RetrievedPage p = RetrievedPage.builder( "Page", 0.5 ).url( "/u" ).summary( null ).cluster( "c" )
                .tags( tags ).author( "me" ).lastModified( d ).derived( true )
                .relatedPages( List.of( new RelatedPage( "R", "why" ) ) )
                .contributingChunks( List.of( new RetrievedChunk( List.of(), "t", 1, null ) ) ).build();
        tags.add( "b" );
        d.setTime( 5L );
        assertEquals( List.of( "a" ), p.tags() );
        assertEquals( 1000L, p.lastModified().getTime() );
        p.lastModified().setTime( 9L );
        assertEquals( 1000L, p.lastModified().getTime() );
        assertEquals( "", p.summary() );
        assertTrue( p.derived() );
        assertEquals( 1, p.relatedPages().size() );
        assertEquals( 1, p.contributingChunks().size() );
        assertNull( RetrievedPage.builder( "X", 0 ).build().lastModified() );
        assertEquals( List.of(), RetrievedPage.builder( "X", 0 ).build().contributingChunks() );
    }

    @Test
    void retrievalResultAndPageListValidate() {
        assertThrows( IllegalArgumentException.class, () -> new RetrievalResult( null, List.of(), 0 ) );
        assertThrows( IllegalArgumentException.class, () -> new RetrievalResult( "q", null, 0 ) );
        assertThrows( IllegalArgumentException.class, () -> new RetrievalResult( "q", List.of(), -1 ) );
        assertEquals( 0, new RetrievalResult( "q", List.of(), 0 ).pages().size() );
        assertThrows( IllegalArgumentException.class, () -> new PageList( List.of(), -1, 10, 0 ) );
        assertEquals( List.of(), new PageList( null, 0, 10, 0 ).pages() );
    }

    @Test
    void metadataValueValidates() {
        assertThrows( IllegalArgumentException.class, () -> new MetadataValue( null, 1 ) );
        assertThrows( IllegalArgumentException.class, () -> new MetadataValue( "v", -1 ) );
        assertEquals( 0, new MetadataValue( "v", 0 ).count() );
    }
}
