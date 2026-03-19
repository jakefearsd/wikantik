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
package com.wikantik.auth.sso;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpSession;
import com.wikantik.HttpMockFactory;
import com.wikantik.TestEngine;
import com.wikantik.auth.WikiPrincipal;
import com.wikantik.auth.login.WebContainerCallbackHandler;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;
import org.pac4j.core.profile.CommonProfile;
import org.pac4j.core.util.Pac4jConstants;

import javax.security.auth.Subject;
import javax.security.auth.callback.CallbackHandler;
import javax.security.auth.login.LoginException;
import javax.security.auth.spi.LoginModule;
import java.security.Principal;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;

class SSOLoginModuleTest {

    private Subject subject;
    private TestEngine engine;

    @BeforeEach
    void setUp() throws Exception {
        engine = new TestEngine( TestEngine.getTestProperties() );
        subject = new Subject();
    }

    @Test
    void testLoginWithValidProfile() throws LoginException {
        // Create a pac4j profile and put it in the session
        final CommonProfile profile = new CommonProfile();
        profile.setId( "jdoe" );
        profile.addAttribute( "preferred_username", "jdoe" );
        profile.addAttribute( "name", "John Doe" );
        profile.addAttribute( "email", "jdoe@example.com" );

        final HttpServletRequest request = createRequestWithSSOProfile( profile );
        final CallbackHandler handler = new WebContainerCallbackHandler( engine, request );

        final LoginModule module = new SSOLoginModule();
        module.initialize( subject, handler, new HashMap<>(), new HashMap<>() );

        Assertions.assertTrue( module.login() );
        Assertions.assertTrue( module.commit() );

        final Set< Principal > principals = subject.getPrincipals();
        Assertions.assertFalse( principals.isEmpty() );
        Assertions.assertTrue( principals.contains( new WikiPrincipal( "jdoe", WikiPrincipal.LOGIN_NAME ) ) );
    }

    @Test
    void testLoginWithNoProfile() throws LoginException {
        // Create a request with no pac4j profile in session
        final HttpServletRequest request = HttpMockFactory.createHttpRequest();

        final CallbackHandler handler = new WebContainerCallbackHandler( engine, request );

        final LoginModule module = new SSOLoginModule();
        module.initialize( subject, handler, new HashMap<>(), new HashMap<>() );

        // login() should throw FailedLoginException (caught by JAAS, returns false)
        Assertions.assertThrows( LoginException.class, module::login );
    }

    @Test
    void testLoginWithEmptyProfilesMap() throws LoginException {
        final HttpServletRequest request = HttpMockFactory.createHttpRequest();
        final HttpSession session = request.getSession();
        Mockito.doReturn( new LinkedHashMap<>() ).when( session ).getAttribute( Pac4jConstants.USER_PROFILES );

        final CallbackHandler handler = new WebContainerCallbackHandler( engine, request );

        final LoginModule module = new SSOLoginModule();
        module.initialize( subject, handler, new HashMap<>(), new HashMap<>() );

        Assertions.assertThrows( LoginException.class, module::login );
    }

    @Test
    void testLoginWithCustomClaimMapping() throws LoginException {
        final CommonProfile profile = new CommonProfile();
        profile.setId( "jdoe-id" );
        profile.addAttribute( "upn", "jdoe@corp.example.com" );

        final HttpServletRequest request = createRequestWithSSOProfile( profile );
        final CallbackHandler handler = new WebContainerCallbackHandler( engine, request );

        // Configure custom claim mapping via LoginModule options
        final Map< String, String > options = new HashMap<>();
        options.put( SSOLoginModule.OPTION_CLAIM_LOGIN_NAME, "upn" );

        final LoginModule module = new SSOLoginModule();
        module.initialize( subject, handler, new HashMap<>(), options );

        Assertions.assertTrue( module.login() );
        Assertions.assertTrue( module.commit() );

        final Set< Principal > principals = subject.getPrincipals();
        Assertions.assertTrue( principals.contains( new WikiPrincipal( "jdoe@corp.example.com", WikiPrincipal.LOGIN_NAME ) ) );
    }

