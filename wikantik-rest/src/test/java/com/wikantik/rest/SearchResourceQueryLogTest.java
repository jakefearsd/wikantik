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
package com.wikantik.rest;

import com.wikantik.api.knowledge.ContextRetrievalService;
import com.wikantik.api.knowledge.RetrievalResult;
import com.wikantik.api.querylog.ActorType;
import com.wikantik.api.querylog.QueryLogService;
import com.wikantik.api.querylog.SourceSurface;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.junit.jupiter.api.Test;

import java.io.PrintWriter;
import java.io.StringWriter;
import java.util.List;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoMoreInteractions;
import static org.mockito.Mockito.when;

class SearchResourceQueryLogTest {

    @Test
    void doGet_logsQuery_withApiSearchSurfaceAndResultCount() throws Exception {
        final ContextRetrievalService ctx = mock( ContextRetrievalService.class );
        when( ctx.retrieve( any() ) ).thenReturn( new RetrievalResult( "deploy", List.of(), 0 ) );
        final QueryLogService qlog = mock( QueryLogService.class );
        final SearchResource servlet = new SearchResource() {
            @Override protected ContextRetrievalService retrievalService() { return ctx; }
            @Override protected QueryLogService queryLogService() { return qlog; }
            @Override protected ActorType actorType( final HttpServletRequest r ) { return ActorType.AGENT; }
            // Pass-through: view filtering is not this test's subject and there is no
            // engine wired. Without this the base implementation resolves a null engine
            // and only survived when a stale ThreadLocal guest session from an earlier
            // test on the same fork thread leaked in (order-dependent flake).
            @Override protected java.util.Set< String > filterViewable( final HttpServletRequest r,
                    final java.util.Collection< String > pageNames ) {
                return new java.util.LinkedHashSet<>( pageNames );
            }
        };
        final HttpServletRequest req = mock( HttpServletRequest.class );
        when( req.getParameter( "q" ) ).thenReturn( "deploy" );
        final HttpServletResponse resp = mock( HttpServletResponse.class );
        when( resp.getWriter() ).thenReturn( new PrintWriter( new StringWriter() ) );

        servlet.doGet( req, resp );

        // logs the ORIGINAL user query (not the Lucene-escaped form); api_search surface; 0 = zero-result signal
        verify( qlog ).log( "deploy", ActorType.AGENT, SourceSurface.API_SEARCH, 0 );
    }

    /**
     * The write-amplification bug: the search box hits {@code /api/search} on every keystroke.
     * Before the fix, typing "Per" -> "Pers" -> "Personal" then submitting "Personal Finance"
     * wrote FOUR rows — the 90-day prod sample reading literal partial words is this bug. Only
     * the final, explicitly-submitted query may be logged.
     */
    @Test
    void doGet_onlyTheSubmittedQuery_isLogged_notEachTypeaheadKeystroke() throws Exception {
        final ContextRetrievalService ctx = mock( ContextRetrievalService.class );
        when( ctx.retrieve( any() ) ).thenReturn( new RetrievalResult( "x", List.of(), 0 ) );
        final QueryLogService qlog = mock( QueryLogService.class );
        final SearchResource servlet = new SearchResource() {
            @Override protected ContextRetrievalService retrievalService() { return ctx; }
            @Override protected QueryLogService queryLogService() { return qlog; }
            @Override protected ActorType actorType( final HttpServletRequest r ) { return ActorType.HUMAN; }
            @Override protected java.util.Set< String > filterViewable( final HttpServletRequest r,
                    final java.util.Collection< String > pageNames ) {
                return new java.util.LinkedHashSet<>( pageNames );
            }
        };

        // Three incremental typeahead requests as the reader types...
        fireSearch( servlet, "Per", true );
        fireSearch( servlet, "Pers", true );
        fireSearch( servlet, "Personal", true );
        // ...then the actual submitted search.
        fireSearch( servlet, "Personal Finance", false );

        verify( qlog, times( 1 ) ).log( eq( "Personal Finance" ), eq( ActorType.HUMAN ),
            eq( SourceSurface.API_SEARCH ), anyInt() );
        verify( qlog, never() ).log( eq( "Per" ), any(), any(), any() );
        verify( qlog, never() ).log( eq( "Pers" ), any(), any(), any() );
        verify( qlog, never() ).log( eq( "Personal" ), any(), any(), any() );
        verifyNoMoreInteractions( qlog );
    }

    private static void fireSearch( final SearchResource servlet, final String query, final boolean typeahead )
            throws Exception {
        final HttpServletRequest req = mock( HttpServletRequest.class );
        when( req.getParameter( "q" ) ).thenReturn( query );
        if ( typeahead ) {
            when( req.getParameter( "typeahead" ) ).thenReturn( "true" );
        }
        final HttpServletResponse resp = mock( HttpServletResponse.class );
        when( resp.getWriter() ).thenReturn( new PrintWriter( new StringWriter() ) );
        servlet.doGet( req, resp );
    }
}
