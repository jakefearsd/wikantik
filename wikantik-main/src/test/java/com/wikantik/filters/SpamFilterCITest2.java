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
package com.wikantik.filters;

import com.wikantik.MockEngineBuilder;
import com.wikantik.api.core.Context;
import com.wikantik.api.core.Engine;
import com.wikantik.api.core.Page;
import com.wikantik.api.core.Session;
import com.wikantik.api.exceptions.RedirectException;
import com.wikantik.api.providers.WikiProvider;
import com.wikantik.api.managers.AttachmentManager;
import com.wikantik.auth.user.UserProfile;
import com.wikantik.api.managers.PageManager;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpSession;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.security.Principal;
import java.util.Properties;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;


/**
 * Additional unit tests for {@link SpamFilter} covering paths not reached by
 * {@link SpamFilterTest}.  Uses the package-private constructor to inject mocks.
 */
class SpamFilterCITest2 {

    private PageManager pageManager;
    private AttachmentManager attachmentManager;
    private SpamFilter filter;
    private Context context;
    private Page page;
    private HttpServletRequest request;
    private Session session;
    private Engine engine;

    @BeforeEach
    void setUp() {
        pageManager = mock( PageManager.class );
        attachmentManager = mock( AttachmentManager.class );

        filter = new SpamFilter( pageManager, attachmentManager );

        engine = MockEngineBuilder.engine()
                .with( PageManager.class, pageManager )
                .with( AttachmentManager.class, attachmentManager )
                .build();

        page = mock( Page.class );
        when( page.getName() ).thenReturn( "TestPage" );
        when( page.getVersion() ).thenReturn( 1 );

        request = mock( HttpServletRequest.class );
        when( request.getRemoteAddr() ).thenReturn( "10.0.0.1" );

        final HttpSession httpSession = mock( HttpSession.class );
        when( request.getSession() ).thenReturn( httpSession );

        session = mock( Session.class );
        when( session.getRoles() ).thenReturn( new Principal[0] );
        when( session.isAuthenticated() ).thenReturn( false );

        context = mock( Context.class );
        when( context.getPage() ).thenReturn( page );
        when( context.getEngine() ).thenReturn( engine );
        when( context.getHttpRequest() ).thenReturn( request );
        when( context.getWikiSession() ).thenReturn( session );
        when( context.hasAdminPermissions() ).thenReturn( false );
        when( context.getURL( anyString(), anyString() ) ).thenReturn( "http://localhost/RejectedMessage" );
    }

    private void initFilter( final Properties extra ) {
        final Properties props = new Properties();
        props.setProperty( SpamFilter.PROP_FILTERSTRATEGY, SpamFilter.STRATEGY_EAGER );
        if( extra != null ) {
            props.putAll( extra );
        }
        filter.initialize( engine, props );
    }

    // ---- AllowedGroups bypass ----

    @Test
    void preSave_skipsChecksForUserInAllowedGroup() throws Exception {
        final Properties props = new Properties();
        props.setProperty( SpamFilter.PROP_ALLOWED_GROUPS, "Editors,Trusted" );
        initFilter( props );

        // Give session a role that matches one of the allowed groups
        final Principal editorRole = mock( Principal.class );
        when( editorRole.getName() ).thenReturn( "Editors" );
        when( session.getRoles() ).thenReturn( new Principal[]{ editorRole } );

        when( page.getName() ).thenReturn( "A".repeat( 200 ) ); // would fail page-name check
        when( pageManager.getPureText( anyString(), eq( WikiProvider.LATEST_VERSION ) ) ).thenReturn( "" );

        final String result = filter.preSave( context, "editor content" );
        assertEquals( "editor content", result,
                "Users in an allowed group should bypass all spam filter checks" );
    }

    // ---- Change-note included in diff text ----

    @Test
    void preSave_includesChangeNoteInSpamCheck() throws Exception {
        initFilter( null );

        final Page spamPage = mock( Page.class );
        when( spamPage.getName() ).thenReturn( "SpamFilterWordList" );
        when( spamPage.getLastModified() ).thenReturn( new java.util.Date() );
        when( spamPage.getAttribute( "spamwords" ) ).thenReturn( "spam-in-note" );
        when( pageManager.getPage( "SpamFilterWordList" ) ).thenReturn( spamPage );

        when( page.getName() ).thenReturn( "TestPage" );
        when( page.getAttribute( Page.CHANGENOTE ) ).thenReturn( "spam-in-note content here" );
        when( pageManager.getPureText( anyString(), eq( WikiProvider.LATEST_VERSION ) ) ).thenReturn( "old text" );

        // The spam word is in the change note, not the page content, but the filter
        // should still catch it because getChange() appends the changenote.
        assertThrows( RedirectException.class,
                () -> filter.preSave( context, "clean page content" ),
                "Spam in change note should be detected" );
    }

