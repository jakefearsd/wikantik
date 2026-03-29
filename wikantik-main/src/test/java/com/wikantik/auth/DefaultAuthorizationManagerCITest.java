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
import com.wikantik.api.core.Acl;
import com.wikantik.api.core.AclEntry;
import com.wikantik.api.core.Engine;
import com.wikantik.api.core.Page;
import com.wikantik.api.core.Session;
import com.wikantik.auth.acl.AclManager;
import com.wikantik.auth.acl.UnresolvedPrincipal;
import com.wikantik.auth.authorize.GroupManager;
import com.wikantik.auth.authorize.Role;
import com.wikantik.auth.permissions.PagePermission;
import com.wikantik.auth.user.UserDatabase;
import com.wikantik.auth.user.UserProfile;
import com.wikantik.pages.PageManager;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import javax.security.auth.Subject;
import java.security.Principal;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;


/**
 * Unit tests for {@link DefaultAuthorizationManager} using constructor injection.
 * Validates the refactored manager fields (PageManager, AclManager, GroupManager,
 * UserManager) without spinning up a full TestEngine.
 */
class DefaultAuthorizationManagerCITest {

    private Engine engine;
    private PageManager pageManager;
    private AclManager aclManager;
    private GroupManager groupManager;
    private UserManager userManager;
    private UserDatabase userDatabase;

    private DefaultAuthorizationManager mgr;

    @BeforeEach
    void setUp() {
        pageManager = mock( PageManager.class );
        aclManager = mock( AclManager.class );
        groupManager = mock( GroupManager.class );
        userManager = mock( UserManager.class );
        userDatabase = mock( UserDatabase.class );
        when( userManager.getUserDatabase() ).thenReturn( userDatabase );

        engine = MockEngineBuilder.engine()
                .with( PageManager.class, pageManager )
                .with( AclManager.class, aclManager )
                .with( GroupManager.class, groupManager )
                .with( UserManager.class, userManager )
                .build();

        mgr = new DefaultAuthorizationManager( engine, pageManager, aclManager, groupManager, userManager );
    }

    // ==================== checkPermission — PagePermission with ACL ====================

    @Test
    void checkPermissionAllowsWhenPageHasNoAcl() {
        // Setup: page exists but has no ACL
        final Page page = mock( Page.class );
        when( page.getName() ).thenReturn( "NoAclPage" );
        when( pageManager.getPage( "NoAclPage" ) ).thenReturn( page );
        when( aclManager.getPermissions( page ) ).thenReturn( null );

        final Session session = mockSession( true, new WikiPrincipal( "alice" ) );
        // checkStaticPermission needs to return true for the policy check
        // Use a spy so we can stub checkStaticPermission
        final DefaultAuthorizationManager spy = spy( mgr );
        doReturn( false ).when( spy ).checkStaticPermission( any(), any() ); // no AllPermission
        // Need to return true for the page permission policy check
        doReturn( false ).doReturn( true ).when( spy ).checkStaticPermission( any(), any() );

        final PagePermission perm = new PagePermission( "test:NoAclPage", "view" );
        assertTrue( spy.checkPermission( session, perm ) );
    }

    @Test
    void checkPermissionDeniesWhenPageHasAclNotMatchingUser() {
        // Setup: page has ACL that doesn't include the user
        final Page page = mock( Page.class );
        when( page.getName() ).thenReturn( "AclPage" );
        when( pageManager.getPage( "AclPage" ) ).thenReturn( page );

        final Acl acl = mock( Acl.class );
        when( acl.isEmpty() ).thenReturn( false );
        when( aclManager.getPermissions( page ) ).thenReturn( acl );

        final PagePermission perm = new PagePermission( "test:AclPage", "view" );
        // ACL contains only "bob" principal
        final Principal bob = new WikiPrincipal( "bob" );
        when( acl.findPrincipals( perm ) ).thenReturn( new Principal[]{ bob } );

        // Session is "alice" (authenticated)
        final Session session = mockSession( true, new WikiPrincipal( "alice" ) );

        final DefaultAuthorizationManager spy = spy( mgr );
        doReturn( false ).doReturn( true ).when( spy ).checkStaticPermission( any(), any() );

        assertFalse( spy.checkPermission( session, perm ) );
    }

