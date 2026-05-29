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

import com.wikantik.api.comments.PageOwnership;
import com.wikantik.comments.PageOwnerService;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.junit.jupiter.api.Test;

import java.io.PrintWriter;
import java.io.StringWriter;
import java.time.Instant;
import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.*;

class MyPagesResourceTest {

    private StringWriter capture( final HttpServletResponse resp ) throws Exception {
        final StringWriter sw = new StringWriter();
        when( resp.getWriter() ).thenReturn( new PrintWriter( sw ) );
        return sw;
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
        assertTrue( sw.toString().contains( "\"error\"" ), "response body should contain error field" );
    }

    @Test
    void returnsOnlyCallersOwnedPages() throws Exception {
        final HttpServletRequest req = mock( HttpServletRequest.class );
        final HttpServletResponse resp = mock( HttpServletResponse.class );
        when( req.getParameter( "limit" ) ).thenReturn( null );
        final PageOwnerService owners = mock( PageOwnerService.class );
        when( owners.listByOwner( "alice", 15, 0 ) ).thenReturn( List.of(
                new PageOwnership( "cid-1", "alice", "alice", Instant.parse( "2026-05-01T00:00:00Z" ) ) ) );
        final StringWriter sw = capture( resp );

        final MyPagesResource res = new MyPagesResource() {
            @Override protected boolean isAuthenticated( final HttpServletRequest r ) { return true; }
            @Override protected String currentUser( final HttpServletRequest r ) { return "alice"; }
            @Override protected PageOwnerService pageOwners() { return owners; }
            @Override protected Optional< String > resolveSlug( final String cid ) { return Optional.of( "Alice/Notes" ); }
        };
        res.doGet( req, resp );

        verify( owners ).listByOwner( "alice", 15, 0 );
        assertTrue( sw.toString().contains( "Alice/Notes" ), "response should include resolved slug" );
        assertTrue( sw.toString().contains( "cid-1" ), "response should include canonicalId" );
    }

    @Test
    void clampsLimitToMax() throws Exception {
        final HttpServletRequest req = mock( HttpServletRequest.class );
        final HttpServletResponse resp = mock( HttpServletResponse.class );
        when( req.getParameter( "limit" ) ).thenReturn( "500" );
        final PageOwnerService owners = mock( PageOwnerService.class );
        when( owners.listByOwner( "alice", 100, 0 ) ).thenReturn( List.of() );
        capture( resp );

        final MyPagesResource res = new MyPagesResource() {
            @Override protected boolean isAuthenticated( final HttpServletRequest r ) { return true; }
            @Override protected String currentUser( final HttpServletRequest r ) { return "alice"; }
            @Override protected PageOwnerService pageOwners() { return owners; }
            @Override protected Optional< String > resolveSlug( final String cid ) { return Optional.empty(); }
        };
        res.doGet( req, resp );

        verify( owners ).listByOwner( "alice", 100, 0 );
    }
}
