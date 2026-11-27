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
import com.wikantik.api.core.Engine;
import com.wikantik.api.core.Page;
import com.wikantik.api.core.Session;
import com.wikantik.api.managers.PageManager;
import com.wikantik.api.providers.PageProvider;
import com.wikantik.auth.acl.AclManager;
import com.wikantik.auth.acl.DefaultAclManager;
import com.wikantik.auth.acl.UnresolvedPrincipal;
import com.wikantik.auth.authorize.GroupManager;
import com.wikantik.auth.authorize.Role;
import com.wikantik.auth.permissions.AllPermission;
import com.wikantik.auth.permissions.PagePermission;
import com.wikantik.auth.user.UserDatabase;
import com.wikantik.event.WikiEvent;
import com.wikantik.event.WikiEventListener;
import com.wikantik.event.WikiSecurityEvent;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import javax.security.auth.Subject;
import java.security.Permission;
import java.security.Principal;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.spy;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Mutation-driven pins on the allow/deny decisions of {@link DefaultAuthorizationManager}: the security events a
 * decision fires, the bootstrap-admin expiry boundary, which principals an ACL user entry matches, the batch
 * {@code filterViewable} path and the private-cluster gate on missing pages.
 */
class DefaultAuthorizationManagerDecisionTest {

    private PageManager pageManager;
    private AclManager aclManager;
    private GroupManager groupManager;
    private UserManager userManager;
    private DefaultAuthorizationManager mgr;
    /** Strong reference: the event manager holds listeners weakly. */
    private final List< WikiEvent > seen = new ArrayList<>();
    private final WikiEventListener listener = seen::add;

    @BeforeEach
    void setUp() {
        pageManager = mock( PageManager.class );
        aclManager = mock( AclManager.class );
        groupManager = mock( GroupManager.class );
        userManager = mock( UserManager.class );
        when( userManager.getUserDatabase() ).thenReturn( mock( UserDatabase.class ) );
        final Engine engine = MockEngineBuilder.engine()
                .with( PageManager.class, pageManager )
                .with( AclManager.class, aclManager )
                .with( GroupManager.class, groupManager )
                .with( UserManager.class, userManager )
                .build();
        mgr = new DefaultAuthorizationManager( engine, pageManager, aclManager, groupManager, userManager );
    }

    /** A manager whose static policy grants everything except AllPermission, like a plain authenticated user. */
    private DefaultAuthorizationManager staticGrantsEverythingButAll() {
        final DefaultAuthorizationManager spy = spy( mgr );
        doAnswer( inv -> !( inv.getArgument( 1 ) instanceof AllPermission ) )
                .when( spy ).checkStaticPermission( any(), any() );
        return spy;
    }

    private static Session session( final boolean authenticated, final Principal login, final Principal... extra ) {
        final Session s = mock( Session.class );
        when( s.isAuthenticated() ).thenReturn( authenticated );
        when( s.getLoginPrincipal() ).thenReturn( login );
        final List< Principal > all = new ArrayList<>();
        if( login != null ) {
            all.add( login );
        }
        all.addAll( List.of( extra ) );
        when( s.getPrincipals() ).thenReturn( all.toArray( new Principal[ 0 ] ) );
        when( s.getRoles() ).thenReturn( extra );
        when( s.getSubject() ).thenReturn( new Subject() );
        when( s.getStatus() ).thenReturn( authenticated ? "authenticated" : "anonymous" );
        for( final Principal p : extra ) {
            when( s.hasPrincipal( p ) ).thenReturn( true );
        }
        return s;
    }

    private Page page( final String name ) {
        final Page p = mock( Page.class );
        when( p.getName() ).thenReturn( name );
        when( p.getVersion() ).thenReturn( 1 );
        when( p.getWiki() ).thenReturn( "test" );
        when( pageManager.getPageWithoutMetadata( name, PageProvider.LATEST_VERSION ) ).thenReturn( p );
        return p;
    }

    private Acl aclNaming( final Page p, final Principal... principals ) {
        final Acl acl = mock( Acl.class );
        when( acl.isEmpty() ).thenReturn( false );
        when( acl.findPrincipals( any( Permission.class ) ) ).thenReturn( principals );
        when( aclManager.getPermissions( p ) ).thenReturn( acl );
        return acl;
    }

    // ---- decisions fire the matching security event --------------------------------------------------------------

