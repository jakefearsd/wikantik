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
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
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
}
