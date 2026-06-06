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
package com.wikantik.render.subsystem.spam;

import com.wikantik.api.core.Context;
import com.wikantik.api.core.ContextEnum;
import com.wikantik.api.core.Page;
import com.wikantik.api.exceptions.RedirectException;
import com.wikantik.api.managers.AttachmentManager;
import com.wikantik.api.managers.PageManager;
import com.wikantik.api.core.Attachment;
import jakarta.servlet.http.HttpServletRequest;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.Date;
import java.util.Properties;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/**
 * Unit tests for {@link DefaultSpamPatternMatcher}.
 * Uses stopAtFirstMatch=true (EAGER strategy) so the spam-detected path throws
 * {@link RedirectException} deterministically without requiring a score accumulator.
 */
class DefaultSpamPatternMatcherTest {

    private PageManager pageManager;
    private AttachmentManager attachmentManager;
    private DefaultSpamPatternMatcher matcher;
    private Context context;
    private Page page;
    private HttpServletRequest request;

    /** The forbidden-words page name used in the test matcher. */
    private static final String WORDS_PAGE = "SpamFilterWordList";
    /** The forbidden-IPs page name used in the test matcher. */
    private static final String IPS_PAGE   = "SpamFilterIPList";
    /** The blacklist attachment path. */
    private static final String BLACKLIST  = "SpamFilterWordList/blacklist.txt";

    @BeforeEach
    void setUp() {
        pageManager       = mock( PageManager.class );
        attachmentManager = mock( AttachmentManager.class );

        final Properties props = new Properties();
        props.setProperty( "wordlist",          WORDS_PAGE );
        props.setProperty( "IPlist",            IPS_PAGE );
        props.setProperty( "maxpagenamelength", "50" );
        props.setProperty( "blacklist",         BLACKLIST );
        props.setProperty( "errorpage",         "RejectedMessage" );

        // stopAtFirstMatch=true → checkStrategy always throws RedirectException
        matcher = new DefaultSpamPatternMatcher( pageManager, attachmentManager, props, true );

        page = mock( Page.class );
        when( page.getName() ).thenReturn( "TestPage" );

        request = mock( HttpServletRequest.class );
        when( request.getRemoteAddr() ).thenReturn( "10.0.0.1" );

        context = mock( Context.class );
        when( context.getPage() ).thenReturn( page );
        when( context.getHttpRequest() ).thenReturn( request );
        when( context.getURL( anyString(), anyString() ) ).thenReturn( "http://localhost/RejectedMessage" );
    }

    // -----------------------------------------------------------------------
    // refreshBlacklists – page-based word list triggers rebuild
    // -----------------------------------------------------------------------

    @Test
    void refreshBlacklists_populatesSpamPatternsFromWordsPage() {
        final Page spamPage = spamWordsPageMock( "buy-viagra casino" );
        when( pageManager.getPage( WORDS_PAGE ) ).thenReturn( spamPage );

        // No attachment, no IP page
        when( pageManager.getPage( IPS_PAGE ) ).thenReturn( null );

        // After refresh the pattern should be live
        matcher.refreshBlacklists( context );

        // Verify: a matching check should throw
        assertThrows( RedirectException.class,
                () -> matcher.checkPatternList( context, "buy-viagra now" ),
                "After refresh, pattern loaded from the words page should be detected" );
    }

    @Test
    void refreshBlacklists_nullWordsPageAndNullIpsPage_doesNotThrowAndLeavesNull() {
        when( pageManager.getPage( WORDS_PAGE ) ).thenReturn( null );
        when( pageManager.getPage( IPS_PAGE ) ).thenReturn( null );

        // Should be a no-op — no patterns loaded, so checkPatternList early-returns
        matcher.refreshBlacklists( context );

        // spamPatterns stays null → early return in checkPatternList, no exception
        assertDoesNotThrow( () -> matcher.checkPatternList( context, "buy-viagra casino" ) );
    }

    @Test
    void refreshBlacklists_populatesIPPatternsFromIPsPage() {
        // No spam words
        when( pageManager.getPage( WORDS_PAGE ) ).thenReturn( null );

        final Page ipPage = mock( Page.class );
        when( ipPage.getName() ).thenReturn( IPS_PAGE );
        when( ipPage.getLastModified() ).thenReturn( new Date() );
        when( ipPage.getAttribute( "ips" ) ).thenReturn( "10\\.0\\.0\\.1" );
        when( pageManager.getPage( IPS_PAGE ) ).thenReturn( ipPage );

        matcher.refreshBlacklists( context );

        // IP 10.0.0.1 (returned by getRemoteAddr) should now match
        assertThrows( RedirectException.class,
                () -> matcher.checkIPList( context ),
                "After refresh, IP matching the banned pattern should be rejected" );
    }

