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

import com.wikantik.HttpMockFactory;
import com.wikantik.TestEngine;
import com.wikantik.auth.UserManager;
import com.wikantik.auth.WikiPrincipal;
import com.wikantik.auth.login.WebContainerCallbackHandler;
import com.wikantik.auth.user.UserDatabase;
import com.wikantik.auth.user.UserProfile;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpSession;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.mockito.Mockito;
import org.pac4j.core.profile.CommonProfile;
import org.pac4j.core.util.Pac4jConstants;

import javax.security.auth.Subject;
import javax.security.auth.callback.CallbackHandler;
import javax.security.auth.login.LoginException;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

class SSOLoginModuleEdgeCasesTest {

    private SSOLoginModule moduleWithClaim( final String loginClaim ) {
        final SSOLoginModule module = new SSOLoginModule();
        module.initialize( new Subject(), Mockito.mock( CallbackHandler.class ), new HashMap<>(),
                Map.of( SSOLoginModule.OPTION_CLAIM_LOGIN_NAME, loginClaim ) );
        return module;
    }

    private HttpServletRequest requestWithProfile( final CommonProfile profile ) {
        final HttpServletRequest request = HttpMockFactory.createHttpRequest();
        final HttpSession session = request.getSession();
        final LinkedHashMap< String, org.pac4j.core.profile.UserProfile > profiles = new LinkedHashMap<>();
        profiles.put( "OidcClient#" + profile.getId(), profile );
        Mockito.doReturn( profiles ).when( session ).getAttribute( Pac4jConstants.USER_PROFILES );
        return request;
    }

    @ParameterizedTest
    @ValueSource( strings = { "   ", "bad\tname", "name\nwith-newline", "a b" } )
    void rejectsUnsafeLoginNames( final String hostile ) throws Exception {
        final TestEngine engine = new TestEngine( TestEngine.getTestProperties() );
        final CommonProfile profile = new CommonProfile();
        profile.setId( "id-1" );
        profile.addAttribute( "sub", hostile );

        final SSOLoginModule module = new SSOLoginModule();
        module.initialize( new Subject(), new WebContainerCallbackHandler( engine, requestWithProfile( profile ) ),
                new HashMap<>(), Map.of( SSOLoginModule.OPTION_CLAIM_LOGIN_NAME, "sub" ) );

        Assertions.assertThrows( LoginException.class, module::login,
            "Whitespace/control-character login names must be rejected, not bound to a principal." );
    }

    @Test
    void resolvesFirstElementOfMultiValuedClaim() {
        final CommonProfile profile = new CommonProfile();
        profile.addAttribute( "sub", List.of( "alice", "alice-alt" ) );

        Assertions.assertEquals( "alice", moduleWithClaim( "sub" ).resolveLoginName( profile ),
            "A list-valued claim must resolve to its first element, not its toString()." );
    }

    @Test
    void firstScalarUnwrapsCollections() {
        Assertions.assertEquals( "a@b.com", SSOLoginModule.firstScalar( List.of( "a@b.com" ) ) );
        Assertions.assertEquals( "scalar", SSOLoginModule.firstScalar( "scalar" ) );
        Assertions.assertNull( SSOLoginModule.firstScalar( List.of() ) );
        Assertions.assertNull( SSOLoginModule.firstScalar( null ) );
    }

    @Test
    void isSafeLoginNameLengthBoundary() {
        Assertions.assertTrue(  SSOLoginModule.isSafeLoginName( "jdoe" ),           "normal name must be safe" );
        Assertions.assertFalse( SSOLoginModule.isSafeLoginName( null ),             "null must be unsafe" );
        Assertions.assertTrue(  SSOLoginModule.isSafeLoginName( "a".repeat( 100 ) ), "100-char name must be safe (at the limit)" );
        Assertions.assertFalse( SSOLoginModule.isSafeLoginName( "a".repeat( 101 ) ), "101-char name must be unsafe (over the limit)" );
    }

    @Test
    void refusesToBindToPreexistingNonSsoAccount() throws Exception {
        final TestEngine engine = new TestEngine( TestEngine.getTestProperties() );
        final UserDatabase userDb = engine.getManager( UserManager.class ).getUserDatabase();

        // A locally-created admin account that was NEVER linked to SSO.
        final UserProfile local = userDb.newProfile();
        local.setLoginName( "admin" );
        local.setFullname( "Local Admin" );
        userDb.save( local );
        try {
            // A hostile IdP asserts identity "admin".
            final CommonProfile profile = new CommonProfile();
            profile.setId( "admin" );
            profile.addAttribute( "sub", "admin" );

            final SSOLoginModule module = new SSOLoginModule();
            final Subject subject = new Subject();
            module.initialize( subject, new WebContainerCallbackHandler( engine, requestWithProfile( profile ) ),
                    new HashMap<>(), Map.of( SSOLoginModule.OPTION_CLAIM_LOGIN_NAME, "sub" ) );

            Assertions.assertThrows( LoginException.class, module::login,
                "SSO must not adopt a pre-existing local account that has no matching sso.subject." );
            Assertions.assertTrue( subject.getPrincipals( WikiPrincipal.class ).isEmpty(),
                "No principal may be bound when the collision check fails." );
        } finally {
            userDb.deleteByLoginName( "admin" );
        }
    }
}