    @Test
    void anAllowedDecisionFiresAccessAllowedAndADeniedOneFiresAccessDeniedWithTheReason() {
        final DefaultAuthorizationManager m = spy( mgr );
        m.addWikiEventListener( listener );
        doAnswer( inv -> false ).when( m ).checkStaticPermission( any(), any() );
        final Session alice = session( true, new WikiPrincipal( "alice" ), Role.AUTHENTICATED );
        final PagePermission view = new PagePermission( "test:P", "view" );

        assertFalse( m.checkPermission( alice, view ) );

        assertEquals( 1, seen.size() );
        final WikiSecurityEvent denied = ( WikiSecurityEvent ) seen.get( 0 );
        assertEquals( WikiSecurityEvent.ACCESS_DENIED, denied.getType() );
        assertEquals( "policy-denied", denied.getAttributes().get( "reason" ) );
        assertEquals( "authenticated", denied.getAttributes().get( "authStatus" ) );
        assertEquals( "Authenticated", denied.getAttributes().get( "roles" ) );

        seen.clear();
        final DefaultAuthorizationManager allowing = staticGrantsEverythingButAll();
        allowing.addWikiEventListener( listener );
        assertTrue( allowing.checkPermission( alice, view ) );
        assertEquals( 1, seen.size() );
        assertEquals( WikiSecurityEvent.ACCESS_ALLOWED, ( ( WikiSecurityEvent ) seen.get( 0 ) ).getType() );
    }

    @Test
    void isPermittedDecidesWithoutFiringAnyEvent() {
        final DefaultAuthorizationManager allowing = staticGrantsEverythingButAll();
        allowing.addWikiEventListener( listener );

        assertTrue( allowing.isPermitted( session( true, new WikiPrincipal( "alice" ) ),
                new PagePermission( "test:P", "view" ) ) );
        assertTrue( seen.isEmpty() );
    }

    // ---- bootstrap admin expiry boundary -------------------------------------------------------------------------

    @Test
    void theBootstrapOverrideStopsAtTheExactExpiryInstant() {
        final long[] now = { 1_000_000L };
        final DefaultAuthorizationManager m = spy( mgr );
        m.setClock( () -> now[ 0 ] );
        m.configureBootstrap( "boot", 60L );
        doAnswer( inv -> false ).when( m ).checkStaticPermission( any(), any() );
        final Session boot = session( true, new WikiPrincipal( "boot" ) );
        final PagePermission view = new PagePermission( "test:P", "view" );

        now[ 0 ] = 1_000_000L + 59_999L;
        assertTrue( m.isPermitted( boot, view ), "one millisecond before expiry" );
        now[ 0 ] = 1_000_000L + 60_000L;
        assertFalse( m.isPermitted( boot, view ), "at the expiry instant the override is over" );
        now[ 0 ] = 1_000_000L + 60_001L;
        assertFalse( m.isPermitted( boot, view ), "after expiry" );
    }

    // ---- which principal an ACL user entry matches ---------------------------------------------------------------

    @Test
    void aLoginTypedAclEntryMatchesOnlyTheSessionsLoginName() {
        final Session alice = session( true, new WikiPrincipal( "alice", WikiPrincipal.LOGIN_NAME ) );

        assertTrue( mgr.hasRoleOrPrincipal( alice, new WikiPrincipal( "alice", WikiPrincipal.LOGIN_NAME ) ) );
        assertFalse( mgr.hasRoleOrPrincipal( alice, new WikiPrincipal( "bob", WikiPrincipal.LOGIN_NAME ) ) );
    }

    @ParameterizedTest
    @ValueSource( strings = { WikiPrincipal.FULL_NAME, WikiPrincipal.WIKI_NAME } )
    void anAclEntryTypedAsAFullOrWikiNameNeverMatchesEvenWhenTheNamesAreEqual( final String type ) {
        final Session alice = session( true, new WikiPrincipal( "alice", WikiPrincipal.LOGIN_NAME ) );

        assertFalse( mgr.hasRoleOrPrincipal( alice, new WikiPrincipal( "alice", type ) ) );
    }

    @Test
    void anUntypedAclEntryIsTreatedAsALoginName() {
        final Session alice = session( true, new WikiPrincipal( "alice", WikiPrincipal.LOGIN_NAME ) );

        assertTrue( mgr.hasRoleOrPrincipal( alice, new WikiPrincipal( "alice", WikiPrincipal.UNSPECIFIED ) ) );
    }

