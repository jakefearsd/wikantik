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
import com.wikantik.api.connectors.DriveAuthCoordinator.AuthResult;
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
        AuthResult completeResult = AuthResult.SUCCESS;
        String lastCompleteId, lastCompleteCode;
        public Optional<String> authorizationUrl( String id, String state ) {
            return "gd".equals( id ) ? Optional.of( urlForGd + state ) : Optional.empty();
        }
        public AuthResult completeAuthorization( String id, String code ) {
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
        when( req.getContextPath() ).thenReturn( "" );
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
        StubCoordinator c = new StubCoordinator(); c.completeResult = AuthResult.EXCHANGE_FAILED;
        attrs.put( "gdrive.oauth.state", "S1" ); attrs.put( "gdrive.oauth.connector", "gd" );
        when( req.getPathInfo() ).thenReturn( "/callback" );
        when( req.getParameter( "state" ) ).thenReturn( "S1" );
        when( req.getParameter( "code" ) ).thenReturn( "AUTHCODE" );
        new TestResource( c ).doGet( req, resp );
        verify( resp ).setStatus( 502 );
    }

    @Test void callbackStoreDisabledIs503() throws Exception {
        StubCoordinator c = new StubCoordinator(); c.completeResult = AuthResult.STORE_DISABLED;
        attrs.put( "gdrive.oauth.state", "S1" ); attrs.put( "gdrive.oauth.connector", "gd" );
        when( req.getPathInfo() ).thenReturn( "/callback" );
        when( req.getParameter( "state" ) ).thenReturn( "S1" );
        when( req.getParameter( "code" ) ).thenReturn( "AUTHCODE" );
        new TestResource( c ).doGet( req, resp );
        verify( resp ).setStatus( 503 );
    }

    @Test void callbackUnknownConnectorIs404() throws Exception {
        StubCoordinator c = new StubCoordinator(); c.completeResult = AuthResult.UNKNOWN_CONNECTOR;
        attrs.put( "gdrive.oauth.state", "S1" ); attrs.put( "gdrive.oauth.connector", "gone" );
        when( req.getPathInfo() ).thenReturn( "/callback" );
        when( req.getParameter( "state" ) ).thenReturn( "S1" );
        when( req.getParameter( "code" ) ).thenReturn( "AUTHCODE" );
        new TestResource( c ).doGet( req, resp );
        verify( resp ).setStatus( 404 );
    }

    @Test void coordinatorAbsentIs503() throws Exception {
        when( req.getPathInfo() ).thenReturn( "/gd/authorize" );
        new TestResource( null ).doGet( req, resp );
        verify( resp ).setStatus( 503 );
    }

    @Test void authorizeStoresValidReturnTo() throws Exception {
        StubCoordinator c = new StubCoordinator();
        when( req.getPathInfo() ).thenReturn( "/gd/authorize" );
        when( req.getParameter( "return_to" ) ).thenReturn( "/admin/connectors/new" );
        new TestResource( c ).doGet( req, resp );
        verify( resp ).sendRedirect( anyString() );
        assertEquals( "/admin/connectors/new", attrs.get( "gdrive.oauth.return_to" ) );
    }

    @Test void authorizeRejectsForeignReturnTo() throws Exception {
        for ( final String bad : List.of( "https://evil.com", "/wiki/x", "//evil" ) ) {
            attrs.clear();
            StubCoordinator c = new StubCoordinator();
            when( req.getPathInfo() ).thenReturn( "/gd/authorize" );
            when( req.getParameter( "return_to" ) ).thenReturn( bad );
            new TestResource( c ).doGet( req, resp );
            assertNull( attrs.get( "gdrive.oauth.return_to" ), "return_to '" + bad + "' must be rejected" );
        }
    }

    @Test void callbackRedirectsBackWithOutcome() throws Exception {
        StubCoordinator c = new StubCoordinator();
        attrs.put( "gdrive.oauth.state", "S1" ); attrs.put( "gdrive.oauth.connector", "gd" );
        attrs.put( "gdrive.oauth.return_to", "/admin/connectors/new" );
        when( req.getPathInfo() ).thenReturn( "/callback" );
        when( req.getParameter( "state" ) ).thenReturn( "S1" );
        when( req.getParameter( "code" ) ).thenReturn( "AUTHCODE" );
        new TestResource( c ).doGet( req, resp );
        ArgumentCaptor<String> loc = ArgumentCaptor.forClass( String.class );
        verify( resp ).sendRedirect( loc.capture() );
        assertTrue( loc.getValue().endsWith( "?oauth=ok" ), "was: " + loc.getValue() );
        assertNull( attrs.get( "gdrive.oauth.return_to" ), "return_to must be single-use (cleared)" );

        // failure case — a fresh resource/response, return_to re-seeded
        attrs.clear();
        attrs.put( "gdrive.oauth.state", "S1" ); attrs.put( "gdrive.oauth.connector", "gd" );
        attrs.put( "gdrive.oauth.return_to", "/admin/connectors/new" );
        HttpServletResponse resp2 = mock( HttpServletResponse.class );
        when( resp2.getWriter() ).thenReturn( new PrintWriter( new StringWriter() ) );
        c.completeResult = AuthResult.EXCHANGE_FAILED;
        new TestResource( c ).doGet( req, resp2 );
        ArgumentCaptor<String> loc2 = ArgumentCaptor.forClass( String.class );
        verify( resp2 ).sendRedirect( loc2.capture() );
        assertTrue( loc2.getValue().endsWith( "?oauth=exchange_failed" ), "was: " + loc2.getValue() );
    }

    @Test void callbackWithoutReturnToKeepsJson() throws Exception {
        StubCoordinator c = new StubCoordinator();
        attrs.put( "gdrive.oauth.state", "S1" ); attrs.put( "gdrive.oauth.connector", "gd" );
        when( req.getPathInfo() ).thenReturn( "/callback" );
        when( req.getParameter( "state" ) ).thenReturn( "S1" );
        when( req.getParameter( "code" ) ).thenReturn( "AUTHCODE" );
        new TestResource( c ).doGet( req, resp );
        verify( resp ).setStatus( 200 );
        verify( resp, never() ).sendRedirect( anyString() );
    }

    // ---- mutation-testing follow-up

    /** Resource whose connector-config service is injected, to observe the post-authorization rebuild. */
    static final class RebuildResource extends GoogleDriveAuthResource {
        final DriveAuthCoordinator c; final com.wikantik.derived.ConnectorConfigService cfg;
        RebuildResource( DriveAuthCoordinator c, com.wikantik.derived.ConnectorConfigService cfg ) { this.c = c; this.cfg = cfg; }
        @Override protected DriveAuthCoordinator resolveCoordinator() { return c; }
        @Override protected com.wikantik.derived.ConnectorConfigService resolveConfigService() { return cfg; }
    }

    private void seedCallback( final String stateInSession, final String connector ) {
        if ( stateInSession != null ) attrs.put( "gdrive.oauth.state", stateInSession );
        if ( connector != null ) attrs.put( "gdrive.oauth.connector", connector );
        attrs.put( "gdrive.oauth.return_to", "/admin/connectors/new" );
    }

    @Test void authorizeWithNoConnectorIdIs404NotAServerError() throws Exception {
        when( req.getPathInfo() ).thenReturn( "/authorize" );
        new TestResource( new StubCoordinator() ).doGet( req, resp );
        verify( resp ).setStatus( 404 );
        verify( resp, never() ).sendRedirect( anyString() );
        assertNull( attrs.get( "gdrive.oauth.state" ) );
    }

    @Test void authorizeWithANestedConnectorIdIs404AndStartsNoFlow() throws Exception {
        when( req.getPathInfo() ).thenReturn( "/a/gd/authorize" );
        new TestResource( new StubCoordinator() ).doGet( req, resp );
        verify( resp ).setStatus( 404 );
        verify( resp, never() ).sendRedirect( anyString() );
        assertNull( attrs.get( "gdrive.oauth.state" ), "no state may be stored for a refused connector id" );
    }

    @Test void authorizeForAnUnknownConnectorStoresNoState() throws Exception {
        when( req.getPathInfo() ).thenReturn( "/nope/authorize" );
        new TestResource( new StubCoordinator() ).doGet( req, resp );
        assertNull( attrs.get( "gdrive.oauth.state" ) );
        assertNull( attrs.get( "gdrive.oauth.connector" ) );
    }

    @Test void everyAuthorizeIssuesAFreshUnguessableState() throws Exception {
        final Set<String> seen = new HashSet<>();
        for ( int n = 0; n < 5; n++ ) {
            attrs.clear();
            when( req.getPathInfo() ).thenReturn( "/gd/authorize" );
            new TestResource( new StubCoordinator() ).doGet( req, resp );
            final String state = (String) attrs.get( "gdrive.oauth.state" );
            assertTrue( state.matches( "[A-Za-z0-9_-]{43}" ), "256 bits, url-safe base64 without padding: " + state );
            seen.add( state );
        }
        assertEquals( 5, seen.size() );
    }

    @Test void anUnknownRouteIs404() throws Exception {
        when( req.getPathInfo() ).thenReturn( "/something" );
        new TestResource( new StubCoordinator() ).doGet( req, resp );
        verify( resp ).setStatus( 404 );
        when( req.getPathInfo() ).thenReturn( null );
        final HttpServletResponse resp2 = mock( HttpServletResponse.class );
        when( resp2.getWriter() ).thenReturn( new PrintWriter( new StringWriter() ) );
        new TestResource( new StubCoordinator() ).doGet( req, resp2 );
        verify( resp2 ).setStatus( 404 );
    }

    @Test void aReplayedCallbackIsRefusedBecauseTheStateWasConsumed() throws Exception {
        final StubCoordinator c = new StubCoordinator();
        seedCallback( "S1", "gd" );
        when( req.getPathInfo() ).thenReturn( "/callback" );
        when( req.getParameter( "state" ) ).thenReturn( "S1" );
        when( req.getParameter( "code" ) ).thenReturn( "AUTHCODE" );
        new TestResource( c ).doGet( req, resp );
        assertEquals( "AUTHCODE", c.lastCompleteCode );
        assertNull( attrs.get( "gdrive.oauth.state" ) );
        assertNull( attrs.get( "gdrive.oauth.connector" ) );

        c.lastCompleteCode = null;
        final HttpServletResponse replay = mock( HttpServletResponse.class );
        when( replay.getWriter() ).thenReturn( new PrintWriter( new StringWriter() ) );
        new TestResource( c ).doGet( req, replay );
        verify( replay ).setStatus( 400 );
        assertNull( c.lastCompleteCode, "the replay must not exchange the code again" );
    }

    @Test void aMismatchedStateStillConsumesTheStoredStateAndReturnTo() throws Exception {
        seedCallback( "S1", "gd" );
        when( req.getPathInfo() ).thenReturn( "/callback" );
        when( req.getParameter( "state" ) ).thenReturn( "WRONG" );
        when( req.getParameter( "code" ) ).thenReturn( "AUTHCODE" );
        new TestResource( new StubCoordinator() ).doGet( req, resp );
        assertNull( attrs.get( "gdrive.oauth.state" ) );
        assertNull( attrs.get( "gdrive.oauth.connector" ) );
        assertNull( attrs.get( "gdrive.oauth.return_to" ) );
        verify( resp, never() ).sendRedirect( anyString() );
    }

    @Test void aCallbackWithoutAStateParameterOrASessionStateOrAConnectorIsRefused() throws Exception {
        final String[][] cases = { { "S1", "gd", null }, { null, "gd", "S1" }, { "S1", null, "S1" } };
        for ( final String[] k : cases ) {
            attrs.clear();
            final StubCoordinator c = new StubCoordinator();
            seedCallback( k[0], k[1] );
            when( req.getPathInfo() ).thenReturn( "/callback" );
            when( req.getParameter( "state" ) ).thenReturn( k[2] );
            when( req.getParameter( "code" ) ).thenReturn( "AUTHCODE" );
            final HttpServletResponse r = mock( HttpServletResponse.class );
            when( r.getWriter() ).thenReturn( new PrintWriter( new StringWriter() ) );
            new TestResource( c ).doGet( req, r );
            verify( r ).setStatus( 400 );
            assertNull( c.lastCompleteId, "no exchange for " + Arrays.toString( k ) );
        }
    }

    @Test void aCallbackWithoutACodeIs400AndDoesNotExchange() throws Exception {
        for ( final String code : new String[] { null, "", "   " } ) {
            attrs.clear();
            final StubCoordinator c = new StubCoordinator();
            seedCallback( "S1", "gd" );
            when( req.getPathInfo() ).thenReturn( "/callback" );
            when( req.getParameter( "state" ) ).thenReturn( "S1" );
            when( req.getParameter( "code" ) ).thenReturn( code );
            final HttpServletResponse r = mock( HttpServletResponse.class );
            when( r.getWriter() ).thenReturn( new PrintWriter( new StringWriter() ) );
            new TestResource( c ).doGet( req, r );
            verify( r ).setStatus( 400 );
            assertNull( c.lastCompleteId );
        }
    }

    @Test void theCallbackRedirectIsPrefixedWithTheContextPathAndJoinsAnExistingQueryWithAnAmpersand() throws Exception {
        when( req.getContextPath() ).thenReturn( "/wikantik" );
        attrs.put( "gdrive.oauth.state", "S1" ); attrs.put( "gdrive.oauth.connector", "gd" );
        attrs.put( "gdrive.oauth.return_to", "/admin/connectors/new?step=2" );
        when( req.getPathInfo() ).thenReturn( "/callback" );
        when( req.getParameter( "state" ) ).thenReturn( "S1" );
        when( req.getParameter( "code" ) ).thenReturn( "AUTHCODE" );
        new TestResource( new StubCoordinator() ).doGet( req, resp );
        verify( resp ).sendRedirect( "/wikantik/admin/connectors/new?step=2&oauth=ok" );
    }

    @Test void aSuccessfulExchangeRebuildsTheLiveConnectorsAndAFailedOneDoesNot() throws Exception {
        final com.wikantik.derived.ConnectorConfigService config = mock( com.wikantik.derived.ConnectorConfigService.class );
        final StubCoordinator ok = new StubCoordinator();
        seedCallback( "S1", "gd" );
        when( req.getPathInfo() ).thenReturn( "/callback" );
        when( req.getParameter( "state" ) ).thenReturn( "S1" );
        when( req.getParameter( "code" ) ).thenReturn( "AUTHCODE" );
        new RebuildResource( ok, config ).doGet( req, resp );
        verify( config ).rebuild();

        final com.wikantik.derived.ConnectorConfigService config2 = mock( com.wikantik.derived.ConnectorConfigService.class );
        final StubCoordinator failing = new StubCoordinator();
        failing.completeResult = AuthResult.EXCHANGE_FAILED;
        attrs.clear();
        seedCallback( "S1", "gd" );
        final HttpServletResponse r = mock( HttpServletResponse.class );
        when( r.getWriter() ).thenReturn( new PrintWriter( new StringWriter() ) );
        new RebuildResource( failing, config2 ).doGet( req, r );
        verify( config2, never() ).rebuild();
    }

    @Test void aRebuildThatThrowsDoesNotFailTheAuthorizationResponse() throws Exception {
        final com.wikantik.derived.ConnectorConfigService config = mock( com.wikantik.derived.ConnectorConfigService.class );
        doThrow( new IllegalStateException( "boom" ) ).when( config ).rebuild();
        seedCallback( "S1", "gd" );
        attrs.remove( "gdrive.oauth.return_to" );
        when( req.getPathInfo() ).thenReturn( "/callback" );
        when( req.getParameter( "state" ) ).thenReturn( "S1" );
        when( req.getParameter( "code" ) ).thenReturn( "AUTHCODE" );
        new RebuildResource( new StubCoordinator(), config ).doGet( req, resp );
        verify( resp ).setStatus( 200 );
    }
}
