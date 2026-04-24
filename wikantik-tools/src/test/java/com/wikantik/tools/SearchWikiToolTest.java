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
package com.wikantik.tools;

import com.wikantik.api.core.Engine;
import com.wikantik.api.knowledge.ContextQuery;
import com.wikantik.api.knowledge.ContextRetrievalService;
import com.wikantik.api.knowledge.RelatedPage;
import com.wikantik.api.knowledge.RetrievalResult;
import com.wikantik.api.knowledge.RetrievedChunk;
import com.wikantik.api.knowledge.RetrievedPage;

import jakarta.servlet.http.HttpServletRequest;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Properties;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

@ExtendWith( MockitoExtension.class )
class SearchWikiToolTest {

    @Mock Engine engine;
    @Mock ContextRetrievalService ctxService;
    @Mock HttpServletRequest request;

    @Test
    void returnsErrorPayloadWhenServiceNotConfigured() {
        when( engine.getManager( ContextRetrievalService.class ) ).thenReturn( null );

        final SearchWikiTool tool = new SearchWikiTool( engine, new ToolsConfig( new Properties() ) );
        final Map< String, Object > result = tool.execute( "hello", 10, request );

        assertEquals( "hello", result.get( "query" ) );
        assertEquals( 0, result.get( "total" ) );
        assertTrue( result.get( "error" ).toString().contains( "ContextRetrievalService not configured" ) );
    }

    @Test
    void returnsErrorPayloadWhenSearchThrows() {
        when( engine.getManager( ContextRetrievalService.class ) ).thenReturn( ctxService );
        when( ctxService.retrieve( any( ContextQuery.class ) ) ).thenThrow( new RuntimeException( "boom" ) );

        final SearchWikiTool tool = new SearchWikiTool( engine, new ToolsConfig( new Properties() ) );
        final Map< String, Object > result = tool.execute( "hello", 10, request );

        assertEquals( "hello", result.get( "query" ) );
        assertEquals( 0, result.get( "total" ) );
        assertTrue( result.get( "error" ).toString().contains( "boom" ) );
    }

    @Test
    void shapesResultsWithCitationAndSnippet() {
        when( engine.getManager( ContextRetrievalService.class ) ).thenReturn( ctxService );
        when( ctxService.retrieve( any( ContextQuery.class ) ) ).thenReturn(
            new RetrievalResult( "hello", List.of( new RetrievedPage(
                "OnePage",
                "",
                42.0,
                "A great page",
                null,
                List.of(),
                List.of( new RetrievedChunk( List.of( "OnePage" ), "matching excerpt", 0.9, List.of() ) ),
                List.of(),
                null,
                null
            ) ), 1 ) );

        final Properties props = new Properties();
        props.setProperty( "wikantik.public.baseURL", "https://wiki.example.com" );
        final SearchWikiTool tool = new SearchWikiTool( engine, new ToolsConfig( props ) );

        final Map< String, Object > out = tool.execute( "hello", 5, request );

        assertEquals( "hello", out.get( "query" ) );
        assertEquals( 1, out.get( "total" ) );
        final List< ? > results = ( List< ? > ) out.get( "results" );
        assertEquals( 1, results.size() );
        final Map< ?, ? > first = ( Map< ?, ? > ) results.get( 0 );
        assertEquals( "OnePage", first.get( "name" ) );
        assertEquals( "https://wiki.example.com/wiki/OnePage", first.get( "url" ) );
        assertEquals( 42.0, first.get( "score" ) );
        assertEquals( "A great page", first.get( "summary" ) );
        assertEquals( "matching excerpt", first.get( "snippet" ) );
    }

    @Test
    void snippetIsTruncatedAt320Chars() {
        when( engine.getManager( ContextRetrievalService.class ) ).thenReturn( ctxService );
        final String longText = "x".repeat( 400 );
        when( ctxService.retrieve( any( ContextQuery.class ) ) ).thenReturn(
            new RetrievalResult( "q", List.of( new RetrievedPage(
                "LongPage",
                "",
                1.0,
                "",
                null,
                List.of(),
                List.of( new RetrievedChunk( List.of(), longText, 0.5, List.of() ) ),
                List.of(),
                null,
                null
            ) ), 1 ) );

        final SearchWikiTool tool = new SearchWikiTool( engine, new ToolsConfig( new Properties() ) );
        final Map< String, Object > out = tool.execute( "q", 5, request );

        final List< ? > results = ( List< ? > ) out.get( "results" );
        final Map< ?, ? > first = ( Map< ?, ? > ) results.get( 0 );
        final String snippet = ( String ) first.get( "snippet" );
        assertTrue( snippet.endsWith( "…" ), "should end with ellipsis" );
        assertEquals( 321, snippet.length(), "320 chars + ellipsis" );
    }