    @Test
    void refreshBlacklists_withAttachment_appendsBlacklistPatterns() throws Exception {
        // Words page present so a rebuild is triggered
        final Page spamPage = spamWordsPageMock( "initial-pattern" );
        when( pageManager.getPage( WORDS_PAGE ) ).thenReturn( spamPage );
        when( pageManager.getPage( IPS_PAGE ) ).thenReturn( null );

        // Attachment present – returns a blacklist with one extra pattern
        final Attachment att = mock( Attachment.class );
        when( att.getLastModified() ).thenReturn( new Date() );
        when( attachmentManager.getAttachmentInfo( context, BLACKLIST ) ).thenReturn( att );

        final String blacklistContent = "# comment line\n\nattachment-spam-word\n";
        final ByteArrayInputStream bis = new ByteArrayInputStream(
                blacklistContent.getBytes( StandardCharsets.UTF_8 ) );
        when( attachmentManager.getAttachmentStream( att ) ).thenReturn( bis );

        matcher.refreshBlacklists( context );

        // Pattern from attachment should now be active
        assertThrows( RedirectException.class,
                () -> matcher.checkPatternList( context, "attachment-spam-word detected" ),
                "Pattern loaded from the blacklist attachment should be detected" );
    }

    @Test
    void refreshBlacklists_withAttachmentIOException_doesNotPropagateException() throws Exception {
        final Page spamPage = spamWordsPageMock( "some-word" );
        when( pageManager.getPage( WORDS_PAGE ) ).thenReturn( spamPage );
        when( pageManager.getPage( IPS_PAGE ) ).thenReturn( null );

        final Attachment att = mock( Attachment.class );
        when( att.getLastModified() ).thenReturn( new Date() );
        when( attachmentManager.getAttachmentInfo( context, BLACKLIST ) ).thenReturn( att );
        when( attachmentManager.getAttachmentStream( att ) ).thenThrow( new IOException( "disk error" ) );

        // IOException must be swallowed (logged at INFO) — the words page still loads
        assertDoesNotThrow( () -> matcher.refreshBlacklists( context ) );
    }

    @Test
    void refreshBlacklists_noRebuildWhenPatternsAlreadyFreshAndPageNotModified() {
        // First refresh — page is new, triggers rebuild
        final Date modDate = new Date( System.currentTimeMillis() - 10_000L );
        final Page spamPage = mock( Page.class );
        when( spamPage.getName() ).thenReturn( WORDS_PAGE );
        when( spamPage.getLastModified() ).thenReturn( modDate );
        when( spamPage.getAttribute( "spamwords" ) ).thenReturn( "first-pattern" );
        when( pageManager.getPage( WORDS_PAGE ) ).thenReturn( spamPage );
        when( pageManager.getPage( IPS_PAGE ) ).thenReturn( null );

        matcher.refreshBlacklists( context );

        // Second refresh — same (older) modification date, patterns already non-empty
        // → no rebuild; first-pattern should still work
        matcher.refreshBlacklists( context );

        assertThrows( RedirectException.class,
                () -> matcher.checkPatternList( context, "first-pattern in content" ) );
    }

    // -----------------------------------------------------------------------
    // checkPatternList – early-return paths
    // -----------------------------------------------------------------------

    @Test
    void checkPatternList_earlyReturnWhenSpamPatternsNull() {
        // No refresh called → spamPatterns is null
        assertDoesNotThrow( () -> matcher.checkPatternList( context, "buy-viagra" ),
                "With null spamPatterns, checkPatternList must return without throwing" );
    }

    @Test
    void checkPatternList_earlyReturnWhenEditingForbiddenWordsPageItself() {
        // Load patterns
        final Page spamPage = spamWordsPageMock( "buy-viagra" );
        when( pageManager.getPage( WORDS_PAGE ) ).thenReturn( spamPage );
        when( pageManager.getPage( IPS_PAGE ) ).thenReturn( null );
        matcher.refreshBlacklists( context );

        // Now pretend the current page IS the forbidden words page
        when( page.getName() ).thenReturn( WORDS_PAGE );

        assertDoesNotThrow( () -> matcher.checkPatternList( context, "buy-viagra editing the list itself" ),
                "Editing the forbiddenWordsPage should bypass pattern matching" );
    }

    @Test
    void checkPatternList_cleanText_doesNotThrow() {
        final Page spamPage = spamWordsPageMock( "buy-viagra" );
        when( pageManager.getPage( WORDS_PAGE ) ).thenReturn( spamPage );
        when( pageManager.getPage( IPS_PAGE ) ).thenReturn( null );
        matcher.refreshBlacklists( context );

        assertDoesNotThrow( () -> matcher.checkPatternList( context, "This is a perfectly clean wiki page" ) );
    }