    @Test
    void checkPermissionAllowsWhenAclMatchesUser() {
        // Setup: page has ACL that includes the user
        final Page page = mock( Page.class );
        when( page.getName() ).thenReturn( "AclPage" );
        when( pageManager.getPage( "AclPage" ) ).thenReturn( page );

        final Acl acl = mock( Acl.class );
        when( acl.isEmpty() ).thenReturn( false );
        when( aclManager.getPermissions( page ) ).thenReturn( acl );

        final PagePermission perm = new PagePermission( "test:AclPage", "view" );
        // ACL contains the authenticated role
        final Principal authRole = Role.AUTHENTICATED;
        when( acl.findPrincipals( perm ) ).thenReturn( new Principal[]{ authRole } );

        // Session has AUTHENTICATED role — must stub hasPrincipal for isUserInRole
        final Session session = mockSession( true, new WikiPrincipal( "alice" ), Role.AUTHENTICATED );
        when( session.hasPrincipal( Role.AUTHENTICATED ) ).thenReturn( true );

        final DefaultAuthorizationManager spy = spy( mgr );
        doReturn( false ).doReturn( true ).when( spy ).checkStaticPermission( any(), any() );

        assertTrue( spy.checkPermission( session, perm ) );
    }

    @Test
    void checkPermissionDeniesNullSession() {
        final PagePermission perm = new PagePermission( "test:SomePage", "view" );
        assertFalse( mgr.checkPermission( null, perm ) );
    }

    @Test
    void checkPermissionDeniesNullPermission() {
        final Session session = mockSession( false, new WikiPrincipal( "anon" ) );
        assertFalse( mgr.checkPermission( session, null ) );
    }

    @Test
    void checkPermissionAllowsWhenPageDoesNotExist() {
        // page not found => allowed (no ACL to restrict)
        when( pageManager.getPage( "MissingPage" ) ).thenReturn( null );

        final Session session = mockSession( true, new WikiPrincipal( "alice" ) );
        final DefaultAuthorizationManager spy = spy( mgr );
        doReturn( false ).doReturn( true ).when( spy ).checkStaticPermission( any(), any() );

        final PagePermission perm = new PagePermission( "test:MissingPage", "view" );
        assertTrue( spy.checkPermission( session, perm ) );
    }

    // ==================== checkPermission — ACL with UnresolvedPrincipal ====================

    @Test
    void checkPermissionResolvesUnresolvedPrincipalInAcl() {
        final Page page = mock( Page.class );
        when( page.getName() ).thenReturn( "AclPage" );
        when( pageManager.getPage( "AclPage" ) ).thenReturn( page );

        final Acl acl = mock( Acl.class );
        when( acl.isEmpty() ).thenReturn( false );
        when( aclManager.getPermissions( page ) ).thenReturn( acl );

        final PagePermission perm = new PagePermission( "test:AclPage", "view" );

        // ACL contains an unresolved principal that will resolve to AUTHENTICATED role
        final UnresolvedPrincipal unresolved = new UnresolvedPrincipal( "Authenticated" );
        when( acl.findPrincipals( perm ) ).thenReturn( new Principal[]{ unresolved } );

        final AclEntry aclEntry = mock( AclEntry.class );
        when( acl.getAclEntry( unresolved ) ).thenReturn( aclEntry );

        // Session has AUTHENTICATED role — must stub hasPrincipal for isUserInRole
        final Session session = mockSession( true, new WikiPrincipal( "alice" ), Role.AUTHENTICATED );
        when( session.hasPrincipal( Role.AUTHENTICATED ) ).thenReturn( true );

        final DefaultAuthorizationManager spy = spy( mgr );
        doReturn( false ).doReturn( true ).when( spy ).checkStaticPermission( any(), any() );

        assertTrue( spy.checkPermission( session, perm ) );
        // The AclEntry should have been updated with the resolved principal
        verify( aclEntry ).setPrincipal( Role.AUTHENTICATED );
    }

    // ==================== resolvePrincipal ====================

    @Test
    void resolvePrincipalReturnsBuiltInRole() {
        // "Authenticated" is a built-in role and should be returned without consulting managers
        final Principal result = mgr.resolvePrincipal( "Authenticated" );
        assertNotNull( result );
        assertTrue( result instanceof Role );
        assertEquals( "Authenticated", result.getName() );
    }

