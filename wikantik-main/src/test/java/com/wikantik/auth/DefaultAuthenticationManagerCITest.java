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
import com.wikantik.api.exceptions.WikiException;
import com.wikantik.auth.authorize.Role;
import com.wikantik.auth.authorize.WebAuthorizer;
import com.wikantik.auth.authorize.WebContainerAuthorizer;
import com.wikantik.auth.sso.SSOConfig;
import com.wikantik.api.core.Session;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import javax.security.auth.Subject;
import javax.security.auth.callback.CallbackHandler;
import javax.security.auth.spi.LoginModule;
import java.security.Principal;
import java.util.Map;
import java.util.Properties;

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

    // ==================== initialize(): failure paths ====================

    @Test
    void initializeWithUnresolvableLoginModuleClassThrowsWikiException() {
        final Engine badEngine = MockEngineBuilder.engine().build();
        final Properties props = new Properties();
        props.setProperty( AuthenticationManager.PROP_LOGIN_MODULE, "com.wikantik.auth.login.NoSuchLoginModuleXYZ" );
        final DefaultAuthenticationManager fresh = new DefaultAuthenticationManager();

        assertThrows( WikiException.class, () -> fresh.initialize( badEngine, props ) );
    }

    @Test
    void initializeWithDuplicateLoginModuleOptionKeyAfterTrimThrowsIllegalArgumentException() {
        final Engine e = MockEngineBuilder.engine().build();
        final Properties props = new Properties();
        // Two distinct Properties keys that both trim to the same option name "foo".
        props.setProperty( AuthenticationManager.PREFIX_LOGIN_MODULE_OPTIONS + "foo", "value1" );
        props.setProperty( AuthenticationManager.PREFIX_LOGIN_MODULE_OPTIONS + "foo ", "value2" );
        final DefaultAuthenticationManager fresh = new DefaultAuthenticationManager();

        assertThrows( IllegalArgumentException.class, () -> fresh.initialize( e, props ) );
    }

    @Test
    void initializeWithSsoEnabledAndNoConfiguredBaseUrl_fallsBackToEngineBaseUrlAndTrimsTrailingSlash() throws Exception {
        // wikantik.baseURL absent -> must fall back to engine.getBaseURL() (logging a warning);
        // that fallback ends with "/" -> must be trimmed before the callback URL is built.
        final Engine e = MockEngineBuilder.engine().build();
        when( e.getBaseURL() ).thenReturn( "http://localhost:8080/" );
        final Properties props = new Properties();
        props.setProperty( SSOConfig.PROP_SSO_ENABLED, "true" );
        final DefaultAuthenticationManager fresh = new DefaultAuthenticationManager();

        assertDoesNotThrow( () -> fresh.initialize( e, props ) );
    }

    // ==================== doJAASLogin(): reflective instantiation failure ====================

    @Test
    void doJAASLoginWithUninstantiableLoginModuleThrowsWikiSecurityException() {
        // An interface cannot be instantiated by ClassUtil.buildInstance.
        assertThrows( WikiSecurityException.class,
                () -> mgr.doJAASLogin( LoginModule.class, callbacks -> { }, Map.of() ) );
    }

    // ==================== login(Session,...) throttling ====================

    @Test
    void loginThrottling_delaysRepeatedFailedAttemptsAndSurvivesInterruption() throws Exception {
        // throttleLogins defaults to true (field initializer) even without calling initialize().
        mgr.loginModuleClass = AlwaysFailLoginModule.class;
        final Session session = mock( Session.class );
        final String username = "throttle-test-user-" + System.nanoTime();

        // Attempt 1: no prior failures recorded -> delayLogin's count>0 branch is skipped.
        assertFalse( mgr.login( session, null, username, "wrong" ) );

        // Attempt 2: one prior failure recorded -> delayLogin sleeps briefly (2ms).
        assertFalse( mgr.login( session, null, username, "wrong" ) );

        // Attempt 3: interrupt the current thread first, so the throttle's Thread.sleep()
        // throws InterruptedException immediately; the login must still complete (interrupt
        // status restored, not swallowed) rather than aborting the whole attempt.
        Thread.currentThread().interrupt();
        try {
            assertFalse( mgr.login( session, null, username, "wrong" ) );
            assertTrue( Thread.currentThread().isInterrupted(), "interrupt status must be restored" );
        } finally {
            Thread.interrupted(); // clear, so it doesn't leak into later tests on a reused thread
        }
    }

    /** Always-failing {@link LoginModule} so throttling tests never touch the real engine/database. */
    public static final class AlwaysFailLoginModule implements LoginModule {
        @Override public void initialize( final Subject subject, final CallbackHandler callbackHandler,
                                           final Map< String, ? > sharedState, final Map< String, ? > options ) { }
        @Override public boolean login() { return false; }
        @Override public boolean commit() { return false; }
        @Override public boolean abort() { return false; }
        @Override public boolean logout() { return false; }
    }

}
