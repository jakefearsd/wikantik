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
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.mockito.Mockito;
import org.pac4j.core.profile.CommonProfile;
import org.pac4j.core.util.Pac4jConstants;

import javax.security.auth.Subject;
import javax.security.auth.callback.CallbackHandler;
import javax.security.auth.login.FailedLoginException;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * How an SSO assertion binds to a local account: only a fresh name or one already linked to the same immutable
 * subject, never a local account of the same name, and never a locked one.
 */
class SSOLoginModuleBindingTest {

    private static HttpServletRequest requestWith( final Object profilesAttribute ) {
        final HttpServletRequest request = HttpMockFactory.createHttpRequest();
        final HttpSession session = request.getSession();
        Mockito.doReturn( profilesAttribute ).when( session ).getAttribute( Pac4jConstants.USER_PROFILES );
        return request;
    }

    private static HttpServletRequest requestWithProfile( final CommonProfile profile ) {
        final LinkedHashMap< String, org.pac4j.core.profile.UserProfile > profiles = new LinkedHashMap<>();
        profiles.put( "OidcClient#" + profile.getId(), profile );
        return requestWith( profiles );
    }

    private static CommonProfile assertion( final String loginName, final String sub ) {
        final CommonProfile profile = new CommonProfile();
        profile.setId( sub == null ? loginName : sub );
        profile.addAttribute( "preferred_username", loginName );
        if( sub != null ) {
            profile.addAttribute( "sub", sub );
        }
        return profile;
    }

    private static SSOLoginModule module( final TestEngine engine, final Subject subject, final CommonProfile profile,
                                          final Map< String, ? > options ) {
        final SSOLoginModule module = new SSOLoginModule();
        module.initialize( subject, new WebContainerCallbackHandler( engine, requestWithProfile( profile ) ),
                new HashMap<>(), options );
        return module;
    }

    private static UserProfile localAccount( final UserDatabase db, final String login, final String linkedSubject )
            throws Exception {
        final UserProfile local = db.newProfile();
        local.setLoginName( login );
        local.setFullname( "Local " + login );
        if( linkedSubject != null ) {
            local.getAttributes().put( SSOAutoProvisionService.ATTR_SSO_SUBJECT, linkedSubject );
        }
        db.save( local );
        return local;
    }

    @Test
    void anAssertionForAnAccountLinkedToTheSameSubjectLogsInAndBindsTheLoginPrincipal() throws Exception {
        final TestEngine engine = new TestEngine( TestEngine.getTestProperties() );
        final UserDatabase db = engine.getManager( UserManager.class ).getUserDatabase();
        localAccount( db, "bind-same-subject", "subject-1" );
        try {
            final Subject subject = new Subject();
            final SSOLoginModule m = module( engine, subject, assertion( "bind-same-subject", "subject-1" ), new HashMap<>() );

            assertTrue( m.login() );
            assertTrue( m.commit() );

            final Set< WikiPrincipal > bound = subject.getPrincipals( WikiPrincipal.class );
            assertEquals( 1, bound.size() );
            final WikiPrincipal p = bound.iterator().next();
            assertEquals( "bind-same-subject", p.getName() );
            assertEquals( WikiPrincipal.LOGIN_NAME, p.getType() );
        } finally {
            db.deleteByLoginName( "bind-same-subject" );
        }
    }

    @Test
    void anAssertionForAnAccountLinkedToADifferentSubjectIsRefused() throws Exception {
        final TestEngine engine = new TestEngine( TestEngine.getTestProperties() );
        final UserDatabase db = engine.getManager( UserManager.class ).getUserDatabase();
        localAccount( db, "bind-other-subject", "subject-owner" );
        try {
            final Subject subject = new Subject();
            final SSOLoginModule m = module( engine, subject, assertion( "bind-other-subject", "subject-attacker" ),
                    new HashMap<>() );

            assertThrows( FailedLoginException.class, m::login );
            assertTrue( subject.getPrincipals( WikiPrincipal.class ).isEmpty() );
        } finally {
            db.deleteByLoginName( "bind-other-subject" );
        }
    }

    @Test
    void anAssertionWithNoSubjectClaimCannotTakeOverAnSsoLinkedAccount() throws Exception {
        final TestEngine engine = new TestEngine( TestEngine.getTestProperties() );
        final UserDatabase db = engine.getManager( UserManager.class ).getUserDatabase();
        localAccount( db, "bind-no-sub", "subject-owner" );
        try {
            final SSOLoginModule m = module( engine, new Subject(), assertion( "bind-no-sub", null ), new HashMap<>() );

            assertThrows( FailedLoginException.class, m::login );
        } finally {
            db.deleteByLoginName( "bind-no-sub" );
        }
    }

    @Test
    void aSubjectClaimNamedByTheIdentityClaimOptionIsWhatTheLinkIsComparedWith() throws Exception {
        final TestEngine engine = new TestEngine( TestEngine.getTestProperties() );
        final UserDatabase db = engine.getManager( UserManager.class ).getUserDatabase();
        localAccount( db, "bind-oid-claim", "oid-123" );
        try {
            final CommonProfile profile = assertion( "bind-oid-claim", "some-other-sub" );
            profile.addAttribute( "oid", "oid-123" );
            final Map< String, String > options = Map.of( SSOLoginModule.OPTION_IDENTITY_CLAIM, "oid" );

            assertTrue( module( engine, new Subject(), profile, options ).login(),
                    "the configured identity claim, not 'sub', identifies the account" );
        } finally {
            db.deleteByLoginName( "bind-oid-claim" );
        }
    }

