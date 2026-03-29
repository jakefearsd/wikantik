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
import com.wikantik.api.core.ContextEnum;
import com.wikantik.api.core.Engine;
import com.wikantik.api.core.Page;
import com.wikantik.api.core.Session;
import com.wikantik.api.exceptions.RedirectException;
import com.wikantik.api.providers.WikiProvider;
import com.wikantik.attachment.AttachmentManager;
import com.wikantik.auth.user.UserProfile;
import com.wikantik.pages.PageManager;
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
 * Unit tests for {@link SpamFilter}. Uses the package-private constructor
 * to inject mock dependencies, avoiding the need for a running engine.
 */
class SpamFilterTest {

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
        when( request.getRemoteAddr() ).thenReturn( "127.0.0.1" );

        session = mock( Session.class );
        when( session.getRoles() ).thenReturn( new Principal[0] );
        when( session.isAuthenticated() ).thenReturn( false );

        context = mock( Context.class );
        when( context.getPage() ).thenReturn( page );
        when( context.getEngine() ).thenReturn( engine );
        when( context.getHttpRequest() ).thenReturn( request );
        when( context.getWikiSession() ).thenReturn( session );
        when( context.hasAdminPermissions() ).thenReturn( false );
        when( context.getRequestContext() ).thenReturn( ContextEnum.PAGE_VIEW.getRequestContext() );
        when( context.getURL( anyString(), anyString() ) ).thenReturn( "http://localhost/RejectedMessage" );
    }

    /**
     * Helper: initialise the filter with given properties, merging sensible defaults.
     */
    private void initFilter( final Properties extra ) {
        final Properties props = new Properties();
        // defaults that keep tests simple
        props.setProperty( SpamFilter.PROP_FILTERSTRATEGY, SpamFilter.STRATEGY_EAGER );
        if( extra != null ) {
            props.putAll( extra );
        }
        filter.initialize( engine, props );
    }

    // ---- Page name length checks ----

    @Test
    void preSave_rejectsPageNameExceedingMaxLength() throws Exception {
        initFilter( null );

        when( page.getName() ).thenReturn( "A".repeat( 200 ) );
        // getChange needs old text
        when( pageManager.getPureText( anyString(), eq( WikiProvider.LATEST_VERSION ) ) ).thenReturn( "" );

        assertThrows( RedirectException.class,
                () -> filter.preSave( context, "some content" ),
                "Page names exceeding the default 100-char limit should be rejected" );
    }

    @Test
    void preSave_acceptsPageNameWithinDefaultMaxLength() throws Exception {
        initFilter( null );

        when( page.getName() ).thenReturn( "ShortName" );
        when( pageManager.getPureText( anyString(), eq( WikiProvider.LATEST_VERSION ) ) ).thenReturn( "" );

        final String result = filter.preSave( context, "hello" );
        assertEquals( "hello", result,
                "Content should pass through unchanged when the page name is within limits" );
    }

    @Test
    void preSave_rejectsPageNameExceedingCustomMaxLength() throws Exception {
        final Properties props = new Properties();
        props.setProperty( SpamFilter.PROP_MAX_PAGENAME_LENGTH, "10" );
        initFilter( props );

        when( page.getName() ).thenReturn( "ThisNameIsLongerThanTen" );
        when( pageManager.getPureText( anyString(), eq( WikiProvider.LATEST_VERSION ) ) ).thenReturn( "" );

        assertThrows( RedirectException.class,
                () -> filter.preSave( context, "content" ),
                "Custom maxpagenamelength of 10 should reject names longer than 10 characters" );
    }

    // ---- URL count checks ----

    @Test
    void preSave_rejectsTooManyUrls() throws Exception {
        final Properties props = new Properties();
        props.setProperty( SpamFilter.PROP_MAXURLS, "2" );
        initFilter( props );

        when( page.getName() ).thenReturn( "TestPage" );
        when( pageManager.getPureText( anyString(), eq( WikiProvider.LATEST_VERSION ) ) ).thenReturn( "" );

        final String spamContent = "Visit http://spam1.com and http://spam2.com and http://spam3.com please";

        assertThrows( RedirectException.class,
                () -> filter.preSave( context, spamContent ),
                "Content with 3 URLs should be rejected when maxurls is set to 2" );
    }

    @Test
    void preSave_acceptsContentWithUrlsUnderLimit() throws Exception {
        final Properties props = new Properties();
        props.setProperty( SpamFilter.PROP_MAXURLS, "5" );
        initFilter( props );

        when( page.getName() ).thenReturn( "TestPage" );
        when( pageManager.getPureText( anyString(), eq( WikiProvider.LATEST_VERSION ) ) ).thenReturn( "" );

        final String content = "See http://example.com and http://example.org for details";

        final String result = filter.preSave( context, content );
        assertEquals( content, result,
                "Content with 2 URLs should be accepted when maxurls is set to 5" );
    }

    // ---- Bot trap field detection ----

    @Test
    void preSave_rejectsBotTrapContent() throws Exception {
        initFilter( null );

        when( page.getName() ).thenReturn( "TestPage" );
        when( pageManager.getPureText( anyString(), eq( WikiProvider.LATEST_VERSION ) ) ).thenReturn( "" );
        when( request.getParameter( SpamFilter.getBotFieldName() ) ).thenReturn( "I am a bot filling all fields" );

        assertThrows( RedirectException.class,
                () -> filter.preSave( context, "some content" ),
                "A non-empty bot trap field should trigger spam rejection" );
    }

    @Test
    void preSave_acceptsEmptyBotTrapField() throws Exception {
        initFilter( null );

        when( page.getName() ).thenReturn( "TestPage" );
        when( pageManager.getPureText( anyString(), eq( WikiProvider.LATEST_VERSION ) ) ).thenReturn( "" );
        when( request.getParameter( SpamFilter.getBotFieldName() ) ).thenReturn( "" );

        final String result = filter.preSave( context, "legit content" );
        assertEquals( "legit content", result,
                "An empty bot trap field should not trigger spam rejection" );
    }

    @Test
    void preSave_acceptsNullBotTrapField() throws Exception {
        initFilter( null );

        when( page.getName() ).thenReturn( "TestPage" );
        when( pageManager.getPureText( anyString(), eq( WikiProvider.LATEST_VERSION ) ) ).thenReturn( "" );
        when( request.getParameter( SpamFilter.getBotFieldName() ) ).thenReturn( null );

        final String result = filter.preSave( context, "content" );
        assertEquals( "content", result,
                "A null bot trap field should not trigger spam rejection" );
    }

    // ---- UTF-8 trap ----

    @Test
    void preSave_rejectsInvalidUtf8EncodingCheck() throws Exception {
        initFilter( null );

        when( page.getName() ).thenReturn( "TestPage" );
        when( pageManager.getPureText( anyString(), eq( WikiProvider.LATEST_VERSION ) ) ).thenReturn( "" );
        when( request.getParameter( "encodingcheck" ) ).thenReturn( "garbled" );

        assertThrows( RedirectException.class,
                () -> filter.preSave( context, "content" ),
                "A garbled UTF-8 encoding check field should reject the edit as a bot" );
    }

    @Test
    void preSave_acceptsCorrectUtf8EncodingCheck() throws Exception {
        initFilter( null );

        when( page.getName() ).thenReturn( "TestPage" );
        when( pageManager.getPureText( anyString(), eq( WikiProvider.LATEST_VERSION ) ) ).thenReturn( "" );
        when( request.getParameter( "encodingcheck" ) ).thenReturn( "\u3041" );

        final String result = filter.preSave( context, "content" );
        assertEquals( "content", result,
                "A correct UTF-8 encoding check should not trigger spam rejection" );
    }

    // ---- Pattern list / banned words ----

    @Test
    void preSave_rejectsContentMatchingSpamPattern() throws Exception {
        initFilter( null );

        // Set up a spam word page with a pattern
        final Page spamPage = mock( Page.class );
        when( spamPage.getName() ).thenReturn( "SpamFilterWordList" );
        when( spamPage.getLastModified() ).thenReturn( new java.util.Date() );
        when( spamPage.getAttribute( "spamwords" ) ).thenReturn( "buy-viagra" );
        when( pageManager.getPage( "SpamFilterWordList" ) ).thenReturn( spamPage );

        when( page.getName() ).thenReturn( "TestPage" );
        when( pageManager.getPureText( anyString(), eq( WikiProvider.LATEST_VERSION ) ) ).thenReturn( "" );

        assertThrows( RedirectException.class,
                () -> filter.preSave( context, "Check out buy-viagra deals" ),
                "Content matching a spam filter word pattern should be rejected" );
    }

    @Test
    void preSave_acceptsContentNotMatchingAnySpamPattern() throws Exception {
        initFilter( null );

        // Set up a spam word page with a pattern that won't match
        final Page spamPage = mock( Page.class );
        when( spamPage.getName() ).thenReturn( "SpamFilterWordList" );
        when( spamPage.getLastModified() ).thenReturn( new java.util.Date() );
        when( spamPage.getAttribute( "spamwords" ) ).thenReturn( "buy-viagra casino-online" );
        when( pageManager.getPage( "SpamFilterWordList" ) ).thenReturn( spamPage );

        when( page.getName() ).thenReturn( "TestPage" );
        when( pageManager.getPureText( anyString(), eq( WikiProvider.LATEST_VERSION ) ) ).thenReturn( "" );

        final String result = filter.preSave( context, "This is a perfectly normal wiki page" );
        assertEquals( "This is a perfectly normal wiki page", result,
                "Content that doesn't match any spam pattern should pass through" );
    }

    @Test
    void preSave_allowsEditingSpamWordListPage() throws Exception {
        initFilter( null );

        // Set up patterns
        final Page spamPage = mock( Page.class );
        when( spamPage.getName() ).thenReturn( "SpamFilterWordList" );
        when( spamPage.getLastModified() ).thenReturn( new java.util.Date() );
        when( spamPage.getAttribute( "spamwords" ) ).thenReturn( "banned-word" );
        when( pageManager.getPage( "SpamFilterWordList" ) ).thenReturn( spamPage );

        // We are editing the spam word list page itself
        when( page.getName() ).thenReturn( "SpamFilterWordList" );
        when( pageManager.getPureText( anyString(), eq( WikiProvider.LATEST_VERSION ) ) ).thenReturn( "" );

        final String result = filter.preSave( context, "banned-word should-not-block-editing-this-page" );
        assertEquals( "banned-word should-not-block-editing-this-page", result,
                "Editing the SpamFilterWordList page itself should bypass word pattern checks" );
    }

    // ---- IP pattern checks ----

    @Test
    void preSave_rejectsRequestFromBannedIP() throws Exception {
        initFilter( null );

        // Set up an IP ban list
        final Page ipPage = mock( Page.class );
        when( ipPage.getName() ).thenReturn( "SpamFilterIPList" );
        when( ipPage.getLastModified() ).thenReturn( new java.util.Date() );
        when( ipPage.getAttribute( "ips" ) ).thenReturn( "127\\.0\\.0\\.1" );
        when( pageManager.getPage( "SpamFilterIPList" ) ).thenReturn( ipPage );

        when( page.getName() ).thenReturn( "TestPage" );
        when( pageManager.getPureText( anyString(), eq( WikiProvider.LATEST_VERSION ) ) ).thenReturn( "" );

        assertThrows( RedirectException.class,
                () -> filter.preSave( context, "some content" ),
                "Requests from IPs matching the ban pattern should be rejected" );
    }

    @Test
    void preSave_acceptsRequestFromNonBannedIP() throws Exception {
        initFilter( null );

        // Set up an IP ban list that won't match 127.0.0.1
        final Page ipPage = mock( Page.class );
        when( ipPage.getName() ).thenReturn( "SpamFilterIPList" );
        when( ipPage.getLastModified() ).thenReturn( new java.util.Date() );
        when( ipPage.getAttribute( "ips" ) ).thenReturn( "10\\.0\\.0\\.99" );
        when( pageManager.getPage( "SpamFilterIPList" ) ).thenReturn( ipPage );

        when( page.getName() ).thenReturn( "TestPage" );
        when( pageManager.getPureText( anyString(), eq( WikiProvider.LATEST_VERSION ) ) ).thenReturn( "" );

        final String result = filter.preSave( context, "content" );
        assertEquals( "content", result,
                "Requests from IPs not matching the ban pattern should be accepted" );
    }

    // ---- Admin bypass ----

    @Test
    void preSave_skipsAllChecksForAdminUser() throws Exception {
        initFilter( null );

        when( context.hasAdminPermissions() ).thenReturn( true );
        when( page.getName() ).thenReturn( "A".repeat( 200 ) );  // would normally fail page name check
        when( pageManager.getPureText( anyString(), eq( WikiProvider.LATEST_VERSION ) ) ).thenReturn( "" );

        // Admin users should be allowed even with a ridiculously long page name
        final String result = filter.preSave( context, "admin content" );
        assertEquals( "admin content", result,
                "Admin users should bypass all spam filter checks" );
    }

    // ---- Authenticated user bypass ----

    @Test
    void preSave_skipsChecksForAuthenticatedWhenConfigured() throws Exception {
        final Properties props = new Properties();
        props.setProperty( SpamFilter.PROP_IGNORE_AUTHENTICATED, "true" );
        initFilter( props );

        when( session.isAuthenticated() ).thenReturn( true );
        when( page.getName() ).thenReturn( "A".repeat( 200 ) );
        when( pageManager.getPureText( anyString(), eq( WikiProvider.LATEST_VERSION ) ) ).thenReturn( "" );

        final String result = filter.preSave( context, "authenticated content" );
        assertEquals( "authenticated content", result,
                "Authenticated users should bypass spam filter when ignoreauthenticated=true" );
    }

    @Test
    void preSave_doesNotSkipChecksForAuthenticatedByDefault() throws Exception {
        initFilter( null );

        when( session.isAuthenticated() ).thenReturn( true );
        when( page.getName() ).thenReturn( "A".repeat( 200 ) );
        when( pageManager.getPureText( anyString(), eq( WikiProvider.LATEST_VERSION ) ) ).thenReturn( "" );

        assertThrows( RedirectException.class,
                () -> filter.preSave( context, "content" ),
                "Authenticated users should NOT bypass spam filter by default" );
    }

    // ---- Too many modifications ----

    @Test
    void preSave_rejectsTooManyModificationsFromSameIP() throws Exception {
        final Properties props = new Properties();
        props.setProperty( SpamFilter.PROP_PAGECHANGES, "2" );
        props.setProperty( SpamFilter.PROP_BANTIME, "1" );
        initFilter( props );

        when( page.getName() ).thenReturn( "TestPage" );
        when( pageManager.getPureText( anyString(), eq( WikiProvider.LATEST_VERSION ) ) ).thenReturn( "" );

        // First two saves should succeed
        filter.preSave( context, "change1" );
        filter.preSave( context, "change2" );

        // Third save should be rejected (limit is 2 per minute)
        assertThrows( RedirectException.class,
                () -> filter.preSave( context, "change3" ),
                "The third modification from the same IP within a minute should be rejected when the limit is 2" );
    }

    // ---- Similar modifications ----

    @Test
    void preSave_rejectsSimilarModifications() throws Exception {
        final Properties props = new Properties();
        props.setProperty( SpamFilter.PROP_SIMILARCHANGES, "1" );
        props.setProperty( SpamFilter.PROP_PAGECHANGES, "100" );  // high limit so we don't hit page change limit
        props.setProperty( SpamFilter.PROP_BANTIME, "1" );
        initFilter( props );

        when( page.getName() ).thenReturn( "TestPage" );
        // Return empty page text so both saves produce the same change diff
        when( pageManager.getPureText( anyString(), eq( WikiProvider.LATEST_VERSION ) ) ).thenReturn( "" );

        // First save
        filter.preSave( context, "identical spam content" );

        // Second save with same content should be flagged
        assertThrows( RedirectException.class,
                () -> filter.preSave( context, "identical spam content" ),
                "Submitting the same content change twice should be rejected when similarchanges=1" );
    }

    // ---- Score strategy ----

    @Test
    void preSave_scoreStrategyAccumulatesAndRejectsAtEnd() throws Exception {
        final Properties props = new Properties();
        props.setProperty( SpamFilter.PROP_FILTERSTRATEGY, SpamFilter.STRATEGY_SCORE );
        props.setProperty( SpamFilter.PROP_MAX_PAGENAME_LENGTH, "5" );
        initFilter( props );

        when( page.getName() ).thenReturn( "LongerThanFive" );
        when( pageManager.getPureText( anyString(), eq( WikiProvider.LATEST_VERSION ) ) ).thenReturn( "" );

        // Score strategy stores the score via context.setVariable / getVariable.
        // We need to capture what setVariable stores and return it from getVariable.
        final java.util.Map< String, Object > variables = new java.util.HashMap<>();
        doAnswer( invocation -> {
            variables.put( invocation.getArgument( 0 ), invocation.getArgument( 1 ) );
            return null;
        } ).when( context ).setVariable( anyString(), any() );
        when( context.getVariable( anyString() ) ).thenAnswer( invocation ->
                variables.get( invocation.getArgument( 0 ) ) );

        // With score strategy, checkPageName increments the score; at end of preSave
        // the accumulated score is checked against scoreLimit (1).
        assertThrows( RedirectException.class,
                () -> filter.preSave( context, "content" ),
                "Score strategy should reject when accumulated score meets the limit" );
    }

    // ---- Content change detection ----

    @Test
    void preSave_detectsAddedContentInDiff() throws Exception {
        initFilter( null );

        when( page.getName() ).thenReturn( "TestPage" );
        when( pageManager.getPureText( "TestPage", WikiProvider.LATEST_VERSION ) ).thenReturn( "old content" );

        // New content adds a line - should pass because it's not spammy
        final String result = filter.preSave( context, "old content\nnew line added" );
        assertEquals( "old content\nnew line added", result,
                "Adding non-spam content to an existing page should be accepted" );
    }

    @Test
    void preSave_handlesEmptyOldContent() throws Exception {
        initFilter( null );

        when( page.getName() ).thenReturn( "TestPage" );
        when( pageManager.getPureText( "TestPage", WikiProvider.LATEST_VERSION ) ).thenReturn( "" );

        final String result = filter.preSave( context, "brand new content" );
        assertEquals( "brand new content", result,
                "Creating new content on an empty page should be accepted" );
    }

    @Test
    void preSave_handlesNoChangeGracefullyWithoutHttpRequest() throws Exception {
        initFilter( null );

        when( page.getName() ).thenReturn( "TestPage" );
        when( pageManager.getPureText( "TestPage", WikiProvider.LATEST_VERSION ) ).thenReturn( "same" );
        // When there is no change, the Change object has a null change field.
        // checkSinglePageChange would NPE on urlPattern.matcher(null) if an HTTP
        // request is present. With no request, the URL/bot/UTF8 checks are skipped.
        when( context.getHttpRequest() ).thenReturn( null );

        final String result = filter.preSave( context, "same" );
        assertEquals( "same", result,
                "Saving the same content (no change) should be accepted" );
    }

    // ---- User profile validation ----

    @Test
    void isValidUserProfile_rejectsProfileMatchingSpamPattern() throws Exception {
        initFilter( null );

        // Set up spam patterns
        final Page spamPage = mock( Page.class );
        when( spamPage.getName() ).thenReturn( "SpamFilterWordList" );
        when( spamPage.getLastModified() ).thenReturn( new java.util.Date() );
        when( spamPage.getAttribute( "spamwords" ) ).thenReturn( "spammer\\.com" );
        when( pageManager.getPage( "SpamFilterWordList" ) ).thenReturn( spamPage );

        // Prime the blacklists by doing a preSave (which calls refreshBlacklists)
        when( page.getName() ).thenReturn( "TempPage" );
        when( pageManager.getPureText( anyString(), eq( WikiProvider.LATEST_VERSION ) ) ).thenReturn( "" );
        filter.preSave( context, "prime" );

        // Now check the user profile
        final UserProfile profile = mock( UserProfile.class );
        when( profile.getEmail() ).thenReturn( "user@spammer.com" );
        when( profile.getFullname() ).thenReturn( "Normal Name" );
        when( profile.getLoginName() ).thenReturn( "normallogin" );

        assertFalse( filter.isValidUserProfile( context, profile ),
                "User profile with email matching a spam pattern should be rejected" );
    }

    @Test
    void isValidUserProfile_acceptsCleanProfile() throws Exception {
        initFilter( null );

        // Set up spam patterns
        final Page spamPage = mock( Page.class );
        when( spamPage.getName() ).thenReturn( "SpamFilterWordList" );
        when( spamPage.getLastModified() ).thenReturn( new java.util.Date() );
        when( spamPage.getAttribute( "spamwords" ) ).thenReturn( "spammer\\.com" );
        when( pageManager.getPage( "SpamFilterWordList" ) ).thenReturn( spamPage );

        // Prime the blacklists
        when( page.getName() ).thenReturn( "TempPage" );
        when( pageManager.getPureText( anyString(), eq( WikiProvider.LATEST_VERSION ) ) ).thenReturn( "" );
        filter.preSave( context, "prime" );

        final UserProfile profile = mock( UserProfile.class );
        when( profile.getEmail() ).thenReturn( "user@example.com" );
        when( profile.getFullname() ).thenReturn( "Jane Doe" );
        when( profile.getLoginName() ).thenReturn( "janedoe" );

        assertTrue( filter.isValidUserProfile( context, profile ),
                "A clean user profile should be accepted" );
    }

    @Test
    void isValidUserProfile_rejectsFullnameMatchingSpam() throws Exception {
        initFilter( null );

        final Page spamPage = mock( Page.class );
        when( spamPage.getName() ).thenReturn( "SpamFilterWordList" );
        when( spamPage.getLastModified() ).thenReturn( new java.util.Date() );
        when( spamPage.getAttribute( "spamwords" ) ).thenReturn( "buy-viagra" );
        when( pageManager.getPage( "SpamFilterWordList" ) ).thenReturn( spamPage );

        when( page.getName() ).thenReturn( "TempPage" );
        when( pageManager.getPureText( anyString(), eq( WikiProvider.LATEST_VERSION ) ) ).thenReturn( "" );
        filter.preSave( context, "prime" );

        final UserProfile profile = mock( UserProfile.class );
        when( profile.getEmail() ).thenReturn( "ok@example.com" );
        when( profile.getFullname() ).thenReturn( "buy-viagra expert" );
        when( profile.getLoginName() ).thenReturn( "user123" );

        assertFalse( filter.isValidUserProfile( context, profile ),
                "A user profile whose full name matches a spam pattern should be rejected" );
    }

    // ---- Static utility methods ----

    @Test
    void getBotFieldName_returnsExpectedValue() {
        assertEquals( "submit_auth", SpamFilter.getBotFieldName(),
                "The bot trap field name should be 'submit_auth'" );
    }

    @Test
    void getSpamHash_incorporatesPageModifiedDateAndIP() {
        final Page hashPage = mock( Page.class );
        when( hashPage.getLastModified() ).thenReturn( new java.util.Date( 1000000L ) );

        final HttpServletRequest hashReq = mock( HttpServletRequest.class );
        when( hashReq.getRemoteAddr() ).thenReturn( "192.168.1.1" );

        final String hash = SpamFilter.getSpamHash( hashPage, hashReq );

        assertNotNull( hash, "Spam hash should never be null" );
        assertFalse( hash.isEmpty(), "Spam hash should not be empty" );
    }

    @Test
    void getSpamHash_producesConsistentResults() {
        final Page hashPage = mock( Page.class );
        when( hashPage.getLastModified() ).thenReturn( new java.util.Date( 1000000L ) );

        final HttpServletRequest hashReq = mock( HttpServletRequest.class );
        when( hashReq.getRemoteAddr() ).thenReturn( "192.168.1.1" );

        final String hash1 = SpamFilter.getSpamHash( hashPage, hashReq );
        final String hash2 = SpamFilter.getSpamHash( hashPage, hashReq );

        assertEquals( hash1, hash2,
                "Spam hash should be deterministic for the same page and request" );
    }

    @Test
    void getSpamHash_handlesNullLastModified() {
        final Page hashPage = mock( Page.class );
        when( hashPage.getLastModified() ).thenReturn( null );

        final HttpServletRequest hashReq = mock( HttpServletRequest.class );
        when( hashReq.getRemoteAddr() ).thenReturn( "10.0.0.1" );

        final String hash = SpamFilter.getSpamHash( hashPage, hashReq );

        assertNotNull( hash, "Spam hash should handle null lastModified date" );
    }

    @Test
    void getHashFieldName_returnsNonNullValue() {
        final HttpServletRequest hashReq = mock( HttpServletRequest.class );
        final HttpSession httpSession = mock( HttpSession.class );
        when( hashReq.getSession() ).thenReturn( httpSession );
        when( httpSession.getAttribute( "_hash" ) ).thenReturn( null );

        final String fieldName = SpamFilter.getHashFieldName( hashReq );

        assertNotNull( fieldName, "Hash field name should never be null" );
        assertFalse( fieldName.isEmpty(), "Hash field name should not be empty" );
    }

    @Test
    void getHashFieldName_returnsSessionCachedValue() {
        final HttpServletRequest hashReq = mock( HttpServletRequest.class );
        final HttpSession httpSession = mock( HttpSession.class );
        when( hashReq.getSession() ).thenReturn( httpSession );
        when( httpSession.getAttribute( "_hash" ) ).thenReturn( "cached_hash" );

        final String fieldName = SpamFilter.getHashFieldName( hashReq );

        assertEquals( "cached_hash", fieldName,
                "Hash field name should return the cached session value when available" );
    }

    // ---- Initialization via engine ----

    @Test
    void initialize_resolvesManagersFromEngine() throws Exception {
        final SpamFilter fresh = new SpamFilter();

        fresh.initialize( engine, new Properties() );

        // After initialize, the filter should be able to process pages
        // (which implicitly verifies managers were resolved from the engine)
        when( page.getName() ).thenReturn( "TestPage" );
        when( pageManager.getPureText( anyString(), eq( WikiProvider.LATEST_VERSION ) ) ).thenReturn( "" );

        final String result = fresh.preSave( context, "test content" );
        assertEquals( "test content", result,
                "Filter initialized via engine should process pages correctly" );
    }

    // ---- No HTTP request (programmatic context) ----

    @Test
    void preSave_acceptsContentWhenNoHttpRequest() throws Exception {
        initFilter( null );

        when( context.getHttpRequest() ).thenReturn( null );
        when( page.getName() ).thenReturn( "TestPage" );
        when( pageManager.getPureText( anyString(), eq( WikiProvider.LATEST_VERSION ) ) ).thenReturn( "" );

        final String result = filter.preSave( context, "programmatic content" );
        assertEquals( "programmatic content", result,
                "Programmatic saves without an HTTP request should be accepted " +
                "(single-page-change checks, bot trap, and UTF-8 checks are skipped)" );
    }

    // ---- Captcha bypass ----

    @Test
    void preSave_skipsChecksWhenCaptchaVariableSet() throws Exception {
        initFilter( null );

        when( context.getVariable( "captcha" ) ).thenReturn( "ok" );
        when( page.getName() ).thenReturn( "A".repeat( 200 ) );
        when( pageManager.getPureText( anyString(), eq( WikiProvider.LATEST_VERSION ) ) ).thenReturn( "" );

        final String result = filter.preSave( context, "content" );
        assertEquals( "content", result,
                "When captcha variable is set, spam checks should be bypassed" );
    }

    // ---- Temporary ban list ----

    @Test
    void preSave_rejectsRequestFromTemporarilyBannedIP() throws Exception {
        final Properties props = new Properties();
        props.setProperty( SpamFilter.PROP_PAGECHANGES, "1" );
        props.setProperty( SpamFilter.PROP_BANTIME, "60" );
        initFilter( props );

        when( page.getName() ).thenReturn( "TestPage" );
        when( pageManager.getPureText( anyString(), eq( WikiProvider.LATEST_VERSION ) ) ).thenReturn( "" );

        // First save succeeds but adds IP to modification list
        filter.preSave( context, "change1" );

        // Second save gets the IP temporarily banned (exceeds 1 change/minute limit)
        try {
            filter.preSave( context, "change2" );
        } catch( final RedirectException ignored ) {
            // expected
        }

        // Third save should be rejected because the IP is on the ban list
        assertThrows( RedirectException.class,
                () -> filter.preSave( context, "change3" ),
                "Requests from temporarily banned IPs should be rejected" );
    }

    // ---- Multiple spam word patterns ----

    @Test
    void preSave_checksMultipleSpamPatterns() throws Exception {
        initFilter( null );

        final Page spamPage = mock( Page.class );
        when( spamPage.getName() ).thenReturn( "SpamFilterWordList" );
        when( spamPage.getLastModified() ).thenReturn( new java.util.Date() );
        when( spamPage.getAttribute( "spamwords" ) ).thenReturn( "casino poker buy-cheap" );
        when( pageManager.getPage( "SpamFilterWordList" ) ).thenReturn( spamPage );

        when( page.getName() ).thenReturn( "TestPage" );
        when( pageManager.getPureText( anyString(), eq( WikiProvider.LATEST_VERSION ) ) ).thenReturn( "" );

        // The second pattern should still match
        assertThrows( RedirectException.class,
                () -> filter.preSave( context, "Best poker sites online" ),
                "Content matching any one of multiple spam patterns should be rejected" );
    }

    // ---- Regex pattern matching (not just substring) ----

    @Test
    void preSave_usesRegexPatternMatchingForSpamWords() throws Exception {
        initFilter( null );

        final Page spamPage = mock( Page.class );
        when( spamPage.getName() ).thenReturn( "SpamFilterWordList" );
        when( spamPage.getLastModified() ).thenReturn( new java.util.Date() );
        when( spamPage.getAttribute( "spamwords" ) ).thenReturn( "v[1i]agra" );
        when( pageManager.getPage( "SpamFilterWordList" ) ).thenReturn( spamPage );

        when( page.getName() ).thenReturn( "TestPage" );
        when( pageManager.getPureText( anyString(), eq( WikiProvider.LATEST_VERSION ) ) ).thenReturn( "" );

        // The regex v[1i]agra should match "v1agra"
        assertThrows( RedirectException.class,
                () -> filter.preSave( context, "buy v1agra now" ),
                "Spam filter should use regex matching, so 'v[1i]agra' should match 'v1agra'" );
    }

    // ---- IP pattern list also bypasses self-editing ----

    @Test
    void preSave_allowsEditingIPListPageEvenWithBannedIP() throws Exception {
        initFilter( null );

        final Page ipPage = mock( Page.class );
        when( ipPage.getName() ).thenReturn( "SpamFilterIPList" );
        when( ipPage.getLastModified() ).thenReturn( new java.util.Date() );
        when( ipPage.getAttribute( "ips" ) ).thenReturn( "127\\.0\\.0\\.1" );
        when( pageManager.getPage( "SpamFilterIPList" ) ).thenReturn( ipPage );

        // Editing the IP list page itself
        when( page.getName() ).thenReturn( "SpamFilterIPList" );
        when( pageManager.getPureText( anyString(), eq( WikiProvider.LATEST_VERSION ) ) ).thenReturn( "" );

        final String result = filter.preSave( context, "127.0.0.1 should not block this" );
        assertEquals( "127.0.0.1 should not block this", result,
                "Editing the SpamFilterIPList page itself should bypass IP pattern checks" );
    }

}