    @Test
    void resolvePrincipalDelegatesToGroupManager() {
        // authorizer must be set for resolvePrincipal to pass the authorizer check
        setAuthorizer( mgr );

        final Principal groupPrincipal = new GroupPrincipal( "TestGroup" );
        when( groupManager.findRole( "TestGroup" ) ).thenReturn( groupPrincipal );

        final Principal result = mgr.resolvePrincipal( "TestGroup" );
        assertEquals( groupPrincipal, result );
    }

    @Test
    void resolvePrincipalDelegatesToUserDatabase() throws Exception {
        setAuthorizer( mgr );
        when( groupManager.findRole( "alice" ) ).thenReturn( null );

        final UserProfile profile = mock( UserProfile.class );
        when( profile.getLoginName() ).thenReturn( "alice" );
        when( userDatabase.find( "alice" ) ).thenReturn( profile );

        final Principal userPrincipal = new WikiPrincipal( "alice" );
        when( userDatabase.getPrincipals( "alice" ) ).thenReturn( new Principal[]{ userPrincipal } );

        final Principal result = mgr.resolvePrincipal( "alice" );
        assertEquals( "alice", result.getName() );
    }

    @Test
    void resolvePrincipalReturnsUnresolvedWhenNotFound() throws Exception {
        setAuthorizer( mgr );
        when( groupManager.findRole( "unknown" ) ).thenReturn( null );
        when( userDatabase.find( "unknown" ) ).thenThrow( new NoSuchPrincipalException( "not found" ) );

        final Principal result = mgr.resolvePrincipal( "unknown" );
        assertTrue( result instanceof UnresolvedPrincipal );
        assertEquals( "unknown", result.getName() );
    }

    // ==================== hasRoleOrPrincipal ====================

    @Test
    void hasRoleOrPrincipalReturnsFalseForNullSession() {
        assertFalse( mgr.hasRoleOrPrincipal( null, new WikiPrincipal( "alice" ) ) );
    }

    @Test
    void hasRoleOrPrincipalReturnsFalseForNullPrincipal() {
        final Session session = mockSession( true, new WikiPrincipal( "alice" ) );
        assertFalse( mgr.hasRoleOrPrincipal( session, null ) );
    }

    @Test
    void hasRoleOrPrincipalReturnsTrueForMatchingUserPrincipal() {
        final WikiPrincipal alice = new WikiPrincipal( "alice" );
        final Session session = mockSession( true, alice );
        assertTrue( mgr.hasRoleOrPrincipal( session, alice ) );
    }

    @Test
    void hasRoleOrPrincipalReturnsFalseForNonMatchingUserPrincipal() {
        final WikiPrincipal alice = new WikiPrincipal( "alice" );
        final WikiPrincipal bob = new WikiPrincipal( "bob" );
        final Session session = mockSession( true, alice );
        assertFalse( mgr.hasRoleOrPrincipal( session, bob ) );
    }

    // ==================== Helpers ====================

    /**
     * Creates a mock Session with the given authentication state and principals.
     * The first principal is used as the login principal.
     */
    private Session mockSession( final boolean authenticated, final Principal loginPrincipal, final Principal... extraPrincipals ) {
        final Session session = mock( Session.class );
        when( session.isAuthenticated() ).thenReturn( authenticated );
        when( session.getLoginPrincipal() ).thenReturn( loginPrincipal );

        final Principal[] allPrincipals = new Principal[ 1 + extraPrincipals.length ];
        allPrincipals[ 0 ] = loginPrincipal;
        System.arraycopy( extraPrincipals, 0, allPrincipals, 1, extraPrincipals.length );

        when( session.getPrincipals() ).thenReturn( allPrincipals );
        when( session.getRoles() ).thenReturn( extraPrincipals );
        when( session.getSubject() ).thenReturn( new Subject() );
        return session;
    }

    /**
     * Sets a no-op Authorizer on the manager so that resolvePrincipal can proceed
     * past the authorizer role check.
     */
    private void setAuthorizer( final DefaultAuthorizationManager manager ) {
        try {
            final java.lang.reflect.Field f = DefaultAuthorizationManager.class.getDeclaredField( "authorizer" );
            f.setAccessible( true );
            f.set( manager, mock( Authorizer.class ) );
        } catch ( final Exception e ) {
            throw new RuntimeException( e );
        }
    }

}