    // ---- Author included in diff text ----

    @Test
    void preSave_includesAuthorInSpamCheck() throws Exception {
        initFilter( null );

        final Page spamPage = mock( Page.class );
        when( spamPage.getName() ).thenReturn( "SpamFilterWordList" );
        when( spamPage.getLastModified() ).thenReturn( new java.util.Date() );
        when( spamPage.getAttribute( "spamwords" ) ).thenReturn( "spamauthor" );
        when( pageManager.getPage( "SpamFilterWordList" ) ).thenReturn( spamPage );

        when( page.getName() ).thenReturn( "TestPage" );
        when( page.getAuthor() ).thenReturn( "spamauthor" );
        when( pageManager.getPureText( anyString(), eq( WikiProvider.LATEST_VERSION ) ) ).thenReturn( "old text" );

        assertThrows( RedirectException.class,
                () -> filter.preSave( context, "more content here" ),
                "Spam in author field should be detected" );
    }

    // ---- IP pattern check passes when IP does not match any pattern ----

    @Test
    void preSave_acceptsRequestWhenIPDoesNotMatchBanPattern() throws Exception {
        initFilter( null );

        // Set up a ban pattern that does NOT match the request IP (10.0.0.1)
        final Page ipPage = mock( Page.class );
        when( ipPage.getName() ).thenReturn( "SpamFilterIPList" );
        when( ipPage.getLastModified() ).thenReturn( new java.util.Date() );
        when( ipPage.getAttribute( "ips" ) ).thenReturn( "192\\.168\\.99\\.99" );
        when( pageManager.getPage( "SpamFilterIPList" ) ).thenReturn( ipPage );

        when( page.getName() ).thenReturn( "TestPage" );
        when( pageManager.getPureText( anyString(), eq( WikiProvider.LATEST_VERSION ) ) ).thenReturn( "" );

        // The request IP is 10.0.0.1 which does not match 192.168.99.99
        final String result = filter.preSave( context, "content" );
        assertEquals( "content", result,
                "Requests from IPs not in the ban pattern should be accepted" );
    }

    // ---- isValidUserProfile with null patterns (before first preSave) ----

    @Test
    void isValidUserProfile_returnsTrueWhenNoPatternsLoaded() {
        filter.initialize( engine, new Properties() );

        final UserProfile profile = mock( UserProfile.class );
        when( profile.getEmail() ).thenReturn( "anyone@example.com" );
        when( profile.getFullname() ).thenReturn( "Normal User" );
        when( profile.getLoginName() ).thenReturn( "normaluser" );

        // No spam patterns loaded → should accept
        assertTrue( filter.isValidUserProfile( context, profile ),
                "Profile should be accepted when no spam patterns are loaded" );
    }

    // ---- isValidUserProfile with loginName matching spam ----

    @Test
    void isValidUserProfile_rejectsLoginNameMatchingSpam() throws Exception {
        initFilter( null );

        final Page spamPage = mock( Page.class );
        when( spamPage.getName() ).thenReturn( "SpamFilterWordList" );
        when( spamPage.getLastModified() ).thenReturn( new java.util.Date() );
        when( spamPage.getAttribute( "spamwords" ) ).thenReturn( "spammer123" );
        when( pageManager.getPage( "SpamFilterWordList" ) ).thenReturn( spamPage );

        // Prime the blacklists
        when( page.getName() ).thenReturn( "TempPage" );
        when( pageManager.getPureText( anyString(), eq( WikiProvider.LATEST_VERSION ) ) ).thenReturn( "" );
        filter.preSave( context, "prime" );

        final UserProfile profile = mock( UserProfile.class );
        when( profile.getEmail() ).thenReturn( "ok@example.com" );
        when( profile.getFullname() ).thenReturn( "Normal Name" );
        when( profile.getLoginName() ).thenReturn( "spammer123" );

        assertFalse( filter.isValidUserProfile( context, profile ),
                "User profile with login name matching a spam pattern should be rejected" );
    }

    // ---- getSpamHash with non-null lastModified ----

