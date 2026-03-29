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
package com.wikantik.auth.acl;

import com.wikantik.MockEngineBuilder;
import com.wikantik.api.core.Acl;
import com.wikantik.api.core.AclEntry;
import com.wikantik.api.core.Attachment;
import com.wikantik.api.core.Engine;
import com.wikantik.api.core.Page;
import com.wikantik.api.pages.PageLock;
import com.wikantik.api.spi.Wiki;
import com.wikantik.auth.AuthorizationManager;
import com.wikantik.auth.WikiPrincipal;
import com.wikantik.auth.WikiSecurityException;
import com.wikantik.auth.permissions.PermissionFactory;
import com.wikantik.pages.PageManager;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.security.Principal;
import java.util.Arrays;
import java.util.Properties;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/**
 * Constructor-injection / unit tests for {@link DefaultAclManager} covering branches
 * not reached by the full-engine {@link DefaultAclManagerTest}:
 *
 * <ul>
 *   <li>{@code parseAcl()} with an invalid rule line → throws WikiSecurityException</li>
 *   <li>{@code parseAcl()} with an invalid permission type → throws WikiSecurityException</li>
 *   <li>{@code parseAcl()} valid rule that extends a pre-existing ACL on the page</li>
 *   <li>{@code getPermissions()} when page already has an ACL set (cached path)</li>
 *   <li>{@code getPermissions()} for an {@link Attachment} → delegates to parent page</li>
 *   <li>{@code getPermissions()} with page text containing two ACL rules</li>
 *   <li>{@code getPermissions()} with empty page text → empty ACL</li>
 *   <li>{@code setPermissions()} — no existing lock</li>
 *   <li>{@code setPermissions()} — existing lock is released before writing</li>
 *   <li>{@code printAcl()} with an empty ACL</li>
 *   <li>{@code printAcl()} with a single entry and one permission</li>
 * </ul>
 */
class DefaultAclManagerCITest {

    private Engine engine;
    private AuthorizationManager authMgr;
    private PageManager pageMgr;

    private DefaultAclManager aclMgr;

    @BeforeEach
    void setUp() {
        authMgr = mock( AuthorizationManager.class );
        pageMgr = mock( PageManager.class );

        engine = MockEngineBuilder.engine()
                .with( AuthorizationManager.class, authMgr )
                .with( PageManager.class, pageMgr )
                .build();

        aclMgr = new DefaultAclManager();
        aclMgr.initialize( engine, new Properties() );
    }

    // -------------------------------------------------------------------------
    //  parseAcl — invalid rule line (no action token) → WikiSecurityException
    // -------------------------------------------------------------------------

    @Test
    void parseAclThrowsOnInvalidRuleLine() {
        final Page page = mock( Page.class );
        when( page.getAcl() ).thenReturn( null );

        // Only one token "ALLOW" — nextToken() for action will throw NoSuchElementException
        assertThrows( WikiSecurityException.class,
                () -> aclMgr.parseAcl( page, "ALLOW" ) );
    }

    // -------------------------------------------------------------------------
    //  parseAcl — invalid permission type → WikiSecurityException
    // -------------------------------------------------------------------------

    @Test
    void parseAclThrowsOnInvalidPermissionType() {
        final Page page = mock( Page.class );
        when( page.getAcl() ).thenReturn( null );
        when( page.getWiki() ).thenReturn( "" );
        when( page.getName() ).thenReturn( "TestPage" );
        // PermissionFactory.getPagePermission throws IllegalArgumentException for unknown action
        when( authMgr.resolvePrincipal( anyString() ) ).thenReturn( new WikiPrincipal( "Alice" ) );

        assertThrows( WikiSecurityException.class,
                () -> aclMgr.parseAcl( page, "ALLOW notanaction Alice" ) );
    }

    // -------------------------------------------------------------------------
    //  parseAcl — valid rule extends pre-existing ACL
    // -------------------------------------------------------------------------

    @Test
    void parseAclAddsEntryToExistingAcl() throws WikiSecurityException {
        // Create a page that already has a view-only ACL entry for Alice
        final Page page = mock( Page.class );
        when( page.getWiki() ).thenReturn( "main" );
        when( page.getName() ).thenReturn( "TestPage" );

        final Acl existingAcl = Wiki.acls().acl();
        final AclEntry existingEntry = Wiki.acls().entry();
        existingEntry.setPrincipal( new WikiPrincipal( "Alice" ) );
        existingEntry.addPermission( PermissionFactory.getPagePermission( "main:TestPage", "view" ) );
        existingAcl.addEntry( existingEntry );
        when( page.getAcl() ).thenReturn( existingAcl );

        when( authMgr.resolvePrincipal( eq( "Alice" ) ) ).thenReturn( new WikiPrincipal( "Alice" ) );

        // Parse an edit ACL for Alice — should add to her existing entry
        final Acl result = aclMgr.parseAcl( page, "ALLOW edit Alice" );
        assertNotNull( result );

        final AclEntry entry = result.getAclEntry( new WikiPrincipal( "Alice" ) );
        assertNotNull( entry );
        assertTrue( entry.checkPermission(
                PermissionFactory.getPagePermission( "main:TestPage", "edit" ) ) );
    }

