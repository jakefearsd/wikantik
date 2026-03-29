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

import com.wikantik.MockEngineBuilder;
import com.wikantik.api.core.Engine;
import com.wikantik.auth.authorize.Role;
import com.wikantik.auth.authorize.WebAuthorizer;
import com.wikantik.auth.authorize.WebContainerAuthorizer;
import com.wikantik.api.core.Session;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.security.Principal;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;


/**
 * Unit tests for {@link DefaultAuthenticationManager} using constructor injection.
 * Validates the refactored AuthorizationManager field without spinning up a full TestEngine.
 */
class DefaultAuthenticationManagerCITest {

    private Engine engine;
    private AuthorizationManager authorizationManager;
    private DefaultAuthenticationManager mgr;

    @BeforeEach
    void setUp() {
        authorizationManager = mock( AuthorizationManager.class );
        engine = MockEngineBuilder.engine()
                .with( AuthorizationManager.class, authorizationManager )
                .build();
        mgr = new DefaultAuthenticationManager( engine, authorizationManager );
    }

    // ==================== isContainerAuthenticated ====================

    @Test
    void isContainerAuthenticatedReturnsTrueWhenWebContainerAuthorizer() throws WikiSecurityException {
        final WebContainerAuthorizer wca = mock( WebContainerAuthorizer.class );
        when( wca.isContainerAuthorized() ).thenReturn( true );
        when( authorizationManager.getAuthorizer() ).thenReturn( wca );

        assertTrue( mgr.isContainerAuthenticated() );
    }

    @Test
    void isContainerAuthenticatedReturnsFalseWhenNotWebContainer() throws WikiSecurityException {
        final Authorizer nonContainer = mock( Authorizer.class );
        when( authorizationManager.getAuthorizer() ).thenReturn( nonContainer );

        assertFalse( mgr.isContainerAuthenticated() );
    }

    @Test
    void isContainerAuthenticatedReturnsFalseWhenAuthorizerThrows() throws WikiSecurityException {
        when( authorizationManager.getAuthorizer() ).thenThrow( new WikiSecurityException( "not initialized" ) );

        assertFalse( mgr.isContainerAuthenticated() );
    }

    @Test
    void isContainerAuthenticatedReturnsFalseWhenContainerNotAuthorized() throws WikiSecurityException {
        final WebContainerAuthorizer wca = mock( WebContainerAuthorizer.class );
        when( wca.isContainerAuthorized() ).thenReturn( false );
        when( authorizationManager.getAuthorizer() ).thenReturn( wca );

        assertFalse( mgr.isContainerAuthenticated() );
    }

    // ==================== Static helper methods ====================

    @Test
    void isUserPrincipalReturnsTrueForWikiPrincipal() {
        assertTrue( AuthenticationManager.isUserPrincipal( new WikiPrincipal( "alice" ) ) );
    }

    @Test
    void isUserPrincipalReturnsFalseForRole() {
        assertFalse( AuthenticationManager.isUserPrincipal( Role.AUTHENTICATED ) );
    }

    @Test
    void isUserPrincipalReturnsFalseForGroupPrincipal() {
        assertFalse( AuthenticationManager.isUserPrincipal( new GroupPrincipal( "TestGroup" ) ) );
    }

    @Test
    void isRolePrincipalReturnsTrueForRole() {
        assertTrue( AuthenticationManager.isRolePrincipal( Role.AUTHENTICATED ) );
    }

    @Test
    void isRolePrincipalReturnsFalseForUserPrincipal() {
        assertFalse( AuthenticationManager.isRolePrincipal( new WikiPrincipal( "alice" ) ) );
    }

    @Test
    void isRolePrincipalReturnsTrueForGroupPrincipal() {
        assertTrue( AuthenticationManager.isRolePrincipal( new GroupPrincipal( "TestGroup" ) ) );
    }

    // ==================== allowsCookieAssertions / allowsCookieAuthentication ====================

    @Test
    void defaultCookieAssertionsAreEnabled() {
        // Default value before initialize() is called
        assertTrue( mgr.allowsCookieAssertions() );
    }

    @Test
    void defaultCookieAuthenticationIsDisabled() {
        // Default value before initialize() is called
        assertFalse( mgr.allowsCookieAuthentication() );
    }

}