    @Test
    void getSpamHash_differentiatesDifferentIPs() {
        final Page hashPage = mock( Page.class );
        when( hashPage.getLastModified() ).thenReturn( new java.util.Date( 5000L ) );

        final HttpServletRequest req1 = mock( HttpServletRequest.class );
        when( req1.getRemoteAddr() ).thenReturn( "192.168.1.1" );

        final HttpServletRequest req2 = mock( HttpServletRequest.class );
        when( req2.getRemoteAddr() ).thenReturn( "192.168.1.2" );

        final String hash1 = SpamFilter.getSpamHash( hashPage, req1 );
        final String hash2 = SpamFilter.getSpamHash( hashPage, req2 );

        assertNotEquals( hash1, hash2,
                "Different remote addresses should produce different spam hashes" );
    }

    // ---- Score strategy: null score variable path ----

    @Test
    void preSave_scoreStrategyDoesNotThrowWhenScoreIsNull() throws Exception {
        final Properties props = new Properties();
        props.setProperty( SpamFilter.PROP_FILTERSTRATEGY, SpamFilter.STRATEGY_SCORE );
        initFilter( props );

        when( page.getName() ).thenReturn( "ShortPage" );
        when( pageManager.getPureText( anyString(), eq( WikiProvider.LATEST_VERSION ) ) ).thenReturn( "" );
        // context.getVariable returns null → score is null → no redirect
        when( context.getVariable( anyString() ) ).thenReturn( null );

        final String result = filter.preSave( context, "clean content" );
        assertEquals( "clean content", result,
                "Score strategy with null score should not reject content" );
    }

    // ---- Malformed spam pattern (PatternSyntaxException) ----

    @Test
    void preSave_handlesInvalidRegexPatternGracefully() throws Exception {
        initFilter( null );

        // Set up an invalid regex pattern on the spam word page
        final Page spamPage = mock( Page.class );
        when( spamPage.getName() ).thenReturn( "SpamFilterWordList" );
        when( spamPage.getLastModified() ).thenReturn( new java.util.Date() );
        when( spamPage.getAttribute( "spamwords" ) ).thenReturn( "[invalid-regex" ); // bad regex
        when( pageManager.getPage( "SpamFilterWordList" ) ).thenReturn( spamPage );

        when( page.getName() ).thenReturn( "TestPage" );
        when( pageManager.getPureText( anyString(), eq( WikiProvider.LATEST_VERSION ) ) ).thenReturn( "" );

        // Should not throw — invalid patterns are skipped with a debug log
        final String result = filter.preSave( context, "valid content" );
        assertEquals( "valid content", result,
                "Invalid regex patterns should be skipped gracefully" );
    }

    // ---- URL check with exactly maxUrls urls (boundary) ----

    @Test
    void preSave_acceptsContentWithExactlyMaxUrls() throws Exception {
        final Properties props = new Properties();
        props.setProperty( SpamFilter.PROP_MAXURLS, "3" );
        initFilter( props );

        when( page.getName() ).thenReturn( "TestPage" );
        when( pageManager.getPureText( anyString(), eq( WikiProvider.LATEST_VERSION ) ) ).thenReturn( "" );

        // Exactly 3 URLs — should be accepted (limit is >maxUrls, not >=)
        final String content = "See http://a.com and http://b.com and http://c.com here";
        final String result = filter.preSave( context, content );
        assertEquals( content, result,
                "Content with exactly maxUrls URLs should be accepted (limit is strictly greater)" );
    }

    // ---- refreshBlacklists: attachment blacklist path ----

    @Test
    void refreshBlacklists_handlesBlacklistAttachmentWithPatterns() throws Exception {
        initFilter( null );

        // Set up a blacklist attachment
        final com.wikantik.api.core.Attachment blacklistAtt = mock( com.wikantik.api.core.Attachment.class );
        when( blacklistAtt.getLastModified() ).thenReturn( new java.util.Date() );
        when( attachmentManager.getAttachmentInfo( eq( context ), anyString() ) )
                .thenReturn( blacklistAtt );
        // Return a simple blacklist with one entry
        final java.io.InputStream blacklistStream = new java.io.ByteArrayInputStream(
                "# comment\n\nbuy-from-blacklist\n".getBytes( java.nio.charset.StandardCharsets.UTF_8 ) );
        when( attachmentManager.getAttachmentStream( blacklistAtt ) ).thenReturn( blacklistStream );

        when( page.getName() ).thenReturn( "TestPage" );
        when( pageManager.getPureText( anyString(), eq( WikiProvider.LATEST_VERSION ) ) ).thenReturn( "" );

        // Should reject content matching the blacklist pattern
        assertThrows( RedirectException.class,
                () -> filter.preSave( context, "check out buy-from-blacklist deals" ),
                "Content matching a blacklist attachment pattern should be rejected" );
    }

}