    // -------------------------------------------------------------------------
    //  getPermissions — page already has a cached ACL (skips re-parsing)
    // -------------------------------------------------------------------------

    @Test
    void getPermissionsReturnsCachedAclWithoutReparsing() {
        final Page page = mock( Page.class );
        final Acl cachedAcl = Wiki.acls().acl();
        when( page.getAcl() ).thenReturn( cachedAcl );

        final Acl result = aclMgr.getPermissions( page );
        assertSame( cachedAcl, result );
        // PageManager should never be touched when ACL is already cached
        verify( pageMgr, never() ).getPureText( anyString(), anyInt() );
    }

    // -------------------------------------------------------------------------
    //  getPermissions — Attachment delegates to parent page
    // -------------------------------------------------------------------------

    @Test
    void getPermissionsDelegatesToParentPageForAttachment() {
        // Set up the parent page with a real ACL
        final Page parentPage = mock( Page.class );
        final Acl parentAcl = Wiki.acls().acl();
        when( parentPage.getAcl() ).thenReturn( parentAcl );
        when( pageMgr.getPage( eq( "ParentPage" ) ) ).thenReturn( parentPage );

        // The attachment has no ACL yet
        final Attachment att = mock( Attachment.class );
        when( att.getAcl() ).thenReturn( null );
        when( att.getParentName() ).thenReturn( "ParentPage" );

        final Acl result = aclMgr.getPermissions( att );
        assertSame( parentAcl, result );
    }

    // -------------------------------------------------------------------------
    //  getPermissions — page text with two ACL rules
    // -------------------------------------------------------------------------

    @Test
    void getPermissionsExtractsMultipleAclRulesFromPageText() {
        final Page page = mock( Page.class );
        when( page.getWiki() ).thenReturn( "main" );
        when( page.getName() ).thenReturn( "MultiAclPage" );

        // Make page.setAcl()/getAcl() persistent on the mock so parseAcl accumulates entries
        final Acl[] holder = { null };
        doAnswer( inv -> { holder[0] = inv.getArgument( 0 ); return null; } )
                .when( page ).setAcl( any( Acl.class ) );
        when( page.getAcl() ).thenAnswer( inv -> holder[0] );

        // Stub both the overloaded String+int form AND the default Page form
        when( pageMgr.getPureText( eq( "MultiAclPage" ), anyInt() ) )
                .thenReturn( "[{ALLOW view Alice}] some text [{ALLOW edit Bob}]" );
        when( pageMgr.getPureText( any( Page.class ) ) )
                .thenReturn( "[{ALLOW view Alice}] some text [{ALLOW edit Bob}]" );

        // extractAclFromPageText passes the full [{...}] match to parseAcl; StringTokenizer
        // skips the "[{ALLOW" first token and parses the principal as "Alice}]" / "Bob}]"
        when( authMgr.resolvePrincipal( eq( "Alice}]" ) ) ).thenReturn( new WikiPrincipal( "Alice" ) );
        when( authMgr.resolvePrincipal( eq( "Bob}]" ) ) ).thenReturn( new WikiPrincipal( "Bob" ) );

        final Acl result = aclMgr.getPermissions( page );
        assertNotNull( result );
        assertFalse( result.isEmpty() );

        // Alice should have view permission, Bob should have edit permission
        final Principal[] viewPrincipals = result.findPrincipals(
                PermissionFactory.getPagePermission( "main:MultiAclPage", "view" ) );
        final Principal[] editPrincipals = result.findPrincipals(
                PermissionFactory.getPagePermission( "main:MultiAclPage", "edit" ) );

        assertTrue( Arrays.asList( viewPrincipals ).contains( new WikiPrincipal( "Alice" ) ),
                "Alice should have view permission" );
        assertTrue( Arrays.asList( editPrincipals ).contains( new WikiPrincipal( "Bob" ) ),
                "Bob should have edit permission" );
    }

    // -------------------------------------------------------------------------
    //  getPermissions — empty page text → empty ACL
    // -------------------------------------------------------------------------