    @Test
    void checkPatternList_matchingText_throwsRedirectException() {
        final Page spamPage = spamWordsPageMock( "buy-viagra" );
        when( pageManager.getPage( WORDS_PAGE ) ).thenReturn( spamPage );
        when( pageManager.getPage( IPS_PAGE ) ).thenReturn( null );
        matcher.refreshBlacklists( context );

        assertThrows( RedirectException.class,
                () -> matcher.checkPatternList( context, "Check out buy-viagra today!" ) );
    }

    @Test
    void checkPatternList_regexPattern_matchesCorrectly() {
        final Page spamPage = spamWordsPageMock( "v[1i]agra" );
        when( pageManager.getPage( WORDS_PAGE ) ).thenReturn( spamPage );
        when( pageManager.getPage( IPS_PAGE ) ).thenReturn( null );
        matcher.refreshBlacklists( context );

        // "v1agra" should match the regex v[1i]agra
        assertThrows( RedirectException.class,
                () -> matcher.checkPatternList( context, "buy v1agra now" ) );
    }

    @Test
    void checkPatternList_matchesRemoteIPAppendedToText() {
        // Pattern that matches the IP portion, not the content itself
        final Page spamPage = spamWordsPageMock( "10\\.0\\.0\\.1" );
        when( pageManager.getPage( WORDS_PAGE ) ).thenReturn( spamPage );
        when( pageManager.getPage( IPS_PAGE ) ).thenReturn( null );
        matcher.refreshBlacklists( context );

        // request.getRemoteAddr() → "10.0.0.1" which gets appended to the change text
        assertThrows( RedirectException.class,
                () -> matcher.checkPatternList( context, "completely clean content" ),
                "Pattern matching the appended remote IP should trigger detection" );
    }

    @Test
    void checkPatternList_withSpamChange_matchingPattern_throwsRedirectException() {
        final Page spamPage = spamWordsPageMock( "casino" );
        when( pageManager.getPage( WORDS_PAGE ) ).thenReturn( spamPage );
        when( pageManager.getPage( IPS_PAGE ) ).thenReturn( null );
        matcher.refreshBlacklists( context );

        final SpamChange change = new SpamChange();
        change.change = "Play at the casino online";

        assertThrows( RedirectException.class,
                () -> matcher.checkPatternList( context, change ) );
    }

    @Test
    void checkPatternList_withSpamChange_cleanContent_doesNotThrow() {
        final Page spamPage = spamWordsPageMock( "casino" );
        when( pageManager.getPage( WORDS_PAGE ) ).thenReturn( spamPage );
        when( pageManager.getPage( IPS_PAGE ) ).thenReturn( null );
        matcher.refreshBlacklists( context );

        final SpamChange change = new SpamChange();
        change.change = "This is a normal wiki edit";

        assertDoesNotThrow( () -> matcher.checkPatternList( context, change ) );
    }

    @Test
    void checkPatternList_nullHttpRequest_doesNotNPE() {
        final Page spamPage = spamWordsPageMock( "buy-viagra" );
        when( pageManager.getPage( WORDS_PAGE ) ).thenReturn( spamPage );
        when( pageManager.getPage( IPS_PAGE ) ).thenReturn( null );
        matcher.refreshBlacklists( context );

        when( context.getHttpRequest() ).thenReturn( null );

        // No remote IP is appended; clean content should pass
        assertDoesNotThrow( () -> matcher.checkPatternList( context, "clean content here" ) );
    }

    // -----------------------------------------------------------------------
    // checkIPList – IP banning
    // -----------------------------------------------------------------------

    @Test
    void checkIPList_earlyReturnWhenIPPatternsNull() {
        // No refresh → IPPatterns null
        assertDoesNotThrow( () -> matcher.checkIPList( context ) );
    }

    @Test
    void checkIPList_earlyReturnWhenEditingIPListPage() {
        final Page ipPage = ipPageMock( "10\\.0\\.0\\.1" );
        when( pageManager.getPage( WORDS_PAGE ) ).thenReturn( null );
        when( pageManager.getPage( IPS_PAGE ) ).thenReturn( ipPage );
        matcher.refreshBlacklists( context );

        // Current page is the IP list itself
        when( page.getName() ).thenReturn( IPS_PAGE );

        assertDoesNotThrow( () -> matcher.checkIPList( context ),
                "Editing the forbiddenIPsPage should bypass IP list checking" );
    }

    @Test
    void checkIPList_matchingIP_throwsRedirectException() {
        final Page ipPage = ipPageMock( "10\\.0\\.0\\.1" );
        when( pageManager.getPage( WORDS_PAGE ) ).thenReturn( null );
        when( pageManager.getPage( IPS_PAGE ) ).thenReturn( ipPage );
        matcher.refreshBlacklists( context );

        // request.getRemoteAddr() = "10.0.0.1" — matches the pattern
        assertThrows( RedirectException.class,
                () -> matcher.checkIPList( context ) );
    }

