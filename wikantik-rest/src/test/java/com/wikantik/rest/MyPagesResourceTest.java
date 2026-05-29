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

import com.wikantik.api.core.Page;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.junit.jupiter.api.Test;

import java.io.PrintWriter;
import java.io.StringWriter;
import java.time.Instant;
import java.util.Collection;
import java.util.Date;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.*;

class MyPagesResourceTest {

    private StringWriter capture( final HttpServletResponse resp ) throws Exception {
        final StringWriter sw = new StringWriter();
        when( resp.getWriter() ).thenReturn( new PrintWriter( sw ) );
        return sw;
    }

    private Page page( final String name, final String author, final String isoInstant ) {
        final Page p = mock( Page.class );
        when( p.getName() ).thenReturn( name );
        when( p.getAuthor() ).thenReturn( author );
        when( p.getLastModified() ).thenReturn( Date.from( Instant.parse( isoInstant ) ) );
        return p;
    }

    @Test
    void unauthenticatedGetsUnauthorized() throws Exception {
        final HttpServletRequest req = mock( HttpServletRequest.class );
        final HttpServletResponse resp = mock( HttpServletResponse.class );
        final StringWriter sw = capture( resp );
        final MyPagesResource res = new MyPagesResource() {
            @Override protected boolean isAuthenticated( final HttpServletRequest r ) { return false; }
        };
        res.doGet( req, resp );
        verify( resp ).setStatus( HttpServletResponse.SC_UNAUTHORIZED );
        assertTrue( sw.toString().contains( "\"error\"" ), "response body should carry an error field" );
    }

    @Test
    void returnsOnlyPagesAuthoredByCaller() throws Exception {
        final HttpServletRequest req = mock( HttpServletRequest.class );
        final HttpServletResponse resp = mock( HttpServletResponse.class );
        when( req.getParameter( "limit" ) ).thenReturn( null );
        final StringWriter sw = capture( resp );

        final Collection< Page > corpus = List.of(
                page( "Alice/Notes", "alice", "2026-05-02T00:00:00Z" ),
                page( "Bob/Thing", "bob", "2026-05-03T00:00:00Z" ),
                page( "Alice/Older", "alice", "2026-05-01T00:00:00Z" ) );

        final MyPagesResource res = new MyPagesResource() {
            @Override protected boolean isAuthenticated( final HttpServletRequest r ) { return true; }
            @Override protected java.util.Set< String > currentUserIdentities( final HttpServletRequest r ) { return java.util.Set.of( "alice" ); }
            @Override protected Collection< Page > candidatePages() { return corpus; }
        };
        res.doGet( req, resp );

        final String json = sw.toString();
        assertTrue( json.contains( "Alice/Notes" ), "should include a page authored by alice" );
        assertTrue( json.contains( "Alice/Older" ), "should include all pages authored by alice" );
        assertTrue( !json.contains( "Bob/Thing" ), "must exclude pages authored by others" );
    }

    @Test
    void sortsNewestFirst() throws Exception {
        final HttpServletRequest req = mock( HttpServletRequest.class );
        final HttpServletResponse resp = mock( HttpServletResponse.class );
        when( req.getParameter( "limit" ) ).thenReturn( null );
        final StringWriter sw = capture( resp );

        // Deliberately supplied oldest-first to prove the resource sorts.
        final Collection< Page > corpus = List.of(
                page( "Alice/Older", "alice", "2026-05-01T00:00:00Z" ),
                page( "Alice/Newer", "alice", "2026-05-09T00:00:00Z" ) );

        final MyPagesResource res = new MyPagesResource() {
            @Override protected boolean isAuthenticated( final HttpServletRequest r ) { return true; }
            @Override protected java.util.Set< String > currentUserIdentities( final HttpServletRequest r ) { return java.util.Set.of( "alice" ); }
            @Override protected Collection< Page > candidatePages() { return corpus; }
        };
        res.doGet( req, resp );

        final String json = sw.toString();
        assertTrue( json.indexOf( "Alice/Newer" ) < json.indexOf( "Alice/Older" ),
                "newer page must be listed before older: " + json );
    }

    @Test
    void honoursLimit() throws Exception {
        final HttpServletRequest req = mock( HttpServletRequest.class );
        final HttpServletResponse resp = mock( HttpServletResponse.class );
        when( req.getParameter( "limit" ) ).thenReturn( "1" );
        final StringWriter sw = capture( resp );

        final Collection< Page > corpus = List.of(
                page( "Alice/A", "alice", "2026-05-01T00:00:00Z" ),
                page( "Alice/B", "alice", "2026-05-09T00:00:00Z" ) );

        final MyPagesResource res = new MyPagesResource() {
            @Override protected boolean isAuthenticated( final HttpServletRequest r ) { return true; }
            @Override protected java.util.Set< String > currentUserIdentities( final HttpServletRequest r ) { return java.util.Set.of( "alice" ); }
            @Override protected Collection< Page > candidatePages() { return corpus; }
        };
        res.doGet( req, resp );

        final String json = sw.toString();
        assertTrue( json.contains( "Alice/B" ) && !json.contains( "Alice/A" ),
                "limit=1 must return only the newest authored page: " + json );
    }
}
