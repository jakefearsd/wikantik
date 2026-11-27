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
package com.wikantik.knowledge.mcp;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.wikantik.api.citation.CitationStatus;
import com.wikantik.api.pagegraph.StructuralIndexService;
import com.wikantik.citation.CitationRepository;
import com.wikantik.citation.CitationRow;
import io.modelcontextprotocol.spec.McpSchema;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

/** Limit, direction and visibility handling of {@code list_stale_citations}. */
class ListStaleCitationsToolLimitsTest {

    private CitationRepository repo;

    @BeforeEach
    void setUp() {
        repo = mock( CitationRepository.class );
    }

    private static CitationRow row( final String src, final String tgt, final CitationStatus status ) {
        return new CitationRow( 1L, src, tgt, "## Intro", "span", "abc123",
                "claim text", 0, 42, status, Instant.EPOCH, Instant.EPOCH, Instant.EPOCH );
    }

    private static List< CitationRow > staleRows( final int n ) {
        final List< CitationRow > rows = new ArrayList<>();
        for ( int i = 0; i < n; i++ ) rows.add( row( "src" + i, "tgt" + i, CitationStatus.STALE ) );
        return rows;
    }

    private static JsonObject json( final McpSchema.CallToolResult result ) {
        assertFalse( result.isError() );
        return JsonParser.parseString( ( ( McpSchema.TextContent ) result.content().get( 0 ) ).text() ).getAsJsonObject();
    }

    @ParameterizedTest
    @CsvSource( { "1,5,1", "4,5,4", "5,5,5", "6,5,5", "0,5,0" } )
    void pageScopedResultIsCappedAtTheLimit( final int available, final int limit, final int expected ) {
        when( repo.findBySource( "p1" ) ).thenReturn( staleRows( available ) );
        final JsonObject out = json( new ListStaleCitationsTool( repo )
                .execute( Map.of( "page", "p1", "direction", "outbound", "limit", limit ) ) );
        assertEquals( expected, out.getAsJsonArray( "citations" ).size() );
        assertEquals( expected, out.get( "count" ).getAsInt() );
    }

    @Test
    void theLimitKeepsTheFirstRowsInRepositoryOrder() {
        when( repo.findBySource( "p1" ) ).thenReturn( staleRows( 4 ) );
        final JsonArray c = json( new ListStaleCitationsTool( repo )
                .execute( Map.of( "page", "p1", "direction", "outbound", "limit", 2 ) ) ).getAsJsonArray( "citations" );
        assertEquals( "tgt0", c.get( 0 ).getAsJsonObject().get( "targetCanonicalId" ).getAsString() );
        assertEquals( "tgt1", c.get( 1 ).getAsJsonObject().get( "targetCanonicalId" ).getAsString() );
    }

    @ParameterizedTest
    @CsvSource( { "2,5,4", "3,5,5", "5,5,5", "7,5,5", "0,1,1" } )
    void corpusWideResultIsCappedAtTheLimitAcrossBothStatuses( final int stale, final int limit, final int expected ) {
        when( repo.findByStatus( CitationStatus.STALE ) ).thenReturn( staleRows( stale ) );
        when( repo.findByStatus( CitationStatus.TARGET_MISSING ) ).thenReturn( List.of(
                row( "m1", "x1", CitationStatus.TARGET_MISSING ), row( "m2", "x2", CitationStatus.TARGET_MISSING ) ) );
        final JsonObject out = json( new ListStaleCitationsTool( repo ).execute( Map.of( "limit", limit ) ) );
        assertEquals( expected, out.getAsJsonArray( "citations" ).size() );
    }

    @Test
    void corpusWideDefaultLimitIsFifty() {
        when( repo.findByStatus( CitationStatus.STALE ) ).thenReturn( staleRows( 60 ) );
        when( repo.findByStatus( CitationStatus.TARGET_MISSING ) ).thenReturn( List.of() );
        assertEquals( 50, json( new ListStaleCitationsTool( repo ).execute( Map.of() ) ).getAsJsonArray( "citations" ).size() );
    }

    @Test
    void outboundDirectionNeverQueriesInboundCitations() {
        when( repo.findBySource( "p1" ) ).thenReturn( List.of( row( "p1", "t", CitationStatus.STALE ) ) );
        json( new ListStaleCitationsTool( repo ).execute( Map.of( "page", "p1", "direction", "outbound" ) ) );
        verify( repo, never() ).findByTarget( anyString() );
    }

    @Test
    void inboundDirectionNeverQueriesOutboundCitations() {
        when( repo.findByTarget( "p1" ) ).thenReturn( List.of( row( "s", "p1", CitationStatus.STALE ) ) );
        json( new ListStaleCitationsTool( repo ).execute( Map.of( "page", "p1", "direction", "inbound" ) ) );
        verify( repo, never() ).findBySource( anyString() );
    }

    @Test
    void omittingDirectionMeansBoth() {
        when( repo.findBySource( "p1" ) ).thenReturn( List.of( row( "p1", "t", CitationStatus.STALE ) ) );
        when( repo.findByTarget( "p1" ) ).thenReturn( List.of( row( "s", "p1", CitationStatus.STALE ) ) );
        assertEquals( 2, json( new ListStaleCitationsTool( repo ).execute( Map.of( "page", "p1" ) ) )
                .getAsJsonArray( "citations" ).size() );
    }