    @Test
    void clampsMaxResults() {
        when( engine.getManager( ContextRetrievalService.class ) ).thenReturn( ctxService );

        // Build 20 pages (service hard cap) — tool clamps to 25 but service returns max 20
        final List< RetrievedPage > manyPages = new ArrayList<>();
        for ( int i = 0; i < 20; i++ ) {
            manyPages.add( new RetrievedPage(
                "P" + i, "", 1.0, "", null, List.of(), List.of(), List.of(), null, null ) );
        }
        when( ctxService.retrieve( any( ContextQuery.class ) ) ).thenReturn(
            new RetrievalResult( "q", manyPages, 20 ) );

        final SearchWikiTool tool = new SearchWikiTool( engine, new ToolsConfig( new Properties() ) );
        final Map< String, Object > out = tool.execute( "q", 100, request );

        // clamped to 25, but service returned 20 — total should be 20
        assertEquals( 20, out.get( "total" ) );
    }

    @Test
    void execute_includesContributingChunksArray() {
        when( engine.getManager( ContextRetrievalService.class ) ).thenReturn( ctxService );
        when( ctxService.retrieve( any( ContextQuery.class ) ) )
            .thenReturn( new RetrievalResult(
                "q",
                List.of( new RetrievedPage(
                    "Alpha", "", 5.0, "alpha summary", null, List.of(),
                    List.of(
                        new RetrievedChunk( List.of( "Alpha", "Intro" ), "first chunk body", 0.9, List.of() ),
                        new RetrievedChunk( List.of( "Alpha", "Details" ), "second chunk body", 0.8, List.of() ) ),
                    List.of(), null, null ) ),
                1 ) );

        final SearchWikiTool tool = new SearchWikiTool( engine, new ToolsConfig( new Properties() ) );
        final Map< String, Object > result = tool.execute( "q", 10, request );
        @SuppressWarnings( "unchecked" )
        final List< Map< String, Object > > results =
            (List< Map< String, Object > >) result.get( "results" );
        assertEquals( 1, results.size() );
        @SuppressWarnings( "unchecked" )
        final List< Map< String, Object > > chunks =
            (List< Map< String, Object > >) results.get( 0 ).get( "contributingChunks" );
        assertNotNull( chunks );
        assertEquals( 2, chunks.size() );
        assertEquals( "first chunk body", chunks.get( 0 ).get( "text" ) );
        assertEquals( List.of( "Alpha", "Intro" ), chunks.get( 0 ).get( "headingPath" ) );
        assertEquals( 0.9, ( (Number) chunks.get( 0 ).get( "chunkScore" ) ).doubleValue(), 1e-9 );
    }

    @Test
    void execute_includesRelatedPagesArray() {
        when( engine.getManager( ContextRetrievalService.class ) ).thenReturn( ctxService );
        when( ctxService.retrieve( any( ContextQuery.class ) ) )
            .thenReturn( new RetrievalResult(
                "q",
                List.of( new RetrievedPage(
                    "Alpha", "", 5.0, "alpha summary", null, List.of(),
                    List.of(),
                    List.of(
                        new RelatedPage( "Beta", "shared entities: x, y" ),
                        new RelatedPage( "Gamma", "shared entities: y" ) ),
                    null, null ) ),
                1 ) );

        final SearchWikiTool tool = new SearchWikiTool( engine, new ToolsConfig( new Properties() ) );
        final Map< String, Object > result = tool.execute( "q", 10, request );
        @SuppressWarnings( "unchecked" )
        final List< Map< String, Object > > results =
            (List< Map< String, Object > >) result.get( "results" );
        @SuppressWarnings( "unchecked" )
        final List< Map< String, Object > > related =
            (List< Map< String, Object > >) results.get( 0 ).get( "relatedPages" );
        assertNotNull( related );
        assertEquals( 2, related.size() );
        assertEquals( "Beta", related.get( 0 ).get( "name" ) );
        assertEquals( "shared entities: x, y", related.get( 0 ).get( "reason" ) );
    }

    @Test
    void execute_omitsContributingChunksWhenEmpty() {
        when( engine.getManager( ContextRetrievalService.class ) ).thenReturn( ctxService );
        when( ctxService.retrieve( any( ContextQuery.class ) ) )
            .thenReturn( new RetrievalResult(
                "q",
                List.of( new RetrievedPage(
                    "Alpha", "", 5.0, "alpha summary", null, List.of(),
                    List.of(), List.of(), null, null ) ),
                1 ) );

        final SearchWikiTool tool = new SearchWikiTool( engine, new ToolsConfig( new Properties() ) );
        final Map< String, Object > result = tool.execute( "q", 10, request );
        @SuppressWarnings( "unchecked" )
        final List< Map< String, Object > > results =
            (List< Map< String, Object > >) result.get( "results" );
        assertFalse( results.get( 0 ).containsKey( "contributingChunks" ),
            "empty chunks should be omitted, not serialized as []" );
        assertFalse( results.get( 0 ).containsKey( "relatedPages" ),
            "empty related should be omitted, not serialized as []" );
        assertFalse( results.get( 0 ).containsKey( "snippet" ),
            "no snippet when no chunks to source from" );
    }
}