    @ParameterizedTest
    @ValueSource( strings = { WikiPrincipal.FULL_NAME, WikiPrincipal.WIKI_NAME } )
    void aSessionWhoseLoginPrincipalIsAFullOrWikiNameMatchesNoUserEntry( final String type ) {
        final Session odd = session( true, new WikiPrincipal( "alice", type ) );

        assertFalse( mgr.hasRoleOrPrincipal( odd, new WikiPrincipal( "alice", WikiPrincipal.LOGIN_NAME ) ) );
        assertFalse( mgr.hasRoleOrPrincipal( odd, new UnresolvedPrincipal( "alice" ) ) );
    }

    @Test
    void anUnresolvedAclEntryMatchesOnlyASessionLoggedInUnderExactlyThatName() {
        final Session alice = session( true, new WikiPrincipal( "alice", WikiPrincipal.LOGIN_NAME ) );

        assertTrue( mgr.hasRoleOrPrincipal( alice, new UnresolvedPrincipal( "alice" ) ) );
        assertFalse( mgr.hasRoleOrPrincipal( alice, new UnresolvedPrincipal( "Alice" ) ) );
        assertFalse( mgr.hasRoleOrPrincipal( alice, new UnresolvedPrincipal( "bob" ) ) );
    }

    @Test
    void aSessionWithoutALoginPrincipalMatchesNoUserEntry() {
        final Session nobody = session( true, null );

        assertFalse( mgr.hasRoleOrPrincipal( nobody, new UnresolvedPrincipal( "alice" ) ) );
        assertFalse( mgr.hasRoleOrPrincipal( nobody, new WikiPrincipal( "alice", WikiPrincipal.LOGIN_NAME ) ) );
    }

    @Test
    void anUnauthenticatedSessionNeverMatchesAUserEntryEvenByLoginName() {
        final Session asserted = session( false, new WikiPrincipal( "alice", WikiPrincipal.LOGIN_NAME ) );

        assertFalse( mgr.hasRoleOrPrincipal( asserted, new WikiPrincipal( "alice", WikiPrincipal.LOGIN_NAME ) ) );
    }

    @Test
    void theLockedPrincipalOfAnUnreadableAclIsNeverHeldBySAnySession() {
        final Principal lockedName = new WikiPrincipal( DefaultAclManager.LOCKED_PRINCIPAL.getName() );
        final Session impostor = session( true, lockedName, lockedName );

        assertFalse( mgr.hasRoleOrPrincipal( impostor, DefaultAclManager.LOCKED_PRINCIPAL ) );
    }

    @Test
    void aRoleEntryIsHeldByTheSessionThatHoldsTheRole() {
        final Session user = session( true, new WikiPrincipal( "alice" ), Role.AUTHENTICATED );

        assertTrue( mgr.hasRoleOrPrincipal( user, Role.AUTHENTICATED ) );
        assertFalse( mgr.hasRoleOrPrincipal( user, new Role( "Admin" ) ) );
    }

    // ---- filterViewable ------------------------------------------------------------------------------------------

    @Test
    void filterViewableKeepsAnAclFreePageAndDropsAnAclPageTheCallerIsNotNamedOn() {
        final Page open = page( "Open" );
        when( aclManager.getPermissions( open ) ).thenReturn( null );
        final Page emptyAcl = page( "EmptyAcl" );
        final Acl empty = mock( Acl.class );
        when( empty.isEmpty() ).thenReturn( true );
        when( aclManager.getPermissions( emptyAcl ) ).thenReturn( empty );
        final Page secret = page( "Secret" );
        aclNaming( secret, new WikiPrincipal( "bob", WikiPrincipal.LOGIN_NAME ) );
        final Page mine = page( "Mine" );
        aclNaming( mine, new WikiPrincipal( "alice", WikiPrincipal.LOGIN_NAME ) );

        final DefaultAuthorizationManager m = staticGrantsEverythingButAll();
        final Session alice = session( true, new WikiPrincipal( "alice", WikiPrincipal.LOGIN_NAME ) );

        assertEquals( Set.of( "Open", "EmptyAcl", "Mine" ),
                m.filterViewable( alice, List.of( "Open", "EmptyAcl", "Secret", "Mine" ) ) );
    }