    @Test
    void anUnrecognisedDirectionListsNothingAndQueriesNothing() {
        final JsonObject out = json( new ListStaleCitationsTool( repo )
                .execute( Map.of( "page", "p1", "direction", "sideways" ) ) );
        assertEquals( 0, out.getAsJsonArray( "citations" ).size() );
        verify( repo, never() ).findBySource( anyString() );
        verify( repo, never() ).findByTarget( anyString() );
    }

    @Test
    void aNonStringPageArgumentFallsBackToTheCorpusWideListing() {
        when( repo.findByStatus( CitationStatus.STALE ) ).thenReturn( staleRows( 1 ) );
        when( repo.findByStatus( CitationStatus.TARGET_MISSING ) ).thenReturn( List.of() );
        assertEquals( 1, json( new ListStaleCitationsTool( repo ).execute( Map.of( "page", 42 ) ) )
                .getAsJsonArray( "citations" ).size() );
        verify( repo, never() ).findBySource( anyString() );
    }

    @Test
    void theCapAppliesToWhatTheCallerMayViewNotToTheCorpus() {
        final StructuralIndexService index = mock( StructuralIndexService.class );
        when( index.resolveSlugFromCanonicalId( anyString() ) ).thenAnswer( i -> Optional.of( i.getArgument( 0 ) ) );
        when( repo.findByStatus( CitationStatus.STALE ) ).thenReturn( List.of(
                row( "hidden1", "t", CitationStatus.STALE ), row( "hidden2", "t", CitationStatus.STALE ),
                row( "open1", "t", CitationStatus.STALE ), row( "open2", "t", CitationStatus.STALE ) ) );
        when( repo.findByStatus( CitationStatus.TARGET_MISSING ) ).thenReturn( List.of() );
        final PageViewGate gate = slug -> !slug.startsWith( "hidden" );

        final JsonArray c = json( new ListStaleCitationsTool( repo, index, gate ).execute( Map.of( "limit", 2 ) ) )
                .getAsJsonArray( "citations" );

        assertEquals( 2, c.size() );
        assertEquals( "open1", c.get( 0 ).getAsJsonObject().get( "sourceCanonicalId" ).getAsString() );
        assertEquals( "open2", c.get( 1 ).getAsJsonObject().get( "sourceCanonicalId" ).getAsString() );
    }

    @Test
    void aTargetTheCallerCannotViewHidesTheCitationButAMissingTargetDoesNot() {
        final StructuralIndexService index = mock( StructuralIndexService.class );
        when( index.resolveSlugFromCanonicalId( "src" ) ).thenReturn( Optional.of( "Src" ) );
        when( index.resolveSlugFromCanonicalId( "secretTarget" ) ).thenReturn( Optional.of( "Secret" ) );
        when( index.resolveSlugFromCanonicalId( "goneTarget" ) ).thenReturn( Optional.empty() );
        when( repo.findByStatus( CitationStatus.STALE ) ).thenReturn( List.of(
                row( "src", "secretTarget", CitationStatus.STALE ), row( "src", "goneTarget", CitationStatus.STALE ) ) );
        when( repo.findByStatus( CitationStatus.TARGET_MISSING ) ).thenReturn( List.of() );

        final JsonArray c = json( new ListStaleCitationsTool( repo, index, slug -> !"Secret".equals( slug ) )
                .execute( Map.of() ) ).getAsJsonArray( "citations" );

        assertEquals( 1, c.size() );
        assertEquals( "goneTarget", c.get( 0 ).getAsJsonObject().get( "targetCanonicalId" ).getAsString() );
    }

    @Test
    void aSourceThatCannotBeResolvedIsNotListed() {
        final StructuralIndexService index = mock( StructuralIndexService.class );
        when( index.resolveSlugFromCanonicalId( anyString() ) ).thenReturn( Optional.empty() );
        when( repo.findByStatus( CitationStatus.STALE ) ).thenReturn( staleRows( 2 ) );
        when( repo.findByStatus( CitationStatus.TARGET_MISSING ) ).thenReturn( List.of() );
        assertEquals( 0, json( new ListStaleCitationsTool( repo, index, slug -> true ) .execute( Map.of() ) )
                .getAsJsonArray( "citations" ).size() );
    }

    @Test
    void withoutAnIndexTheGateCannotJudgeSoEveryRowIsListed() {
        when( repo.findByStatus( CitationStatus.STALE ) ).thenReturn( staleRows( 2 ) );
        when( repo.findByStatus( CitationStatus.TARGET_MISSING ) ).thenReturn( List.of() );
        assertEquals( 2, json( new ListStaleCitationsTool( repo, null, slug -> false ).execute( Map.of() ) )
                .getAsJsonArray( "citations" ).size() );
    }

    @Test
    void aNullViewGateListsEveryRow() {
        final StructuralIndexService index = mock( StructuralIndexService.class );
        when( repo.findByStatus( CitationStatus.STALE ) ).thenReturn( staleRows( 2 ) );
        when( repo.findByStatus( CitationStatus.TARGET_MISSING ) ).thenReturn( List.of() );
        assertEquals( 2, json( new ListStaleCitationsTool( repo, index, null ).execute( Map.of() ) )
                .getAsJsonArray( "citations" ).size() );
        verifyNoInteractions( index );
    }
}
