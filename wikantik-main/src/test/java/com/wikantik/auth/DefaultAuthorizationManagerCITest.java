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
import com.wikantik.WikiEngine;
import com.wikantik.api.core.Acl;
import com.wikantik.api.core.AclEntry;
import com.wikantik.api.core.Context;
import com.wikantik.api.core.Engine;
import com.wikantik.api.core.Page;
import com.wikantik.api.core.Session;
import com.wikantik.api.exceptions.NoRequiredPropertyException;
import com.wikantik.api.exceptions.WikiException;
import com.wikantik.auth.acl.AclManager;
import com.wikantik.auth.acl.UnresolvedPrincipal;
import com.wikantik.auth.authorize.GroupManager;
import com.wikantik.auth.authorize.Role;
import com.wikantik.auth.permissions.AllPermission;
import com.wikantik.auth.permissions.PagePermission;
import com.wikantik.auth.user.UserDatabase;
import com.wikantik.auth.user.UserProfile;
import com.wikantik.api.managers.PageManager;
import com.wikantik.api.providers.PageProvider;
import com.wikantik.i18n.InternationalizationManager;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import jakarta.servlet.http.HttpSession;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import javax.security.auth.Subject;
import java.lang.reflect.Field;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.security.Principal;
import java.util.Properties;

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

    /**
     * Regression guard from the 2026-09-25 profiling campaign: an authorization decision
     * must NOT force a markdown parse.
     *
     * <p>{@code decide()} loaded the page with {@link PageManager#getPage(String)}, which
     * routes through {@code CachingProvider.refreshMetadata} and runs a full flexmark parse
     * (plus a second one inside {@code collectLinks}) to populate {@code [{SET}]} page
     * variables. Nothing on this path reads them: the page feeds
     * {@code aclManager().getPermissions(page)} and {@code decideByAcl}, and inline
     * {@code [{ALLOW ...}]} ACLs are resolved by {@code DefaultAclManager} from the raw page
     * text behind its own version-keyed cache. {@code filterViewable} in this same class
     * already used the metadata-free accessor; {@code decide} was the un-migrated sibling.</p>
     *
     * <p>JFR on a production-shaped host attributed 13.65% of ALL CPU to
     * {@code refreshMetadata}, and the full caller chains showed <b>~90% of it funnelling
     * through {@code decide}</b> — 74% via {@code checkPermission}, 12% via
     * {@code isPermitted}, and 3% via {@code DefaultLuceneSearcher.findPages}, which had
     * already been migrated to the metadata-free accessor only to have {@code decide} undo
     * it on the next line.</p>
     *
     * <p>This guard lives HERE, not on {@code PermissionFilter}: an earlier version asserted
     * only that {@code PermissionFilter}'s own call site had changed, which passed while the
     * system carried on parsing on every request. Assert at the layer that actually loads
     * the page.</p>
     */
    @Test
    void pagePermissionDecisionMustNotForceMetadataParse() {
        final Page page = mock( Page.class );
        when( page.getName() ).thenReturn( "GuardedPage" );
        when( pageManager.getPageWithoutMetadata( "GuardedPage", PageProvider.LATEST_VERSION ) )
                .thenReturn( page );
        when( aclManager.getPermissions( page ) ).thenReturn( null );

        final Session session = mockSession( true, new WikiPrincipal( "alice" ) );
        final DefaultAuthorizationManager spy = spy( mgr );
        doReturn( false ).doReturn( true ).when( spy ).checkStaticPermission( any(), any() );

        assertTrue( spy.checkPermission( session, new PagePermission( "test:GuardedPage", "view" ) ) );

        verify( pageManager ).getPageWithoutMetadata( "GuardedPage", PageProvider.LATEST_VERSION );
        verify( pageManager, never() ).getPage( anyString() );
        verify( pageManager, never() ).getPage( anyString(), anyInt() );
    }

    // ==================== checkPermission — PagePermission with ACL ====================

    @Test
    void checkPermissionAllowsWhenPageHasNoAcl() {
        // Setup: page exists but has no ACL
        final Page page = mock( Page.class );
        when( page.getName() ).thenReturn( "NoAclPage" );
        when( pageManager.getPageWithoutMetadata( "NoAclPage", PageProvider.LATEST_VERSION ) ).thenReturn( page );
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
        when( pageManager.getPageWithoutMetadata( "AclPage", PageProvider.LATEST_VERSION ) ).thenReturn( page );

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
        when( pageManager.getPageWithoutMetadata( "AclPage", PageProvider.LATEST_VERSION ) ).thenReturn( page );

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
        when( pageManager.getPageWithoutMetadata( "MissingPage", PageProvider.LATEST_VERSION ) ).thenReturn( null );

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
        when( pageManager.getPageWithoutMetadata( "AclPage", PageProvider.LATEST_VERSION ) ).thenReturn( page );

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
        when( userDatabase.findByLoginName( "alice" ) ).thenReturn( profile );

        final Principal userPrincipal = new WikiPrincipal( "alice" );
        when( userDatabase.getPrincipals( "alice" ) ).thenReturn( new Principal[]{ userPrincipal } );

        final Principal result = mgr.resolvePrincipal( "alice" );
        assertEquals( "alice", result.getName() );
    }

    @Test
    void resolvePrincipalFallsBackToFullOrWikiNameWhenNotALogin() throws Exception {
        setAuthorizer( mgr );
        when( groupManager.findRole( "Alice Smith" ) ).thenReturn( null );
        when( userDatabase.findByLoginName( "Alice Smith" ) ).thenThrow( new NoSuchPrincipalException( "no login" ) );
        final UserProfile profile = mock( UserProfile.class );
        when( profile.getLoginName() ).thenReturn( "alice" );
        when( userDatabase.find( "Alice Smith" ) ).thenReturn( profile );
        final Principal full = new WikiPrincipal( "Alice Smith", WikiPrincipal.FULL_NAME );
        when( userDatabase.getPrincipals( "alice" ) ).thenReturn( new Principal[]{ new WikiPrincipal( "alice", WikiPrincipal.LOGIN_NAME ), full } );

        assertEquals( full, mgr.resolvePrincipal( "Alice Smith" ) );
    }

    @Test
    void resolvePrincipalReturnsUnresolvedWhenNotFound() throws Exception {
        setAuthorizer( mgr );
        when( groupManager.findRole( "unknown" ) ).thenReturn( null );
        when( userDatabase.findByLoginName( "unknown" ) ).thenThrow( new NoSuchPrincipalException( "not found" ) );
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

    // ==================== Bootstrap admin override expiry ====================

    @Test
    void bootstrapAdminAllowsPermissionBeforeExpiry() {
        final long[] now = { 1_000_000L };
        mgr.setClock( () -> now[ 0 ] );
        mgr.configureBootstrap( "alice", 60L );

        final Session session = mockSession( true, new WikiPrincipal( "alice" ) );
        final PagePermission perm = new PagePermission( "test:SomePage", "view" );

        assertTrue( mgr.checkPermission( session, perm ) );
    }

    @Test
    void bootstrapAdminDeniesPermissionAfterExpiry() {
        final long[] now = { 1_000_000L };

        final DefaultAuthorizationManager spy = spy( mgr );
        spy.setClock( () -> now[ 0 ] );
        spy.configureBootstrap( "alice", 60L );
        // Advance beyond the configured window
        now[ 0 ] += 61_000L;

        // Without the bootstrap override active, fall through to the policy check.
        doReturn( false ).when( spy ).checkStaticPermission( any(), any() );

        final Session session = mockSession( true, new WikiPrincipal( "alice" ) );
        final PagePermission perm = new PagePermission( "test:SomePage", "view" );

        assertFalse( spy.checkPermission( session, perm ) );
    }

    @Test
    void bootstrapAdminDoesNotApplyToOtherUsers() {
        final long[] now = { 1_000_000L };
        mgr.setClock( () -> now[ 0 ] );
        mgr.configureBootstrap( "alice", 60L );

        // A different user must not inherit the override.
        final DefaultAuthorizationManager spy = spy( mgr );
        doReturn( false ).when( spy ).checkStaticPermission( any(), any() );

        final Session session = mockSession( true, new WikiPrincipal( "bob" ) );
        final PagePermission perm = new PagePermission( "test:SomePage", "view" );

        assertFalse( spy.checkPermission( session, perm ) );
    }

    @Test
    void bootstrapAdminDeniedToAssertedUnauthenticatedSession() {
        final long[] now = { 1_000_000L };
        mgr.setClock( () -> now[ 0 ] );
        mgr.configureBootstrap( "bootadmin", 60L );

        final DefaultAuthorizationManager spy = spy( mgr );
        doReturn( false ).when( spy ).checkStaticPermission( any(), any() );

        // Asserted (remembered-name) session: carries the name but is not authenticated.
        final Session session = mockSession( false, new WikiPrincipal( "bootadmin" ) );
        when( session.isAsserted() ).thenReturn( true );

        assertFalse( spy.checkPermission( session, new AllPermission( "*" ) ) );
        assertFalse( spy.checkPermission( session, new PagePermission( "test:SomePage", "view" ) ) );
    }

    @Test
    void bootstrapAdminDeniedWhenNameIsOnlyADisplayNamePrincipal() {
        final long[] now = { 1_000_000L };
        mgr.setClock( () -> now[ 0 ] );
        mgr.configureBootstrap( "bootadmin", 60L );

        final DefaultAuthorizationManager spy = spy( mgr );
        doReturn( false ).when( spy ).checkStaticPermission( any(), any() );

        // Authenticated as "mallory"; "bootadmin" is only the user's full name.
        final Session session = mockSession( true, new WikiPrincipal( "mallory" ),
                new WikiPrincipal( "bootadmin", WikiPrincipal.FULL_NAME ) );

        assertFalse( spy.checkPermission( session, new AllPermission( "*" ) ) );
    }

    @Test
    void bootstrapAdminAppliesToAuthenticatedSessionForAllPermission() {
        final long[] now = { 1_000_000L };
        mgr.setClock( () -> now[ 0 ] );
        mgr.configureBootstrap( "bootadmin", 60L );

        final Session session = mockSession( true, new WikiPrincipal( "bootadmin" ) );

        assertTrue( mgr.checkPermission( session, new AllPermission( "*" ) ) );
    }

    @Test
    void userPrincipalPolicyGrantsDoNotApplyToAssertedSession() {
        final DefaultAuthorizationManager spy = spy( mgr );
        // A policy grant keyed on the user principal "grantee" (roles grant nothing).
        doAnswer( inv -> {
            final Principal[] ps = inv.getArgument( 0 );
            return java.util.Arrays.stream( ps ).anyMatch( p -> "grantee".equals( p.getName() ) );
        } ).when( spy ).allowedByLocalPolicy( any( Principal[].class ), any() );

        final PagePermission perm = new PagePermission( "test:SomePage", "view" );

        final Session authenticated = mockSession( true, new WikiPrincipal( "grantee" ) );
        assertTrue( spy.checkStaticPermission( authenticated, perm ) );

        final Session asserted = mockSession( false, new WikiPrincipal( "grantee" ) );
        when( asserted.isAsserted() ).thenReturn( true );
        assertFalse( spy.checkStaticPermission( asserted, perm ) );
    }

    @Test
    void configureBootstrapWithBlankAdminClearsOverride() {
        mgr.configureBootstrap( "alice", 60L );
        mgr.configureBootstrap( "  ", 60L );

        final DefaultAuthorizationManager spy = spy( mgr );
        doReturn( false ).when( spy ).checkStaticPermission( any(), any() );

        final Session session = mockSession( true, new WikiPrincipal( "alice" ) );
        final PagePermission perm = new PagePermission( "test:SomePage", "view" );

        assertFalse( spy.checkPermission( session, perm ) );
    }

    // ==================== filterViewable: null session ====================

    @Test
    void filterViewableReturnsEmptySetForNullSession() {
        assertTrue( mgr.filterViewable( null, java.util.List.of( "SomePage" ) ).isEmpty() );
    }

    // ==================== getAuthorizer: not yet initialized ====================

    @Test
    void getAuthorizerThrowsWhenNeverInitialized() {
        final DefaultAuthorizationManager fresh = new DefaultAuthorizationManager();
        assertThrows( WikiSecurityException.class, fresh::getAuthorizer );
    }

    // ==================== hasAccess ====================

    @Test
    void hasAccessDeniedForAuthenticatedUserLogsAndRedirectsWithoutThrowing() throws Exception {
        final InternationalizationManager i18n = mock( InternationalizationManager.class );
        when( i18n.getBundle( anyString(), any() ) ).thenCallRealMethod();
        final WikiEngine localEngine = ( WikiEngine ) MockEngineBuilder.engine()
                .with( PageManager.class, pageManager )
                .with( AclManager.class, aclManager )
                .with( GroupManager.class, groupManager )
                .with( UserManager.class, userManager )
                .with( InternationalizationManager.class, i18n )
                .build();
        when( localEngine.getURL( anyString(), anyString(), any() ) ).thenReturn( "/Login.jsp" );
        final DefaultAuthorizationManager localMgr =
                new DefaultAuthorizationManager( localEngine, pageManager, aclManager, groupManager, userManager );
        final DefaultAuthorizationManager spy = spy( localMgr );
        doReturn( false ).when( spy ).checkPermission( any(), any() );

        final Session session = mockSession( true, new WikiPrincipal( "alice" ) );
        when( session.getUserPrincipal() ).thenReturn( new WikiPrincipal( "alice" ) );

        final HttpServletRequest request = mock( HttpServletRequest.class );
        when( request.getAttribute( Context.ATTR_CONTEXT ) ).thenReturn( null );
        when( request.getLocale() ).thenReturn( java.util.Locale.ENGLISH );
        when( request.getSession() ).thenReturn( mock( HttpSession.class ) );

        final Page page = mock( Page.class );
        when( page.getName() ).thenReturn( "SomePage" );

        final Context context = mock( Context.class );
        when( context.getWikiSession() ).thenReturn( session );
        when( context.requiredPermission() ).thenReturn( new PagePermission( "test:SomePage", "view" ) );
        when( context.getHttpRequest() ).thenReturn( request );
        when( context.getPage() ).thenReturn( page );
        when( context.getName() ).thenReturn( "SomePage" );
        when( context.getEngine() ).thenReturn( localEngine );

        final HttpServletResponse response = mock( HttpServletResponse.class );

        final boolean result = spy.hasAccess( context, response, true );

        assertFalse( result );
        verify( request ).setAttribute( Context.ATTR_CONTEXT, context );
        verify( response ).sendRedirect( "/Login.jsp" );
    }

    @Test
    void hasAccessDeniedForAnonymousUserLogsAndRedirectsWithoutThrowing() throws Exception {
        final InternationalizationManager i18n = mock( InternationalizationManager.class );
        when( i18n.getBundle( anyString(), any() ) ).thenCallRealMethod();
        final WikiEngine localEngine = ( WikiEngine ) MockEngineBuilder.engine()
                .with( PageManager.class, pageManager )
                .with( AclManager.class, aclManager )
                .with( GroupManager.class, groupManager )
                .with( UserManager.class, userManager )
                .with( InternationalizationManager.class, i18n )
                .build();
        when( localEngine.getURL( anyString(), anyString(), any() ) ).thenReturn( "/Login.jsp" );
        final DefaultAuthorizationManager localMgr =
                new DefaultAuthorizationManager( localEngine, pageManager, aclManager, groupManager, userManager );
        final DefaultAuthorizationManager spy = spy( localMgr );
        doReturn( false ).when( spy ).checkPermission( any(), any() );

        final Session session = mockSession( false, new WikiPrincipal( "anon" ) );
        when( session.getUserPrincipal() ).thenReturn( new WikiPrincipal( "anon" ) );

        final HttpServletRequest request = mock( HttpServletRequest.class );
        // A non-null ATTR_CONTEXT already stashed -> the stash branch is skipped this time.
        when( request.getAttribute( Context.ATTR_CONTEXT ) ).thenReturn( new Object() );
        when( request.getLocale() ).thenReturn( java.util.Locale.ENGLISH );
        when( request.getSession() ).thenReturn( mock( HttpSession.class ) );

        final Page page = mock( Page.class );
        when( page.getName() ).thenReturn( "SomePage" );

        final Context context = mock( Context.class );
        when( context.getWikiSession() ).thenReturn( session );
        when( context.requiredPermission() ).thenReturn( new PagePermission( "test:SomePage", "view" ) );
        when( context.getHttpRequest() ).thenReturn( request );
        when( context.getPage() ).thenReturn( page );
        when( context.getName() ).thenReturn( "SomePage" );
        when( context.getEngine() ).thenReturn( localEngine );

        final HttpServletResponse response = mock( HttpServletResponse.class );

        final boolean result = spy.hasAccess( context, response, true );

        assertFalse( result );
        verify( request, never() ).setAttribute( eq( Context.ATTR_CONTEXT ), any() );
        verify( response ).sendRedirect( "/Login.jsp" );
    }

    // ==================== initialize()'s private helpers, exercised via reflection ====================
    // (Reaching these through the full initialize() chain would also require a working JAAS
    // Authorizer and either a real JNDI DataSource or a real security-policy file — reflection
    // targets exactly the branch under test without that unrelated setup.)

    @Test
    void initializeSecurityPolicyWithDatasourceConfiguredButNoJndiBoundThrowsWikiException() throws Exception {
        final Properties props = new Properties();
        props.setProperty( AbstractJDBCDatabase.PROP_DATASOURCE, "jdbc/DefinitelyNotBound" + System.nanoTime() );

        final InvocationTargetException ite = assertThrows( InvocationTargetException.class,
                () -> invokePrivate( "initializeSecurityPolicy", new Class<?>[]{ Properties.class }, props ) );
        assertInstanceOf( WikiException.class, ite.getCause() );
    }

    @Test
    void initializeFilePolicyWithMissingPolicyFileThrowsWikiException() throws Exception {
        when( engine.findConfigFile( anyString() ) ).thenReturn( null );
        final Properties props = new Properties();

        final InvocationTargetException ite = assertThrows( InvocationTargetException.class,
                () -> invokePrivate( "initializeFilePolicy", new Class<?>[]{ Properties.class }, props ) );
        assertInstanceOf( WikiException.class, ite.getCause() );
    }

    @Test
    void initializeFilePolicyWhenFindConfigFileThrowsWrapsAsWikiException() throws Exception {
        when( engine.findConfigFile( anyString() ) ).thenThrow( new RuntimeException( "disk error" ) );
        final Properties props = new Properties();

        final InvocationTargetException ite = assertThrows( InvocationTargetException.class,
                () -> invokePrivate( "initializeFilePolicy", new Class<?>[]{ Properties.class }, props ) );
        assertInstanceOf( WikiException.class, ite.getCause() );
        assertTrue( ite.getCause().getMessage().contains( "disk error" ) );
    }

    @Test
    void initializeBootstrapAdminWithValidMaxAgeParsesAndActivates() throws Exception {
        final Properties props = new Properties();
        props.setProperty( DefaultAuthorizationManager.PROP_BOOTSTRAP_ADMIN, "bootstrapuser2" );
        props.setProperty( DefaultAuthorizationManager.PROP_BOOTSTRAP_MAX_AGE, "120" );

        invokePrivate( "initializeBootstrapAdmin", new Class<?>[]{ Properties.class }, props );

        final Session session = mockSession( true, new WikiPrincipal( "bootstrapuser2" ) );
        assertTrue( mgr.checkPermission( session, new PagePermission( "test:AnyPage", "view" ) ) );
    }

    @Test
    void initializeBootstrapAdminWithInvalidMaxAgeFallsBackToDefaultAndStillActivates() throws Exception {
        final Properties props = new Properties();
        props.setProperty( DefaultAuthorizationManager.PROP_BOOTSTRAP_ADMIN, "bootstrapuser" );
        props.setProperty( DefaultAuthorizationManager.PROP_BOOTSTRAP_MAX_AGE, "not-a-number" );

        invokePrivate( "initializeBootstrapAdmin", new Class<?>[]{ Properties.class }, props );

        // The malformed max-age must not prevent the override from activating (using the default).
        final Session session = mockSession( true, new WikiPrincipal( "bootstrapuser" ) );
        assertTrue( mgr.checkPermission( session, new PagePermission( "test:AnyPage", "view" ) ) );
    }

    // ==================== locateImplementation(), exercised via reflection ====================

    @Test
    void locateImplementationWithUnresolvableClassNameThrowsWikiException() throws Exception {
        final InvocationTargetException ite = assertThrows( InvocationTargetException.class,
                () -> invokePrivate( "locateImplementation", new Class<?>[]{ String.class },
                        "com.wikantik.auth.authorize.NoSuchAuthorizerXYZ" ) );
        assertInstanceOf( WikiException.class, ite.getCause() );
    }

    @Test
    void locateImplementationWithNullClassNameThrowsNoRequiredPropertyException() throws Exception {
        final InvocationTargetException ite = assertThrows( InvocationTargetException.class,
                () -> invokePrivate( "locateImplementation", new Class<?>[]{ String.class }, new Object[]{ null } ) );
        assertInstanceOf( NoRequiredPropertyException.class, ite.getCause() );
    }

    // ==================== allowedByLocalPolicy: database-backed policy path ====================

    @Test
    void allowedByLocalPolicyDatabaseBackedGrantsWhenAnyPrincipalMatches() throws Exception {
        final DatabasePolicy dbPolicy = mock( DatabasePolicy.class );
        final Principal alice = new WikiPrincipal( "alice" );
        final PagePermission perm = new PagePermission( "test:SomePage", "view" );
        when( dbPolicy.implies( alice, perm ) ).thenReturn( true );
        setField( "databasePolicy", dbPolicy );

        assertTrue( mgr.allowedByLocalPolicy( new Principal[]{ alice }, perm ) );
    }

    @Test
    void allowedByLocalPolicyDatabaseBackedDeniesWhenNoPrincipalMatches() throws Exception {
        final DatabasePolicy dbPolicy = mock( DatabasePolicy.class );
        final Principal alice = new WikiPrincipal( "alice" );
        final PagePermission perm = new PagePermission( "test:SomePage", "view" );
        when( dbPolicy.implies( any(), any() ) ).thenReturn( false );
        setField( "databasePolicy", dbPolicy );

        assertFalse( mgr.allowedByLocalPolicy( new Principal[]{ alice }, perm ) );
    }

    // ==================== resolvePrincipal: authorizer role hit + genuinely-unresolved user ====================

    @Test
    void resolvePrincipalDelegatesToAuthorizerRole() throws Exception {
        final Authorizer authorizerMock = mock( Authorizer.class );
        setField( "authorizer", authorizerMock );
        final Principal authorizerRole = new Role( "CustomAuthorizerRole" );
        when( authorizerMock.findRole( "CustomAuthorizerRole" ) ).thenReturn( authorizerRole );

        final Principal result = mgr.resolvePrincipal( "CustomAuthorizerRole" );
        assertEquals( authorizerRole, result );
    }

    @Test
    void resolvePrincipalReturnsUnresolvedWhenProfileFoundButNoPrincipalNameMatches() throws Exception {
        setAuthorizer( mgr );
        when( groupManager.findRole( "alice" ) ).thenReturn( null );

        final UserProfile profile = mock( UserProfile.class );
        when( profile.getLoginName() ).thenReturn( "alice" );
        when( userDatabase.findByLoginName( "alice" ) ).thenReturn( profile );
        // Profile is found, but none of its principals' names equal "alice" exactly.
        when( userDatabase.getPrincipals( "alice" ) ).thenReturn( new Principal[]{ new WikiPrincipal( "SomeoneElse" ) } );

        final Principal result = mgr.resolvePrincipal( "alice" );
        assertInstanceOf( UnresolvedPrincipal.class, result );
        assertEquals( "alice", result.getName() );
    }

    // ==================== getDatabasePolicy / removeWikiEventListener ====================

    @Test
    void getDatabasePolicyReturnsConfiguredInstance() throws Exception {
        final DatabasePolicy dbPolicy = mock( DatabasePolicy.class );
        setField( "databasePolicy", dbPolicy );
        assertSame( dbPolicy, mgr.getDatabasePolicy() );
    }

    @Test
    void removeWikiEventListenerDoesNotThrow() {
        final com.wikantik.event.WikiEventListener listener = event -> { };
        assertDoesNotThrow( () -> mgr.addWikiEventListener( listener ) );
        assertDoesNotThrow( () -> mgr.removeWikiEventListener( listener ) );
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

    /** Sets a private field on {@link #mgr} directly, for branches only reachable that way in a unit test. */
    private void setField( final String fieldName, final Object value ) throws Exception {
        final Field f = DefaultAuthorizationManager.class.getDeclaredField( fieldName );
        f.setAccessible( true );
        f.set( mgr, value );
    }

    /** Invokes a private method on {@link #mgr} directly, for branches only reachable that way in a unit test. */
    private Object invokePrivate( final String methodName, final Class<?>[] paramTypes, final Object... args ) throws Exception {
        final Method m = DefaultAuthorizationManager.class.getDeclaredMethod( methodName, paramTypes );
        m.setAccessible( true );
        return m.invoke( mgr, args );
    }

}