    @Test
    void filterViewableKeepsAPageThatDoesNotExistWhenThePolicyAllowsViewing() {
        when( pageManager.getPageWithoutMetadata( "Ghost", PageProvider.LATEST_VERSION ) ).thenReturn( null );
        final DefaultAuthorizationManager m = staticGrantsEverythingButAll();

        assertEquals( Set.of( "Ghost" ),
                m.filterViewable( session( true, new WikiPrincipal( "alice" ) ), List.of( "Ghost" ) ) );
    }

    @Test
    void filterViewableWithoutTheBlanketGrantStillHonoursAPageAcl() {
        final Page secret = page( "Secret" );
        aclNaming( secret, new WikiPrincipal( "bob", WikiPrincipal.LOGIN_NAME ) );
        final Page open = page( "Open" );
        when( aclManager.getPermissions( open ) ).thenReturn( null );
        final DefaultAuthorizationManager m = spy( mgr );
        // The wiki-wide "<wiki>:*" view is NOT granted; a per-page grant is.
        doAnswer( inv -> {
            final Object perm = inv.getArgument( 1 );
            return perm instanceof PagePermission pp && !"*".equals( pp.getPage() );
        } ).when( m ).checkStaticPermission( any(), any() );

        assertEquals( Set.of( "Open" ),
                m.filterViewable( session( true, new WikiPrincipal( "alice", WikiPrincipal.LOGIN_NAME ) ),
                        List.of( "Open", "Secret" ) ) );
    }

    // ---- private cluster gate ------------------------------------------------------------------------------------

    @Test
    void aMissingPageIsNotHiddenByAnEnabledPrivateClusterGate() {
        when( pageManager.getPageWithoutMetadata( "Ghost", PageProvider.LATEST_VERSION ) ).thenReturn( null );
        mgr.setPrivateClusterGate( new PrivateClusterGate(
                Set.of( "home-lab" ), Set.of( "HomeLab" ), pageManager ) );
        final DefaultAuthorizationManager m = staticGrantsEverythingButAll();
        m.setPrivateClusterGate( new PrivateClusterGate( Set.of( "home-lab" ), Set.of( "HomeLab" ), pageManager ) );

        assertTrue( m.isPermitted( session( true, new WikiPrincipal( "alice" ) ),
                new PagePermission( "test:Ghost", "view" ) ) );
    }

    @Test
    void aNullSessionIsHiddenFromPrivateClusterTextWithoutConsultingPolicy() {
        mgr.setPrivateClusterGate( new PrivateClusterGate(
                Set.of( "home-lab" ), Set.of( "HomeLab" ), pageManager ) );

        assertTrue( mgr.isHiddenByPrivateClusterText( null, "---\ncluster: home-lab\n---\nx\n" ) );
    }

    @Test
    void anAdministratorIsNotHiddenFromPrivateClusterTextButAnOrdinaryUserIs() {
        mgr.setPrivateClusterGate( new PrivateClusterGate(
                Set.of( "home-lab" ), Set.of( "HomeLab" ), pageManager ) );
        final String text = "---\ncluster: home-lab\n---\nx\n";
        final DefaultAuthorizationManager m = spy( mgr );
        doAnswer( inv -> inv.getArgument( 1 ) instanceof AllPermission ).when( m ).checkStaticPermission( any(), any() );
        final Session user = session( true, new WikiPrincipal( "alice" ) );

        assertFalse( m.isHiddenByPrivateClusterText( user, text ), "AllPermission holder is let in" );
        doAnswer( inv -> false ).when( m ).checkStaticPermission( any(), any() );
        assertTrue( m.isHiddenByPrivateClusterText( user, text ) );
        verify( pageManager, never() ).getPureText( anyString(), org.mockito.ArgumentMatchers.anyInt() );
    }

    @Test
    void aPlainUserSessionAgainstTheRealStaticPolicyIsHiddenFromPrivateText() {
        // No stubbing of checkStaticPermission: a real (not-yet-initialised) policy denies AllPermission.
        mgr.setPrivateClusterGate( new PrivateClusterGate(
                Set.of( "home-lab" ), Set.of( "HomeLab" ), pageManager ) );

        assertTrue( mgr.isHiddenByPrivateClusterText( session( true, new WikiPrincipal( "alice" ) ),
                "---\ncluster: home-lab\n---\nx\n" ) );
    }
}
