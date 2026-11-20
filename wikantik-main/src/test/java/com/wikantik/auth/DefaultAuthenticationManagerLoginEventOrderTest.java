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
package com.wikantik.auth;

import com.wikantik.HttpMockFactory;
import com.wikantik.TestEngine;
import com.wikantik.WikiSession;
import com.wikantik.api.core.Session;
import com.wikantik.api.spi.Wiki;
import com.wikantik.auth.authorize.Role;
import com.wikantik.event.WikiEvent;
import com.wikantik.event.WikiEventListener;
import com.wikantik.event.WikiSecurityEvent;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpSession;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Pins the order of the login success tail of {@link DefaultAuthenticationManager}: one
 * {@code LOGIN_AUTHENTICATED}, then one {@code PRINCIPAL_ADD} per login principal, then the
 * authorizer-role {@code PRINCIPAL_ADD}s. The username/password path additionally rotates the
 * HTTP session id between the principals and the roles; the container path never rotates.
 */
class DefaultAuthenticationManagerLoginEventOrderTest {

    private static final String ROTATED = "ROTATED";

    private TestEngine engine;
    private AuthenticationManager auth;
    private final List< String > trail = new ArrayList<>();
    private final WikiEventListener recorder = ( final WikiEvent event ) -> {
        if( event instanceof WikiSecurityEvent e ) {
            trail.add( e.getType() + ":" + ( ( java.security.Principal ) e.getPrincipal() ).getName() );
        }
    };

    @BeforeEach
    void setUp() throws Exception {
        engine = new TestEngine( TestEngine.getTestProperties() );
        auth = engine.getManager( AuthenticationManager.class );
        auth.addWikiEventListener( recorder );
    }

    @AfterEach
    void tearDown() {
        auth.removeWikiEventListener( recorder );
    }

    private static boolean isPrincipalAdd( final String entry ) {
        return entry.startsWith( WikiSecurityEvent.PRINCIPAL_ADD + ":" );
    }

    @Test
    void containerLogin_firesAuthenticatedThenPrincipalsThenRoles_withoutRotation() throws Exception {
        final HttpSession httpSession = Mockito.mock( HttpSession.class );
        Mockito.doReturn( "mock-session-order-container" ).when( httpSession ).getId();
        final HttpServletRequest request = HttpMockFactory.createHttpRequest();
        Mockito.doReturn( httpSession ).when( request ).getSession();
        Mockito.doReturn( httpSession ).when( request ).getSession( Mockito.anyBoolean() );
        Mockito.doReturn( new com.wikantik.auth.WikiPrincipal( "containerUser" ) ).when( request ).getUserPrincipal();
        Mockito.doReturn( true ).when( request ).isUserInRole( Role.AUTHENTICATED.getName() );
        Wiki.session().find( engine, request );
        trail.clear();

        auth.login( request );

        assertFalse( trail.isEmpty() );
        assertEquals( WikiSecurityEvent.LOGIN_AUTHENTICATED + ":containerUser", trail.get( 0 ), trail.toString() );
        // After LOGIN_AUTHENTICATED only PRINCIPAL_ADD events follow: login principals, then roles.
        trail.stream().skip( 1 ).forEach( e -> assertTrue( isPrincipalAdd( e ), trail.toString() ) );
        assertTrue( trail.size() >= 2, "at least the login principal must be added: " + trail );
        assertTrue( trail.contains( WikiSecurityEvent.PRINCIPAL_ADD + ":" + Role.AUTHENTICATED.getName() ),
                "the authorizer roles must be injected last: " + trail );
        assertEquals( WikiSecurityEvent.PRINCIPAL_ADD + ":" + Role.AUTHENTICATED.getName(), trail.get( trail.size() - 1 ),
                trail.toString() );
        Mockito.verify( request, Mockito.never() ).changeSessionId();
    }

    @Test
    void passwordLogin_firesAuthenticatedThenPrincipals_thenRotatesThenRoles() throws Exception {
        final HttpSession httpSession = Mockito.mock( HttpSession.class );
        Mockito.doReturn( "mock-session-order-password" ).when( httpSession ).getId();
        final HttpServletRequest request = Mockito.mock( HttpServletRequest.class );
        Mockito.doReturn( httpSession ).when( request ).getSession( false );
        Mockito.doAnswer( invocation -> {
            trail.add( ROTATED );
            return "new-id";
        } ).when( request ).changeSessionId();
        final Session session = WikiSession.guestSession( engine );

        assertTrue( auth.login( session, request, Users.JANNE, Users.JANNE_PASS ) );

        assertEquals( WikiSecurityEvent.LOGIN_AUTHENTICATED + ":" + Users.JANNE, trail.get( 0 ), trail.toString() );
        final int rotation = trail.indexOf( ROTATED );
        assertTrue( rotation >= 2, "rotation comes after LOGIN_AUTHENTICATED and the login principals: " + trail );
        assertEquals( 1, trail.stream().filter( ROTATED::equals ).count(), trail.toString() );
        trail.subList( 1, rotation ).forEach( e -> assertTrue( isPrincipalAdd( e ), trail.toString() ) );
        assertTrue( rotation < trail.size() - 1, "authorizer roles are injected after the rotation: " + trail );
        trail.subList( rotation + 1, trail.size() ).forEach( e -> assertTrue( isPrincipalAdd( e ), trail.toString() ) );
        assertEquals( WikiSecurityEvent.PRINCIPAL_ADD + ":" + Role.AUTHENTICATED.getName(), trail.get( trail.size() - 1 ),
                trail.toString() );
    }
}