    @Test
    void aFreshNameWithNoLocalAccountLogsInOnTheAssertionAlone() throws Exception {
        final TestEngine engine = new TestEngine( TestEngine.getTestProperties() );
        final Subject subject = new Subject();

        assertTrue( module( engine, subject, assertion( "bind-brand-new", "subject-new" ), new HashMap<>() ).login() );
    }

    @Test
    void aLoginWithoutAnEngineStillBindsThePrincipal() throws Exception {
        final CallbackHandler handler = callbacks -> {
            for( final javax.security.auth.callback.Callback cb : callbacks ) {
                if( cb instanceof com.wikantik.auth.login.HttpRequestCallback rc ) {
                    rc.setRequest( requestWithProfile( assertion( "no-engine-user", "s" ) ) );
                }
            }
        };
        final SSOLoginModule m = new SSOLoginModule();
        final Subject subject = new Subject();
        m.initialize( subject, handler, new HashMap<>(), new HashMap<>() );

        assertTrue( m.login() );
        assertTrue( m.commit() );
        assertEquals( "no-engine-user", subject.getPrincipals( WikiPrincipal.class ).iterator().next().getName() );
    }

    // ---- request / session / profile presence -----------------------------------------------------------------------

    @Test
    void aRequestWithNoHttpSessionIsRefused() throws Exception {
        final HttpServletRequest request = HttpMockFactory.createHttpRequest();
        Mockito.doReturn( null ).when( request ).getSession( false );
        final SSOLoginModule m = new SSOLoginModule();
        final TestEngine engine = new TestEngine( TestEngine.getTestProperties() );
        m.initialize( new Subject(), new WebContainerCallbackHandler( engine, request ), new HashMap<>(), new HashMap<>() );

        final FailedLoginException e = assertThrows( FailedLoginException.class, m::login );
        assertEquals( "No HTTP session available.", e.getMessage() );
    }

    @Test
    void extractProfileReadsAPlainMapAsWellAsALinkedHashMap() {
        final CommonProfile profile = assertion( "alice", "s" );
        final HttpSession session = Mockito.mock( HttpSession.class );
        Mockito.doReturn( Map.of( "k", profile ) ).when( session ).getAttribute( Pac4jConstants.USER_PROFILES );

        assertSame( profile, new SSOLoginModule().extractProfile( session ) );

        Mockito.doReturn( Map.of() ).when( session ).getAttribute( Pac4jConstants.USER_PROFILES );
        assertNull( new SSOLoginModule().extractProfile( session ) );
        Mockito.doReturn( "not a map" ).when( session ).getAttribute( Pac4jConstants.USER_PROFILES );
        assertNull( new SSOLoginModule().extractProfile( session ) );
    }

    @Test
    void theLoginNameFallsBackFromTheClaimToTheUsernameThenTheProfileId() {
        final SSOLoginModule m = new SSOLoginModule();
        m.initialize( new Subject(), Mockito.mock( CallbackHandler.class ), new HashMap<>(), new HashMap<>() );

        final CommonProfile withClaim = new CommonProfile();
        withClaim.setId( "the-id" );
        withClaim.addAttribute( "preferred_username", "claimed" );
        assertEquals( "claimed", m.resolveLoginName( withClaim ) );

        final CommonProfile withUsername = new CommonProfile();
        withUsername.setId( "the-id" );
        withUsername.addAttribute( Pac4jConstants.USERNAME, "uname" );
        assertEquals( "uname", m.resolveLoginName( withUsername ) );

        final CommonProfile blankUsername = new CommonProfile();
        blankUsername.setId( "the-id" );
        blankUsername.addAttribute( Pac4jConstants.USERNAME, "   " );
        assertEquals( "the-id", m.resolveLoginName( blankUsername ), "a blank username does not count" );
    }

    @Test
    void aBlankClaimValueIsReturnedAsIsSoTheSafetyCheckRejectsItInsteadOfFallingBack() {
        final SSOLoginModule m = new SSOLoginModule();
        m.initialize( new Subject(), Mockito.mock( CallbackHandler.class ), new HashMap<>(), new HashMap<>() );
        final CommonProfile profile = new CommonProfile();
        profile.setId( "the-id" );
        profile.addAttribute( "preferred_username", " " );
        profile.addAttribute( Pac4jConstants.USERNAME, "uname" );

        assertEquals( " ", m.resolveLoginName( profile ) );
    }

    @ParameterizedTest
    @ValueSource( strings = { "a\u0007b", "tab\there", "nl\nx", "sp ace" } )
    void loginNamesWithControlOrWhitespaceCharactersAreUnsafe( final String name ) {
        assertFalse( SSOLoginModule.isSafeLoginName( name ) );
    }

    @Test
    void anOrdinaryNameIsSafeAndABlankOneIsNot() {
        assertTrue( SSOLoginModule.isSafeLoginName( "j.doe-1_x@corp" ) );
        assertFalse( SSOLoginModule.isSafeLoginName( "" ) );
        assertFalse( SSOLoginModule.isSafeLoginName( "   " ) );
    }
}
