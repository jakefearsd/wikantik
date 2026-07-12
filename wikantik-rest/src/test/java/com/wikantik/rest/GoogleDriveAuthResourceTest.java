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

import com.wikantik.api.connectors.DriveAuthCoordinator;
import jakarta.servlet.http.*;
import org.junit.jupiter.api.*;
import org.mockito.ArgumentCaptor;
import java.io.PrintWriter;
import java.io.StringWriter;
import java.util.*;
import static org.mockito.Mockito.*;
import static org.junit.jupiter.api.Assertions.*;

class GoogleDriveAuthResourceTest {

    static final class StubCoordinator implements DriveAuthCoordinator {
        String urlForGd = "https://accounts.google.com/o/oauth2/auth?state=";
        boolean completeResult = true;
        String lastCompleteId, lastCompleteCode;
        public Optional<String> authorizationUrl( String id, String state ) {
            return "gd".equals( id ) ? Optional.of( urlForGd + state ) : Optional.empty();
        }
        public boolean completeAuthorization( String id, String code ) {
            lastCompleteId = id; lastCompleteCode = code; return completeResult;
        }
    }
    /** Test subclass injecting the stub + capturing sendRedirect targets. */
    static final class TestResource extends GoogleDriveAuthResource {
        final DriveAuthCoordinator c; TestResource( DriveAuthCoordinator c ) { this.c = c; }
        @Override protected DriveAuthCoordinator resolveCoordinator() { return c; }
    }

    HttpServletRequest req; HttpServletResponse resp; HttpSession session; Map<String,Object> attrs;

    @BeforeEach void setup() throws Exception {
        req = mock( HttpServletRequest.class ); resp = mock( HttpServletResponse.class );
        session = mock( HttpSession.class ); attrs = new HashMap<>();
        // RestServletBase.sendError/sendJson write to response.getWriter(); a bare Mockito mock
        // returns null from getWriter() by default, so stub it — mirrors ConnectorCredentialsResourceTest.
        when( resp.getWriter() ).thenReturn( new PrintWriter( new StringWriter() ) );
        when( req.getSession( anyBoolean() ) ).thenReturn( session );
        when( req.getSession() ).thenReturn( session );
        doAnswer( i -> attrs.put( i.getArgument( 0 ), i.getArgument( 1 ) ) ).when( session ).setAttribute( any(), any() );
        when( session.getAttribute( any() ) ).thenAnswer( i -> attrs.get( i.getArgument( 0 ) ) );
        doAnswer( i -> attrs.remove( i.getArgument( 0 ) ) ).when( session ).removeAttribute( any() );
    }

    @Test void authorizeRedirectsToConsentUrlAndStoresState() throws Exception {
        StubCoordinator c = new StubCoordinator();
        when( req.getPathInfo() ).thenReturn( "/gd/authorize" );
        new TestResource( c ).doGet( req, resp );
        ArgumentCaptor<String> loc = ArgumentCaptor.forClass( String.class );
        verify( resp ).sendRedirect( loc.capture() );
        String state = (String) attrs.get( "gdrive.oauth.state" );
        assertNotNull( state );
        assertEquals( "gd", attrs.get( "gdrive.oauth.connector" ) );
        assertTrue( loc.getValue().contains( "state=" + state ) );
    }

    @Test void authorizeUnknownIdIs404() throws Exception {
        when( req.getPathInfo() ).thenReturn( "/nope/authorize" );
        new TestResource( new StubCoordinator() ).doGet( req, resp );
        verify( resp ).setStatus( 404 );
        verify( resp, never() ).sendRedirect( anyString() );
    }

    @Test void callbackWithMatchingStateCompletesAuthorization() throws Exception {
        StubCoordinator c = new StubCoordinator();
        attrs.put( "gdrive.oauth.state", "S1" ); attrs.put( "gdrive.oauth.connector", "gd" );
        when( req.getPathInfo() ).thenReturn( "/callback" );
        when( req.getParameter( "state" ) ).thenReturn( "S1" );
        when( req.getParameter( "code" ) ).thenReturn( "AUTHCODE" );
        new TestResource( c ).doGet( req, resp );
        assertEquals( "gd", c.lastCompleteId );
        assertEquals( "AUTHCODE", c.lastCompleteCode );
        verify( resp ).setStatus( 200 );
        assertNull( attrs.get( "gdrive.oauth.state" ), "state must be single-use (cleared)" );
    }

    @Test void callbackWithMismatchedStateIs400AndDoesNotExchange() throws Exception {
        StubCoordinator c = new StubCoordinator();
        attrs.put( "gdrive.oauth.state", "S1" ); attrs.put( "gdrive.oauth.connector", "gd" );
        when( req.getPathInfo() ).thenReturn( "/callback" );
        when( req.getParameter( "state" ) ).thenReturn( "WRONG" );
        when( req.getParameter( "code" ) ).thenReturn( "AUTHCODE" );
        new TestResource( c ).doGet( req, resp );
        verify( resp ).setStatus( 400 );
        assertNull( c.lastCompleteId, "no exchange on state mismatch" );
    }

    @Test void callbackExchangeFailureIs502() throws Exception {
        StubCoordinator c = new StubCoordinator(); c.completeResult = false;
        attrs.put( "gdrive.oauth.state", "S1" ); attrs.put( "gdrive.oauth.connector", "gd" );
        when( req.getPathInfo() ).thenReturn( "/callback" );
        when( req.getParameter( "state" ) ).thenReturn( "S1" );
        when( req.getParameter( "code" ) ).thenReturn( "AUTHCODE" );
        new TestResource( c ).doGet( req, resp );
        verify( resp ).setStatus( 502 );
    }

    @Test void coordinatorAbsentIs503() throws Exception {
        when( req.getPathInfo() ).thenReturn( "/gd/authorize" );
        new TestResource( null ).doGet( req, resp );
        verify( resp ).setStatus( 503 );
    }
}