    @Test
    void getPermissionsReturnsEmptyAclForEmptyPageText() {
        final Page page = mock( Page.class );
        when( page.getAcl() ).thenReturn( null );
        when( page.getName() ).thenReturn( "EmptyPage" );
        when( pageMgr.getPureText( eq( "EmptyPage" ), anyInt() ) ).thenReturn( "" );
        when( pageMgr.getPureText( any( Page.class ) ) ).thenReturn( "" );

        final Acl result = aclMgr.getPermissions( page );
        assertNotNull( result );
        assertTrue( result.isEmpty() );
    }

    // -------------------------------------------------------------------------
    //  setPermissions — no existing lock
    // -------------------------------------------------------------------------

    @Test
    void setPermissionsWritesNewAclTextWhenNoLockExists() throws Exception {
        final Page page = mock( Page.class );
        when( page.getName() ).thenReturn( "LocklessPage" );
        when( page.getWiki() ).thenReturn( "" );

        when( pageMgr.getCurrentLock( page ) ).thenReturn( null );
        // Stub both forms of getPureText so the default method works too
        when( pageMgr.getPureText( eq( "LocklessPage" ), anyInt() ) )
                .thenReturn( "Some content without any ACL rules." );
        when( pageMgr.getPureText( any( Page.class ) ) )
                .thenReturn( "Some content without any ACL rules." );

        // Build an ACL with one entry
        final Acl acl = Wiki.acls().acl();
        final AclEntry entry = Wiki.acls().entry();
        entry.setPrincipal( new WikiPrincipal( "Charlie" ) );
        entry.addPermission( PermissionFactory.getPagePermission( "test:LocklessPage", "view" ) );
        acl.addEntry( entry );
        when( page.getAcl() ).thenReturn( acl );

        aclMgr.setPermissions( page, acl );

        // putPageText should have been called with content that includes the new ACL header
        verify( pageMgr ).putPageText( eq( page ), contains( "ALLOW view Charlie" ) );
    }

    // -------------------------------------------------------------------------
    //  setPermissions — existing lock is released before writing
    // -------------------------------------------------------------------------

    @Test
    void setPermissionsUnlocksPageBeforeWriting() throws Exception {
        final Page page = mock( Page.class );
        when( page.getName() ).thenReturn( "LockedPage" );
        when( page.getWiki() ).thenReturn( "" );

        final PageLock lock = mock( PageLock.class );
        when( pageMgr.getCurrentLock( page ) ).thenReturn( lock );
        when( pageMgr.getPureText( eq( "LockedPage" ), anyInt() ) )
                .thenReturn( "Content. [{ALLOW view OldUser}]" );
        when( pageMgr.getPureText( any( Page.class ) ) )
                .thenReturn( "Content. [{ALLOW view OldUser}]" );

        final Acl acl = Wiki.acls().acl();
        final AclEntry entry = Wiki.acls().entry();
        entry.setPrincipal( new WikiPrincipal( "NewUser" ) );
        entry.addPermission( PermissionFactory.getPagePermission( "test:LockedPage", "edit" ) );
        acl.addEntry( entry );
        when( page.getAcl() ).thenReturn( acl );

        aclMgr.setPermissions( page, acl );

        // The lock should have been released
        verify( pageMgr ).unlockPage( lock );
        // And the page should have been saved with the old ACL tag stripped
        final ArgumentCaptor<String> textCaptor = ArgumentCaptor.forClass( String.class );
        verify( pageMgr ).putPageText( eq( page ), textCaptor.capture() );
        assertFalse( textCaptor.getValue().contains( "OldUser" ),
                "Saved text should not contain the old ACL entry for OldUser" );
    }

    // -------------------------------------------------------------------------
    //  printAcl — empty ACL produces empty string
    // -------------------------------------------------------------------------

    @Test
    void printAclReturnsEmptyStringForEmptyAcl() {
        final Acl emptyAcl = Wiki.acls().acl();
        assertEquals( "", DefaultAclManager.printAcl( emptyAcl ) );
    }

    // -------------------------------------------------------------------------
    //  printAcl — single entry with one permission
    // -------------------------------------------------------------------------

    @Test
    void printAclFormatsCorrectlyForSingleEntry() {
        final Acl acl = Wiki.acls().acl();
        final AclEntry entry = Wiki.acls().entry();
        entry.setPrincipal( new WikiPrincipal( "Zara" ) );
        entry.addPermission( PermissionFactory.getPagePermission( "test:X", "view" ) );
        acl.addEntry( entry );

        final String text = DefaultAclManager.printAcl( acl );
        assertEquals( "[{ALLOW view Zara}]\n", text );
    }

}