    @Test
    void checkIPList_nonMatchingIP_doesNotThrow() {
        final Page ipPage = ipPageMock( "192\\.168\\.1\\.99" );
        when( pageManager.getPage( WORDS_PAGE ) ).thenReturn( null );
        when( pageManager.getPage( IPS_PAGE ) ).thenReturn( ipPage );
        matcher.refreshBlacklists( context );

        // Remote addr is 10.0.0.1, banned pattern is 192.168.1.99 → no match
        assertDoesNotThrow( () -> matcher.checkIPList( context ) );
    }

    // -----------------------------------------------------------------------
    // checkPageName – name length enforcement
    // -----------------------------------------------------------------------

    @Test
    void checkPageName_nameLongerThanMax_throwsRedirectException() {
        // maxpagenamelength is set to 50 in setUp; use 51-char name
        when( page.getName() ).thenReturn( "A".repeat( 51 ) );

        assertThrows( RedirectException.class,
                () -> matcher.checkPageName( context ) );
    }

    @Test
    void checkPageName_nameExactlyAtMax_doesNotThrow() {
        // exactly 50 chars — should pass
        when( page.getName() ).thenReturn( "A".repeat( 50 ) );

        assertDoesNotThrow( () -> matcher.checkPageName( context ) );
    }

    @Test
    void checkPageName_shortName_doesNotThrow() {
        when( page.getName() ).thenReturn( "ShortName" );

        assertDoesNotThrow( () -> matcher.checkPageName( context ) );
    }

    // -----------------------------------------------------------------------
    // getChange – diff building
    // -----------------------------------------------------------------------

    @Test
    void getChange_returnsEmptyChangeWhenTextUnchanged() throws Exception {
        when( pageManager.getPureText( "TestPage", com.wikantik.api.providers.WikiProvider.LATEST_VERSION ) )
                .thenReturn( "unchanged content" );
        when( page.getAuthor() ).thenReturn( null );
        when( page.getAttribute( Page.CHANGENOTE ) ).thenReturn( null );

        final SpamChange ch = matcher.getChange( context, "unchanged content" );

        // No additions/removals when content is identical
        assertEquals( 0, ch.adds );
        assertEquals( 0, ch.removals );
    }

    @Test
    void getChange_countsAddedLines() throws Exception {
        when( pageManager.getPureText( "TestPage", com.wikantik.api.providers.WikiProvider.LATEST_VERSION ) )
                .thenReturn( "" );
        when( page.getAuthor() ).thenReturn( null );
        when( page.getAttribute( Page.CHANGENOTE ) ).thenReturn( null );

        final SpamChange ch = matcher.getChange( context, "brand new content" );

        assertTrue( ch.adds > 0, "Adding new content should increase the add counter" );
    }

    @Test
    void getChange_appendsAuthorAndChangenote() throws Exception {
        when( pageManager.getPureText( "TestPage", com.wikantik.api.providers.WikiProvider.LATEST_VERSION ) )
                .thenReturn( "" );
        when( page.getAuthor() ).thenReturn( "Alice" );
        when( page.getAttribute( Page.CHANGENOTE ) ).thenReturn( "my note" );

        final SpamChange ch = matcher.getChange( context, "new content" );

        assertNotNull( ch.change );
        assertTrue( ch.change.contains( "Alice" ), "Change string should contain the author name" );
        assertTrue( ch.change.contains( "my note" ), "Change string should contain the change note" );
    }

    // -----------------------------------------------------------------------
    // getForbiddenWordsPage / getErrorPage
    // -----------------------------------------------------------------------

    @Test
    void getForbiddenWordsPage_returnsConfiguredValue() {
        assertEquals( WORDS_PAGE, matcher.getForbiddenWordsPage() );
    }

    @Test
    void getErrorPage_returnsConfiguredValue() {
        assertEquals( "RejectedMessage", matcher.getErrorPage() );
    }

    // -----------------------------------------------------------------------
    // helpers
    // -----------------------------------------------------------------------

    private Page spamWordsPageMock( final String wordList ) {
        final Page p = mock( Page.class );
        when( p.getName() ).thenReturn( WORDS_PAGE );
        when( p.getLastModified() ).thenReturn( new Date() );
        when( p.getAttribute( "spamwords" ) ).thenReturn( wordList );
        return p;
    }

    private Page ipPageMock( final String ipPattern ) {
        final Page p = mock( Page.class );
        when( p.getName() ).thenReturn( IPS_PAGE );
        when( p.getLastModified() ).thenReturn( new Date() );
        when( p.getAttribute( "ips" ) ).thenReturn( ipPattern );
        return p;
    }
}