    @Test
    void testLoginFallsBackToUsername() throws LoginException {
        // Profile with no preferred_username claim, but with a username set
        final CommonProfile profile = new CommonProfile();
        profile.setId( "jdoe-id" );
        profile.setLinkedId( "jdoe-linked" );

        final HttpServletRequest request = createRequestWithSSOProfile( profile );
        final CallbackHandler handler = new WebContainerCallbackHandler( engine, request );

        final LoginModule module = new SSOLoginModule();
        module.initialize( subject, handler, new HashMap<>(), new HashMap<>() );

        Assertions.assertTrue( module.login() );
        Assertions.assertTrue( module.commit() );

        // Should fall back to profile ID since there's no preferred_username or username
        final Set< Principal > principals = subject.getPrincipals();
        Assertions.assertFalse( principals.isEmpty() );
    }

    @Test
    void testLogout() throws LoginException {
        final CommonProfile profile = new CommonProfile();
        profile.setId( "jdoe" );
        profile.addAttribute( "preferred_username", "jdoe" );

        final HttpServletRequest request = createRequestWithSSOProfile( profile );
        final CallbackHandler handler = new WebContainerCallbackHandler( engine, request );

        final LoginModule module = new SSOLoginModule();
        module.initialize( subject, handler, new HashMap<>(), new HashMap<>() );

        module.login();
        module.commit();
        Assertions.assertFalse( subject.getPrincipals().isEmpty() );

        module.logout();
        Assertions.assertTrue( subject.getPrincipals().isEmpty() );
    }

    @Test
    void testExtractProfileFromLinkedHashMap() {
        final SSOLoginModule module = new SSOLoginModule();
        final HttpSession session = HttpMockFactory.createHttpSession();

        final CommonProfile profile = new CommonProfile();
        profile.setId( "test-user" );
        profile.addAttribute( "preferred_username", "testuser" );

        final LinkedHashMap< String, org.pac4j.core.profile.UserProfile > profiles = new LinkedHashMap<>();
        profiles.put( "OidcClient#test-user", profile );
        Mockito.doReturn( profiles ).when( session ).getAttribute( Pac4jConstants.USER_PROFILES );

        final org.pac4j.core.profile.UserProfile extracted = module.extractProfile( session );
        Assertions.assertNotNull( extracted );
        Assertions.assertEquals( "test-user", extracted.getId() );
    }

    @Test
    void testExtractProfileReturnsNullForNoProfiles() {
        final SSOLoginModule module = new SSOLoginModule();
        final HttpSession session = HttpMockFactory.createHttpSession();

        // No USER_PROFILES attribute set
        Mockito.doReturn( null ).when( session ).getAttribute( Pac4jConstants.USER_PROFILES );

        Assertions.assertNull( module.extractProfile( session ) );
    }

    @Test
    void testResolveLoginNameFromConfiguredClaim() {
        final SSOLoginModule module = new SSOLoginModule();
        // Initialize with options containing the custom claim
        module.initialize( new Subject(), Mockito.mock( CallbackHandler.class ), new HashMap<>(),
                Map.of( SSOLoginModule.OPTION_CLAIM_LOGIN_NAME, "email" ) );

        final CommonProfile profile = new CommonProfile();
        profile.addAttribute( "email", "user@example.com" );
        profile.addAttribute( "preferred_username", "someuser" );

        Assertions.assertEquals( "user@example.com", module.resolveLoginName( profile ) );
    }

    @Test
    void testResolveLoginNameFallbackToUsername() {
        final SSOLoginModule module = new SSOLoginModule();
        module.initialize( new Subject(), Mockito.mock( CallbackHandler.class ), new HashMap<>(), new HashMap<>() );

        final CommonProfile profile = new CommonProfile();
        profile.setId( "profile-id" );
        // No preferred_username attribute, but pac4j will use the id as username if not set explicitly

        final String loginName = module.resolveLoginName( profile );
        Assertions.assertNotNull( loginName );
        Assertions.assertFalse( loginName.isBlank() );
    }

    /**
     * Helper to create a mock request with a pac4j profile stored in the session.
     */
    private HttpServletRequest createRequestWithSSOProfile( final CommonProfile profile ) {
        final HttpServletRequest request = HttpMockFactory.createHttpRequest();
        final HttpSession session = request.getSession();

        final LinkedHashMap< String, org.pac4j.core.profile.UserProfile > profiles = new LinkedHashMap<>();
        profiles.put( profile.getClientName() + "#" + profile.getId(), profile );
        Mockito.doReturn( profiles ).when( session ).getAttribute( Pac4jConstants.USER_PROFILES );

        return request;
    }
}
