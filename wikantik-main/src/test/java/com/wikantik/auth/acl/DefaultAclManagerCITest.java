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
import com.wikantik.api.exceptions.ProviderException;
import com.wikantik.api.spi.Wiki;
import com.wikantik.auth.AuthorizationManager;
import com.wikantik.auth.WikiPrincipal;
import com.wikantik.auth.WikiSecurityException;
import com.wikantik.auth.permissions.PermissionFactory;
import com.wikantik.api.managers.PageManager;
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
 *   <li>{@code parseAcl()} valid rule that extends the ACL it is given, never the page's</li>
 *   <li>{@code getPermissions()} ignores an ACL set on the page; caches; locks disputed or unreadable pages</li>
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
    void parseAclThrowsOnInvalidRuleLine() throws Exception {
        final Page page = mock( Page.class );
        when( page.getAcl() ).thenReturn( null );

        // Only one token "ALLOW" — nextToken() for action will throw NoSuchElementException
        assertThrows( WikiSecurityException.class,
                () -> aclMgr.parseAcl( page, Wiki.acls().acl(), "ALLOW" ) );
    }

    // -------------------------------------------------------------------------
    //  parseAcl — invalid permission type → WikiSecurityException
    // -------------------------------------------------------------------------

    @Test
    void parseAclThrowsOnInvalidPermissionType() throws Exception {
        final Page page = mock( Page.class );
        when( page.getAcl() ).thenReturn( null );
        when( page.getWiki() ).thenReturn( "" );
        when( page.getName() ).thenReturn( "TestPage" );
        // PermissionFactory.getPagePermission throws IllegalArgumentException for unknown action
        when( authMgr.resolvePrincipal( anyString() ) ).thenReturn( new WikiPrincipal( "Alice" ) );

        assertThrows( WikiSecurityException.class,
                () -> aclMgr.parseAcl( page, Wiki.acls().acl(), "ALLOW notanaction Alice" ) );
    }

    // -------------------------------------------------------------------------
    //  parseAcl — builds into the ACL it is given, never the page's
    // -------------------------------------------------------------------------

    @Test
    void parseAclAddsEntryToTheAclItIsGivenAndLeavesThePageAlone() throws WikiSecurityException {
        final Page page = mock( Page.class );
        when( page.getWiki() ).thenReturn( "main" );
        when( page.getName() ).thenReturn( "TestPage" );

        final Acl into = Wiki.acls().acl();
        final AclEntry existingEntry = Wiki.acls().entry();
        existingEntry.setPrincipal( new WikiPrincipal( "Alice" ) );
        existingEntry.addPermission( PermissionFactory.getPagePermission( "main:TestPage", "view" ) );
        into.addEntry( existingEntry );

        when( authMgr.resolvePrincipal( eq( "Alice" ) ) ).thenReturn( new WikiPrincipal( "Alice" ) );

        // Parse an edit ACL for Alice — should add to her existing entry
        final Acl result = aclMgr.parseAcl( page, into, "ALLOW edit Alice" );
        assertSame( into, result );

        final AclEntry entry = result.getAclEntry( new WikiPrincipal( "Alice" ) );
        assertNotNull( entry );
        assertTrue( entry.checkPermission(
                PermissionFactory.getPagePermission( "main:TestPage", "edit" ) ) );
        verify( page, never() ).getAcl();
        verify( page, never() ).setAcl( any() );
    }

    // -------------------------------------------------------------------------
    //  getPermissions — an ACL someone set on the page is never what is enforced
    // -------------------------------------------------------------------------

    @Test
    void getPermissionsIgnoresAnAclSetOnThePage() throws Exception {
        final Page page = aclPage( "PresetAclPage", "No rules here." );
        final Acl preset = Wiki.acls().acl();
        final AclEntry entry = Wiki.acls().entry();
        entry.setPrincipal( new WikiPrincipal( "Mallory" ) );
        entry.addPermission( PermissionFactory.getPagePermission( "main:PresetAclPage", "view" ) );
        preset.addEntry( entry );
        page.setAcl( preset );

        final Acl result = aclMgr.getPermissions( page );
        assertNotSame( preset, result );
        assertTrue( result.isEmpty(), "the text has no rule: the page is open, whatever was set on it" );
    }

    @Test
    void getPermissionsServesTheSameVersionFromItsCacheWithoutRereading() throws Exception {
        final Page page = aclPage( "CachedAclPage", "[{ALLOW view Alice}]" );
        final Acl first = aclMgr.getPermissions( page );
        final Acl second = aclMgr.getPermissions( page );
        assertSame( first, second );
        verify( pageMgr, times( 1 ) ).getPageText( eq( "CachedAclPage" ), anyInt() );
    }

    @Test
    void theEnforcedAclCannotBeChanged() throws Exception {
        final Acl acl = aclMgr.getPermissions( aclPage( "ReadOnlyAclPage", "[{ALLOW view Alice}]" ) );
        assertThrows( UnsupportedOperationException.class, () -> acl.addEntry( Wiki.acls().entry() ) );
        assertThrows( UnsupportedOperationException.class, () -> acl.removeEntry( acl.aclEntries().nextElement() ) );

        // Nor through an entry: widening one would widen the cached ACL for everyone.
        final java.security.Permission edit = PermissionFactory.getPagePermission( "main:ReadOnlyAclPage", "edit" );
        final java.security.Permission view = PermissionFactory.getPagePermission( "main:ReadOnlyAclPage", "view" );
        for ( final AclEntry entry : java.util.List.of( acl.aclEntries().nextElement(), acl.getAclEntry( new WikiPrincipal( "Alice" ) ) ) ) {
            assertThrows( UnsupportedOperationException.class, () -> entry.addPermission( edit ) );
            assertThrows( UnsupportedOperationException.class, () -> entry.removePermission( view ) );
            assertThrows( UnsupportedOperationException.class, () -> entry.setPrincipal( new WikiPrincipal( "Mallory" ) ) );
        }
        assertEquals( 0, aclMgr.getPermissions( aclPage( "ReadOnlyAclPage", "[{ALLOW view Alice}]" ) )
                .findPrincipals( edit ).length, "nothing widened the cached ACL" );
        assertTrue( acl.getAclEntry( new WikiPrincipal( "Alice" ) ).checkPermission( view ), "the view answers like the entry" );
    }

    @Test
    void printAclPrintsAMultiActionRuleAsOneRulePerAction() throws Exception {
        final Acl acl = aclMgr.getPermissions( aclPage( "MultiActionPage", "[{ALLOW edit,view Alice}]\n[{ALLOW view Bob}]" ) );
        assertEquals( "[{ALLOW edit Alice}]\n[{ALLOW view Alice,Bob}]\n", DefaultAclManager.printAcl( acl ) );
    }

    @Test
    void setPermissionsPutsTheRulesAfterTheFrontmatter() throws Exception {
        final Page page = mock( Page.class );
        when( page.getName() ).thenReturn( "FrontPage" );
        when( page.getWiki() ).thenReturn( "" );
        when( pageMgr.getPageText( anyString(), anyInt() ) )
                .thenReturn( "---\ncanonical_id: 01ABC\ncluster: finance\n---\n[{ALLOW view Old}]\nBody.\n" );
        final Acl acl = Wiki.acls().acl();
        final AclEntry entry = Wiki.acls().entry();
        entry.setPrincipal( new WikiPrincipal( "New" ) );
        entry.addPermission( PermissionFactory.getPagePermission( "test:FrontPage", "view" ) );
        acl.addEntry( entry );

        aclMgr.setPermissions( page, acl );

        verify( pageMgr ).putPageText( eq( page ),
                eq( "---\ncanonical_id: 01ABC\ncluster: finance\n---\n[{ALLOW view New}]\n\nBody.\n" ) );
    }

    /** Page texts for the frontmatter-preservation check: name, stored text. */
    static java.util.stream.Stream< org.junit.jupiter.params.provider.Arguments > frontmatterShapes() {
        return java.util.stream.Stream.of(
                org.junit.jupiter.params.provider.Arguments.of( "LF frontmatter",
                        "---\ncanonical_id: 01ABC\ncluster: finance\ntags: [a, b]\nsummary: \"S: x\"\n---\n[{ALLOW view Old}]\nBody.\n" ),
                org.junit.jupiter.params.provider.Arguments.of( "CRLF frontmatter",
                        "---\r\ncanonical_id: 01ABC\r\ncluster: finance\r\n---\r\n[{ALLOW view Old}]\r\nBody.\r\n" ),
                org.junit.jupiter.params.provider.Arguments.of( "no frontmatter", "[{ALLOW view Old}]\nBody.\n" ),
                org.junit.jupiter.params.provider.Arguments.of( "only frontmatter", "---\ncanonical_id: 01ABC\ncluster: finance\n---\n" ),
                org.junit.jupiter.params.provider.Arguments.of( "only frontmatter, no line end after it",
                        "---\ncanonical_id: 01ABC\ncluster: finance\n---" ),
                org.junit.jupiter.params.provider.Arguments.of( "only frontmatter, CRLF, no line end after it",
                        "---\r\ncanonical_id: 01ABC\r\n---" ),
                org.junit.jupiter.params.provider.Arguments.of( "empty frontmatter block", "---\n---\nBody.\n" ) );
    }

    @org.junit.jupiter.params.ParameterizedTest( name = "{0}" )
    @org.junit.jupiter.params.provider.MethodSource( "frontmatterShapes" )
    void setPermissionsKeepsTheFrontmatterByteForByte( final String name, final String stored ) throws Exception {
        final Page page = mock( Page.class );
        when( page.getName() ).thenReturn( "ShapePage" );
        when( page.getWiki() ).thenReturn( "" );
        when( pageMgr.getPageText( anyString(), anyInt() ) ).thenReturn( stored );
        final Acl acl = Wiki.acls().acl();
        final AclEntry entry = Wiki.acls().entry();
        entry.setPrincipal( new WikiPrincipal( "New" ) );
        entry.addPermission( PermissionFactory.getPagePermission( "test:ShapePage", "view" ) );
        acl.addEntry( entry );

        aclMgr.setPermissions( page, acl );

        final ArgumentCaptor< String > written = ArgumentCaptor.forClass( String.class );
        verify( pageMgr ).putPageText( eq( page ), written.capture() );
        final String out = written.getValue();
        final int head = com.wikantik.api.frontmatter.FrontmatterParser.bodyOffset( stored );
        final String frontmatter = stored.substring( 0, head );
        assertTrue( out.startsWith( frontmatter ), "the frontmatter bytes come first, unchanged: " + out );
        assertEquals( com.wikantik.api.frontmatter.FrontmatterParser.parse( stored ).metadata(),
                com.wikantik.api.frontmatter.FrontmatterParser.parse( out ).metadata(), "the frontmatter reads the same: " + out );
        assertEquals( new AccessRules.Rules( java.util.List.of( "ALLOW view New" ), AccessRules.read( out ).spans() ),
                AccessRules.read( out ), "the new rule is the page's one rule, in its body: " + out );
        assertTrue( out.endsWith( stored.substring( head ).replace( "[{ALLOW view Old}]", "" ) ), "the body is kept: " + out );
    }

    @Test
    void setPermissionsWritesNothingWhenThePageCannotBeRead() throws Exception {
        final Page page = mock( Page.class );
        when( page.getName() ).thenReturn( "UnreadablePage" );
        when( pageMgr.getPageText( anyString(), anyInt() ) ).thenThrow( new ProviderException( "provider down" ) );
        assertThrows( WikiSecurityException.class, () -> aclMgr.setPermissions( page, Wiki.acls().acl() ) );
        verify( pageMgr, never() ).putPageText( any(), anyString() );
    }

    // -------------------------------------------------------------------------
    //  getPermissions — a locked page
    // -------------------------------------------------------------------------

    @Test
    void aDisputedShapeLocksThePage() throws Exception {
        final Page page = aclPage( "DenyPage", "[{DENY view Alice}]\n\nBody." );
        final Acl acl = aclMgr.getPermissions( page );
        assertEquals( 1, java.util.Collections.list( acl.aclEntries() ).size() );
        final Principal[] viewers = acl.findPrincipals( PermissionFactory.getPagePermission( "main:DenyPage", "view" ) );
        assertArrayEquals( new Principal[]{ DefaultAclManager.LOCKED_PRINCIPAL }, viewers );
        for ( final String action : new String[]{ "comment", "delete", "edit", "modify", "rename", "upload" } ) {
            assertArrayEquals( new Principal[]{ DefaultAclManager.LOCKED_PRINCIPAL },
                    acl.findPrincipals( PermissionFactory.getPagePermission( "main:DenyPage", action ) ), action );
        }
    }

    @Test
    void aRuleLineTheAclParserRefusesLocksThePage() throws Exception {
        final Page page = aclPage( "RefusedPage", "x" );
        final AccessRules.Rules rules = new AccessRules.Rules( java.util.List.of( "ALLOW nonsense Alice" ),
                java.util.List.of( new AccessRules.Span( 0, 1, AccessRules.Shape.CANONICAL ) ) );
        final Acl acl = aclMgr.aclOf( page, "x", rules );
        assertNotNull( acl.getAclEntry( DefaultAclManager.LOCKED_PRINCIPAL ), "fail closed, not open" );
    }

    @Test
    void aProviderFailureLocksThePageWithoutCachingIt() throws Exception {
        final Page page = aclPage( "BrokenPage", "x" );
        when( pageMgr.getPageText( eq( "BrokenPage" ), anyInt() ) ).thenThrow( new ProviderException( "provider down" ) );
        final Acl acl = aclMgr.getPermissions( page );
        assertNotNull( acl.getAclEntry( DefaultAclManager.LOCKED_PRINCIPAL ) );
        aclMgr.getPermissions( page );
        verify( pageMgr, times( 2 ) ).getPageText( eq( "BrokenPage" ), anyInt() );
    }

    @Test
    void aPrincipalThatCannotBeResolvedLocksThePageWithoutCachingIt() throws Exception {
        final Page page = aclPage( "ResolverDownPage", "[{ALLOW view Alice}]" );
        doThrow( new IllegalStateException( "user database down" ) ).when( authMgr ).resolvePrincipal( anyString() );
        assertNotNull( aclMgr.getPermissions( page ).getAclEntry( DefaultAclManager.LOCKED_PRINCIPAL ), "fail closed" );

        doAnswer( inv -> new WikiPrincipal( inv.getArgument( 0, String.class ) ) ).when( authMgr ).resolvePrincipal( anyString() );
        assertNotNull( aclMgr.getPermissions( page ).getAclEntry( new WikiPrincipal( "Alice" ) ), "not cached: read again" );
    }

    @Test
    void theLockedPrincipalIsNoLogin() throws Exception {
        assertTrue( DefaultAclManager.LOCKED_PRINCIPAL.getName().indexOf( '\u0000' ) >= 0 );
        assertNotEquals( DefaultAclManager.LOCKED_PRINCIPAL, new WikiPrincipal( DefaultAclManager.LOCKED_PRINCIPAL.getName() ) );
    }

    // -------------------------------------------------------------------------
    //  getPermissions — Attachment delegates to parent page
    // -------------------------------------------------------------------------

    @Test
    void getPermissionsDelegatesToParentPageForAttachment() throws Exception {
        final Page parentPage = aclPage( "ParentPage", "[{ALLOW view Alice}]" );
        when( pageMgr.getPage( eq( "ParentPage" ) ) ).thenReturn( parentPage );

        final Attachment att = mock( Attachment.class );
        when( att.getParentName() ).thenReturn( "ParentPage" );

        final Acl result = aclMgr.getPermissions( att );
        assertSame( aclMgr.getPermissions( parentPage ), result );
        assertNotNull( result.getAclEntry( new WikiPrincipal( "Alice" ) ) );
    }

    // -------------------------------------------------------------------------
    //  getPermissions — page text with two ACL rules
    // -------------------------------------------------------------------------

    @Test
    void getPermissionsExtractsMultipleAclRulesFromPageText() throws Exception {
        final Page page = mock( Page.class );
        when( page.getWiki() ).thenReturn( "main" );
        when( page.getName() ).thenReturn( "MultiAclPage" );

        // Make page.setAcl()/getAcl() persistent on the mock so parseAcl accumulates entries
        final Acl[] holder = { null };
        doAnswer( inv -> { holder[0] = inv.getArgument( 0 ); return null; } )
                .when( page ).setAcl( any( Acl.class ) );
        when( page.getAcl() ).thenAnswer( inv -> holder[0] );

        // Stub both the overloaded String+int form AND the default Page form
        when( pageMgr.getPageText( eq( "MultiAclPage" ), anyInt() ) )
                .thenReturn( "[{ALLOW view Alice}] some text [{ALLOW edit Bob}]" );
        when( pageMgr.getPageText( anyString(), anyInt() ) )
                .thenReturn( "[{ALLOW view Alice}] some text [{ALLOW edit Bob}]" );

        // extractAclFromPageText strips the [{ }] braces before handing the rule line to
        // parseAcl, so the principals arrive clean — see ruleLineOf().
        when( authMgr.resolvePrincipal( eq( "Alice" ) ) ).thenReturn( new WikiPrincipal( "Alice" ) );
        when( authMgr.resolvePrincipal( eq( "Bob" ) ) ).thenReturn( new WikiPrincipal( "Bob" ) );

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
    //  getPermissions — principal names must not absorb the rule's closing "}]"
    // -------------------------------------------------------------------------

    /**
     * The page-text fast path used to hand {@code parseAcl} the whole regex match
     * ({@code [{ALLOW view Alice}]}), so its {@code StringTokenizer} folded the
     * trailing {@code "}]"} into the last principal name. The resulting entry
     * ("Alice}]") can never match a real principal, so the ACL is non-empty but
     * grants nobody — a live enforcement defect.
     *
     * <p>These two tests use an <em>echoing</em> resolver (whatever name the
     * manager asks for comes back as that principal) instead of stubbing the
     * mangled name, so the assertion sees the name production code actually
     * resolved.
     */
    @Test
    void getPermissionsResolvesSolePrincipalWithoutTheRuleTerminator() throws Exception {
        final Page page = aclPage( "SoleAclPage", "[{ALLOW view Alice}]" );

        final Acl result = aclMgr.getPermissions( page );

        final Principal[] viewers = result.findPrincipals(
                PermissionFactory.getPagePermission( "main:SoleAclPage", "view" ) );
        assertEquals( 1, viewers.length, "exactly one principal should hold view" );
        assertEquals( "Alice", viewers[ 0 ].getName(),
                "the principal name must not absorb the rule's closing \"}]\"" );
    }

    @Test
    void getPermissionsResolvesLastOfSeveralCommaSeparatedPrincipals() throws Exception {
        final Page page = aclPage( "MultiPrincipalPage", "[{ALLOW view Alice,Bob}]" );

        final Acl result = aclMgr.getPermissions( page );

        final Principal[] viewers = result.findPrincipals(
                PermissionFactory.getPagePermission( "main:MultiPrincipalPage", "view" ) );
        final java.util.List< String > names = Arrays.stream( viewers ).map( Principal::getName ).sorted().toList();
        assertEquals( java.util.List.of( "Alice", "Bob" ), names,
                "the LAST principal in a comma-separated rule must not keep the \"}]\"" );
    }

    /**
     * Builds a mock page whose {@code setAcl}/{@code getAcl} round-trip (so parseAcl
     * accumulates entries) and whose text is {@code pageText}, with an echoing
     * principal resolver installed on the shared {@link #authMgr} mock.
     */
    private Page aclPage( final String name, final String pageText ) throws Exception {
        final Page page = mock( Page.class );
        when( page.getWiki() ).thenReturn( "main" );
        when( page.getName() ).thenReturn( name );

        final Acl[] holder = { null };
        doAnswer( inv -> { holder[ 0 ] = inv.getArgument( 0 ); return null; } )
                .when( page ).setAcl( any( Acl.class ) );
        when( page.getAcl() ).thenAnswer( inv -> holder[ 0 ] );

        when( pageMgr.getPageText( eq( name ), anyInt() ) ).thenReturn( pageText );
        when( pageMgr.getPageText( anyString(), anyInt() ) ).thenReturn( pageText );
        when( authMgr.resolvePrincipal( anyString() ) )
                .thenAnswer( inv -> new WikiPrincipal( inv.getArgument( 0, String.class ) ) );
        return page;
    }

    // -------------------------------------------------------------------------
    //  getPermissions — empty page text → empty ACL
    // -------------------------------------------------------------------------

    @Test
    void getPermissionsReturnsEmptyAclForEmptyPageText() throws Exception {
        final Page page = mock( Page.class );
        when( page.getAcl() ).thenReturn( null );
        when( page.getName() ).thenReturn( "EmptyPage" );
        when( pageMgr.getPageText( eq( "EmptyPage" ), anyInt() ) ).thenReturn( "" );
        when( pageMgr.getPageText( anyString(), anyInt() ) ).thenReturn( "" );

        final Acl result = aclMgr.getPermissions( page );
        assertNotNull( result );
        assertTrue( result.isEmpty() );
    }

    // -------------------------------------------------------------------------
    //  setPermissions — writes new ACL text
    // -------------------------------------------------------------------------

    @Test
    void setPermissionsWritesNewAclText() throws Exception {
        final Page page = mock( Page.class );
        when( page.getName() ).thenReturn( "LocklessPage" );
        when( page.getWiki() ).thenReturn( "" );

        // Stub the text read (by name and by any name)
        when( pageMgr.getPageText( eq( "LocklessPage" ), anyInt() ) )
                .thenReturn( "Some content without any ACL rules." );
        when( pageMgr.getPageText( anyString(), anyInt() ) )
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
    //  setPermissions — strips the old ACL tag before writing
    // -------------------------------------------------------------------------

    @Test
    void setPermissionsReplacesOldAclTag() throws Exception {
        final Page page = mock( Page.class );
        when( page.getName() ).thenReturn( "LockedPage" );
        when( page.getWiki() ).thenReturn( "" );

        when( pageMgr.getPageText( eq( "LockedPage" ), anyInt() ) )
                .thenReturn( "Content. [{ALLOW view OldUser}]" );
        when( pageMgr.getPageText( anyString(), anyInt() ) )
                .thenReturn( "Content. [{ALLOW view OldUser}]" );

        final Acl acl = Wiki.acls().acl();
        final AclEntry entry = Wiki.acls().entry();
        entry.setPrincipal( new WikiPrincipal( "NewUser" ) );
        entry.addPermission( PermissionFactory.getPagePermission( "test:LockedPage", "edit" ) );
        acl.addEntry( entry );
        when( page.getAcl() ).thenReturn( acl );

        aclMgr.setPermissions( page, acl );

        // And the page should have been saved with the old ACL tag stripped
        final ArgumentCaptor<String> textCaptor = ArgumentCaptor.forClass( String.class );
        verify( pageMgr ).putPageText( eq( page ), textCaptor.capture() );
        assertFalse( textCaptor.getValue().contains( "OldUser" ),
                "Saved text should not contain the old ACL entry for OldUser" );
    }

    @Test
    void setPermissionsRefusesAPageWhoseRulesCannotBeRead() throws Exception {
        final Page page = mock( Page.class );
        when( page.getName() ).thenReturn( "DisputedPage" );
        when( page.getWiki() ).thenReturn( "" );
        when( pageMgr.getPageText( anyString(), anyInt() ) ).thenReturn( "[{DENY view OldUser}]\nContent." );

        final Acl acl = Wiki.acls().acl();
        final AclEntry entry = Wiki.acls().entry();
        entry.setPrincipal( new WikiPrincipal( "NewUser" ) );
        entry.addPermission( PermissionFactory.getPagePermission( "test:DisputedPage", "edit" ) );
        acl.addEntry( entry );

        assertThrows( WikiSecurityException.class, () -> aclMgr.setPermissions( page, acl ) );
        verify( pageMgr, never() ).putPageText( any(), anyString() );
    }

    @Test
    void setPermissionsRefusesALockedAcl() throws Exception {
        final Page locked = aclPage( "LockedAclPage", "[{DENY view x}]" );
        final Acl acl = aclMgr.getPermissions( locked );
        assertThrows( WikiSecurityException.class, () -> aclMgr.setPermissions( locked, acl ) );
    }

    @Test
    void setPermissionsKeepsADocumentationExampleInCode() throws Exception {
        final Page page = mock( Page.class );
        when( page.getName() ).thenReturn( "DocPage" );
        when( page.getWiki() ).thenReturn( "" );
        when( pageMgr.getPageText( anyString(), anyInt() ) ).thenReturn( "[{ALLOW view Old}]()\nWrite `[{ALLOW edit Bob}]` to restrict." );

        final Acl acl = Wiki.acls().acl();
        final AclEntry entry = Wiki.acls().entry();
        entry.setPrincipal( new WikiPrincipal( "New" ) );
        entry.addPermission( PermissionFactory.getPagePermission( "test:DocPage", "view" ) );
        acl.addEntry( entry );

        aclMgr.setPermissions( page, acl );

        verify( pageMgr ).putPageText( eq( page ), eq( "[{ALLOW view New}]\n\nWrite `[{ALLOW edit Bob}]` to restrict." ) );
    }

    /** An escaped rule is literal text (#104 §5.3): a rewrite replaces the page's rules and keeps it. */
    @Test
    void setPermissionsKeepsAnEscapedExampleAndReplacesAWrappedRule() throws Exception {
        final Page page = mock( Page.class );
        when( page.getName() ).thenReturn( "EscapedDocPage" );
        when( page.getWiki() ).thenReturn( "" );
        when( pageMgr.getPageText( anyString(), anyInt() ) )
                .thenReturn( "[{allow view Old,\n Older}]\nWrite \\[{ALLOW edit Bob}] to restrict." );

        final Acl acl = Wiki.acls().acl();
        final AclEntry entry = Wiki.acls().entry();
        entry.setPrincipal( new WikiPrincipal( "New" ) );
        entry.addPermission( PermissionFactory.getPagePermission( "test:EscapedDocPage", "view" ) );
        acl.addEntry( entry );

        aclMgr.setPermissions( page, acl );

        verify( pageMgr ).putPageText( eq( page ), eq( "[{ALLOW view New}]\n\nWrite \\[{ALLOW edit Bob}] to restrict." ) );
    }

    // -------------------------------------------------------------------------
    //  printAcl — empty ACL produces empty string
    // -------------------------------------------------------------------------

    @Test
    void printAclReturnsEmptyStringForEmptyAcl() throws Exception {
        final Acl emptyAcl = Wiki.acls().acl();
        assertEquals( "", DefaultAclManager.printAcl( emptyAcl ) );
    }

    // -------------------------------------------------------------------------
    //  printAcl — single entry with one permission
    // -------------------------------------------------------------------------

    @Test
    void printAclFormatsCorrectlyForSingleEntry() throws Exception {
        final Acl acl = Wiki.acls().acl();
        final AclEntry entry = Wiki.acls().entry();
        entry.setPrincipal( new WikiPrincipal( "Zara" ) );
        entry.addPermission( PermissionFactory.getPagePermission( "test:X", "view" ) );
        acl.addEntry( entry );

        final String text = DefaultAclManager.printAcl( acl );
        assertEquals( "[{ALLOW view Zara}]\n", text );
    }

}
