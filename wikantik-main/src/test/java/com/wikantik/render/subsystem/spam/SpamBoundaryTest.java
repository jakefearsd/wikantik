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

import com.wikantik.api.core.Attachment;
import com.wikantik.api.core.Context;
import com.wikantik.api.core.ContextEnum;
import com.wikantik.api.core.Page;
import com.wikantik.api.exceptions.RedirectException;
import com.wikantik.api.managers.AttachmentManager;
import com.wikantik.api.managers.PageManager;
import com.wikantik.api.providers.WikiProvider;
import jakarta.servlet.http.HttpServletRequest;
import net.thauvin.erik.akismet.AkismetComment;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.mockito.ArgumentCaptor;
import org.mockito.InOrder;

import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;
import java.security.Principal;
import java.util.Date;
import java.util.Properties;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * The spam subsystem's thresholds, reload decisions and call sequence, probed at their boundaries. Every test builds
 * the strategies in stop-at-first-match mode so a hit is a {@link RedirectException}.
 */
class SpamBoundaryTest {

    private static final String WORDS_PAGE = "SpamFilterWordList";
    private static final String IPS_PAGE = "SpamFilterIPList";
    private static final String BLACKLIST = "SpamFilterWordList/blacklist.txt";

    private Context context;
    private Page page;
    private HttpServletRequest request;

    @BeforeEach
    void setUp() {
        page = mock( Page.class );
        when( page.getName() ).thenReturn( "TestPage" );
        request = mock( HttpServletRequest.class );
        when( request.getRemoteAddr() ).thenReturn( "10.0.0.1" );
        context = mock( Context.class );
        when( context.getPage() ).thenReturn( page );
        when( context.getHttpRequest() ).thenReturn( request );
        when( context.getURL( anyString(), anyString() ) ).thenReturn( "http://localhost/RejectedMessage" );
    }

    private static SpamChange change( final String text ) {
        final SpamChange c = new SpamChange();
        c.change = text;
        return c;
    }

    // ---- DefaultSpamPolicy ------------------------------------------------------------------------------------------

    @Test
    void policyRunsEveryCheckOnTheChangeInTheDocumentedOrder() throws Exception {
        final SpamRateLimiter limiter = mock( SpamRateLimiter.class );
        final SpamPatternMatcher matcher = mock( SpamPatternMatcher.class );
        final SpamExternalSignals signals = mock( SpamExternalSignals.class );
        final SpamChange change = change( "edit" );

        new DefaultSpamPolicy( limiter, matcher, signals, true, "RejectedMessage" ).evaluate( context, "edit", change );

        final InOrder order = inOrder( limiter, signals, matcher );
        order.verify( limiter ).checkBanList( context, change );
        order.verify( limiter ).checkSinglePageChange( context, change );
        order.verify( signals ).checkBotTrap( context, change );
        order.verify( signals ).checkUTF8( context, change );
        order.verify( signals ).checkAkismet( context, change );
        order.verify( matcher ).checkIPList( context );
        order.verify( matcher ).checkPatternList( context, change );
        order.verify( matcher ).checkPageName( context );
    }

    @Test
    void policyStopsAtTheFirstCheckThatRedirects() throws Exception {
        final SpamRateLimiter limiter = mock( SpamRateLimiter.class );
        final SpamPatternMatcher matcher = mock( SpamPatternMatcher.class );
        final SpamExternalSignals signals = mock( SpamExternalSignals.class );
        final SpamChange change = change( "edit" );
        org.mockito.Mockito.doThrow( new RedirectException( "banned", "RejectedMessage" ) )
                .when( limiter ).checkBanList( context, change );

        assertThrows( RedirectException.class,
                () -> new DefaultSpamPolicy( limiter, matcher, signals, true, "RejectedMessage" ).evaluate( context, "edit", change ) );

        verify( limiter, never() ).checkSinglePageChange( any(), any() );
        verify( matcher, never() ).checkPageName( any() );
    }

    // ---- DefaultSpamRateLimiter -------------------------------------------------------------------------------------

    private static DefaultSpamRateLimiter limiter( final int pageChanges, final int similar, final int maxUrls ) {
        final Properties props = new Properties();
        props.setProperty( "bantime", "1" );
        props.setProperty( "pagechangesinminute", String.valueOf( pageChanges ) );
        props.setProperty( "similarchanges", String.valueOf( similar ) );
        props.setProperty( "maxurls", String.valueOf( maxUrls ) );
        return new DefaultSpamRateLimiter( props, true, "RejectedMessage" );
    }

    private static String urls( final int n ) {
        final StringBuilder sb = new StringBuilder( "links:" );
        for( int i = 0; i < n; i++ ) {
            sb.append( " http://site" ).append( i ).append( ".example/x" );
        }
        return sb.toString();
    }

    @ParameterizedTest
    @ValueSource( ints = { 0, 1, 2, 3 } )
    void aChangeWithAtMostMaxUrlsLinksIsNotBanned( final int links ) {
        final DefaultSpamRateLimiter limiter = limiter( 999, 999, 3 );

        assertDoesNotThrow( () -> limiter.checkSinglePageChange( context, change( urls( links ) ) ) );
    }

    @ParameterizedTest
    @ValueSource( ints = { 4, 5, 20 } )
    void aChangeWithMoreThanMaxUrlsLinksIsBanned( final int links ) {
        final DefaultSpamRateLimiter limiter = limiter( 999, 999, 3 );

        assertThrows( RedirectException.class, () -> limiter.checkSinglePageChange( context, change( urls( links ) ) ) );
    }

    @Test
    void theBanMessageCountsTheRemainingSecondsOfTheConfiguredBanTime() throws Exception {
        final DefaultSpamRateLimiter limiter = limiter( 999, 999, 3 );
        assertThrows( RedirectException.class, () -> limiter.checkSinglePageChange( context, change( urls( 4 ) ) ) );

        final RedirectException banned = assertThrows( RedirectException.class,
                () -> limiter.checkBanList( context, change( "next edit" ) ) );

        // bantime=1 minute: just banned, so 59 or 60 seconds are left
        assertTrue( banned.getMessage().matches( ".*\\((59|60) seconds of ban left\\).*" ), banned.getMessage() );
    }

    @Test
    void aBanThatHasNotExpiredSurvivesTheHousekeeping() throws Exception {
        final DefaultSpamRateLimiter limiter = limiter( 999, 999, 3 );
        assertThrows( RedirectException.class, () -> limiter.checkSinglePageChange( context, change( urls( 4 ) ) ) );

        limiter.cleanBanList();

        assertThrows( RedirectException.class, () -> limiter.checkBanList( context, change( "next edit" ) ),
                "a one-minute ban is still in force right after housekeeping" );
    }

    @Test
    void aBanAppliesToTheOffendingAddressOnly() throws Exception {
        final DefaultSpamRateLimiter limiter = limiter( 999, 999, 3 );
        assertThrows( RedirectException.class, () -> limiter.checkSinglePageChange( context, change( urls( 4 ) ) ) );

        final HttpServletRequest otherRequest = mock( HttpServletRequest.class );
        when( otherRequest.getRemoteAddr() ).thenReturn( "10.0.0.2" );
        final Context other = mock( Context.class );
        when( other.getPage() ).thenReturn( page );
        when( other.getHttpRequest() ).thenReturn( otherRequest );

        assertDoesNotThrow( () -> limiter.checkBanList( other, change( "innocent edit" ) ) );
    }

    @Test
    void recordedModificationsCountTowardsTheSamePerMinuteLimitAsLiveOnes() {
        final DefaultSpamRateLimiter limiter = limiter( 2, 999, 999 );
        limiter.recordModification( "10.0.0.1", change( "one" ) );
        assertDoesNotThrow( () -> limiter.checkSinglePageChange( context, change( "two" ) ) );

        assertThrows( RedirectException.class, () -> limiter.checkSinglePageChange( context, change( "three" ) ),
                "two prior changes from this address within the minute reach pagechangesinminute=2" );
    }

    @Test
    void anotherAddressesRecordedModificationsDoNotCountAgainstThisOne() {
        final DefaultSpamRateLimiter limiter = limiter( 2, 999, 999 );
        limiter.recordModification( "192.0.2.7", change( "one" ) );
        limiter.recordModification( "192.0.2.7", change( "two" ) );

        assertDoesNotThrow( () -> limiter.checkSinglePageChange( context, change( "mine" ) ) );
    }

    // ---- DefaultSpamPatternMatcher ----------------------------------------------------------------------------------

    private PageManager pageManager;
    private AttachmentManager attachmentManager;
    private DefaultSpamPatternMatcher matcher;

    private void matcherSetUp() {
        pageManager = mock( PageManager.class );
        attachmentManager = mock( AttachmentManager.class );
        final Properties props = new Properties();
        props.setProperty( "wordlist", WORDS_PAGE );
        props.setProperty( "IPlist", IPS_PAGE );
        props.setProperty( "maxpagenamelength", "50" );
        props.setProperty( "blacklist", BLACKLIST );
        props.setProperty( "errorpage", "RejectedMessage" );
        matcher = new DefaultSpamPatternMatcher( pageManager, attachmentManager, props, true );
    }

    private static Page listPage( final String name, final String attribute, final String value, final long modified ) {
        final Page p = mock( Page.class );
        when( p.getName() ).thenReturn( name );
        when( p.getLastModified() ).thenReturn( new Date( modified ) );
        when( p.getAttribute( attribute ) ).thenReturn( value );
        return p;
    }

    private void servePage( final String name, final Page served ) {
        when( pageManager.getPage( name ) ).thenReturn( served );
    }

    private Attachment blacklistAttachment( final String content, final long modified ) throws Exception {
        final Attachment att = mock( Attachment.class );
        when( att.getLastModified() ).thenReturn( new Date( modified ) );
        when( attachmentManager.getAttachmentInfo( any(), eq( BLACKLIST ) ) ).thenReturn( att );
        when( attachmentManager.getAttachmentStream( att ) )
                .thenAnswer( inv -> new ByteArrayInputStream( content.getBytes( StandardCharsets.UTF_8 ) ) );
        return att;
    }

    private static String eq( final String s ) {
        return org.mockito.ArgumentMatchers.eq( s );
    }

    private boolean spam( final String text ) {
        try {
            matcher.checkPatternList( context, text );
            return false;
        } catch( final RedirectException e ) {
            return true;
        }
    }

    private boolean bannedIp( final String ip ) {
        when( request.getRemoteAddr() ).thenReturn( ip );
        try {
            matcher.checkIPList( context );
            return false;
        } catch( final RedirectException e ) {
            return true;
        }
    }

    private static final long LONG_AGO = 1_000_000L;
    private static final long FAR_FUTURE = System.currentTimeMillis() + 3_600_000L;

    @Test
    void aWordListPageChangedAfterTheLastReloadReplacesTheOldPatterns() {
        matcherSetUp();
        servePage( WORDS_PAGE, listPage( WORDS_PAGE, "spamwords", "old-word", LONG_AGO ) );
        matcher.refreshBlacklists( context );
        assertTrue( spam( "an old-word here" ) );

        servePage( WORDS_PAGE, listPage( WORDS_PAGE, "spamwords", "new-word", FAR_FUTURE ) );
        matcher.refreshBlacklists( context );

        assertTrue( spam( "a new-word here" ), "the changed page is reloaded" );
        assertTrue( !spam( "an old-word here" ), "and the old patterns are gone" );
    }

    @Test
    void anUnchangedWordListPageIsNotReloaded() {
        matcherSetUp();
        servePage( WORDS_PAGE, listPage( WORDS_PAGE, "spamwords", "old-word", LONG_AGO ) );
        matcher.refreshBlacklists( context );

        // same modification date: the page is not read again, whatever its attribute now says
        servePage( WORDS_PAGE, listPage( WORDS_PAGE, "spamwords", "new-word", LONG_AGO ) );
        matcher.refreshBlacklists( context );

        assertTrue( spam( "an old-word here" ) );
        assertTrue( !spam( "a new-word here" ) );
    }

    @Test
    void anEmptyWordListIsReloadedOnEveryRefreshSoARepairedPageTakesEffect() {
        matcherSetUp();
        servePage( WORDS_PAGE, listPage( WORDS_PAGE, "spamwords", "", LONG_AGO ) );
        matcher.refreshBlacklists( context );
        assertTrue( !spam( "late-word" ) );

        servePage( WORDS_PAGE, listPage( WORDS_PAGE, "spamwords", "late-word", LONG_AGO ) );
        matcher.refreshBlacklists( context );

        assertTrue( spam( "a late-word here" ), "no patterns loaded yet means the page is read again" );
    }

    @Test
    void aBlacklistAttachmentAloneLoadsItsPatterns() throws Exception {
        matcherSetUp();
        blacklistAttachment( "# comment\nbad-attachment-word\n", LONG_AGO );

        matcher.refreshBlacklists( context );

        assertTrue( spam( "has bad-attachment-word inside" ) );
        assertTrue( !spam( "comment" ), "the # line is a comment, not a pattern" );
    }

    @Test
    void aBlacklistAttachmentChangedAfterTheLastReloadIsLoadedAgain() throws Exception {
        matcherSetUp();
        servePage( WORDS_PAGE, listPage( WORDS_PAGE, "spamwords", "page-word", LONG_AGO ) );
        blacklistAttachment( "first-list-word\n", LONG_AGO );
        matcher.refreshBlacklists( context );
        assertTrue( spam( "first-list-word" ) );
        assertTrue( spam( "page-word" ) );

        blacklistAttachment( "second-list-word\n", FAR_FUTURE );
        matcher.refreshBlacklists( context );

        assertTrue( spam( "second-list-word" ), "the newer attachment is read" );
        assertTrue( !spam( "first-list-word" ) );
        assertTrue( spam( "page-word" ), "the page's own words are kept alongside" );
    }

    @Test
    void anUnchangedBlacklistAttachmentIsNotReadAgain() throws Exception {
        matcherSetUp();
        servePage( WORDS_PAGE, listPage( WORDS_PAGE, "spamwords", "page-word", LONG_AGO ) );
        blacklistAttachment( "first-list-word\n", LONG_AGO );
        matcher.refreshBlacklists( context );

        blacklistAttachment( "second-list-word\n", LONG_AGO );
        matcher.refreshBlacklists( context );

        assertTrue( spam( "first-list-word" ) );
        assertTrue( !spam( "second-list-word" ) );
    }

    @Test
    void anIpListPageAloneLoadsItsPatternsAndIsReloadedWhenItChanges() {
        matcherSetUp();
        servePage( IPS_PAGE, listPage( IPS_PAGE, "ips", "^203\\.0\\.113\\.", LONG_AGO ) );
        matcher.refreshBlacklists( context );
        assertTrue( bannedIp( "203.0.113.9" ) );
        assertTrue( !bannedIp( "198.51.100.1" ) );

        servePage( IPS_PAGE, listPage( IPS_PAGE, "ips", "^198\\.51\\.100\\.", FAR_FUTURE ) );
        matcher.refreshBlacklists( context );

        assertTrue( bannedIp( "198.51.100.1" ), "the changed IP page is reloaded" );
        assertTrue( !bannedIp( "203.0.113.9" ) );
    }

    @Test
    void anUnchangedIpListPageIsNotReloaded() {
        matcherSetUp();
        servePage( IPS_PAGE, listPage( IPS_PAGE, "ips", "^203\\.0\\.113\\.", LONG_AGO ) );
        matcher.refreshBlacklists( context );

        servePage( IPS_PAGE, listPage( IPS_PAGE, "ips", "^198\\.51\\.100\\.", LONG_AGO ) );
        matcher.refreshBlacklists( context );

        assertTrue( bannedIp( "203.0.113.9" ) );
        assertTrue( !bannedIp( "198.51.100.1" ) );
    }

    @Test
    void aMalformedPatternIsReportedOnTheListPageThatHoldsIt() {
        matcherSetUp();
        final Page words = listPage( WORDS_PAGE, "spamwords", "fine-word [broken", LONG_AGO );
        when( pageManager.getPage( WORDS_PAGE ) ).thenReturn( words );

        matcher.refreshBlacklists( context );

        verify( words ).setAttribute( "error", "Malformed spam filter pattern [broken" );
        assertTrue( spam( "fine-word" ) );
    }

    @Test
    void blacklistLinesAreTrimmedCutAtTheFirstBlankAndBlankOrCommentLinesAreSkipped() throws Exception {
        matcherSetUp();
        blacklistAttachment( "\n   \n#skipped-comment\n  spaced-word   trailing note\ntabbed-word\tnote-after-tab\n[broken\nplain-word\n", LONG_AGO );

        matcher.refreshBlacklists( context );

        assertTrue( spam( "spaced-word" ) );
        assertTrue( spam( "tabbed-word" ) );
        assertTrue( spam( "plain-word" ) );
        assertTrue( !spam( "trailing" ), "text after the first blank is a note, not a pattern" );
        assertTrue( !spam( "note-after-tab" ) );
        assertTrue( !spam( "skipped-comment" ) );
    }

    @Test
    void aSaveWithNoTextChangeYieldsAnEmptyChangeNotANullOne() throws Exception {
        matcherSetUp();
        when( pageManager.getPureText( "TestPage", WikiProvider.LATEST_VERSION ) ).thenReturn( "same text\n" );
        when( page.getAuthor() ).thenReturn( null );
        when( page.getAttribute( Page.CHANGENOTE ) ).thenReturn( null );

        final SpamChange unchanged = matcher.getChange( context, "same text\n" );

        assertEquals( "", unchanged.change, "no difference means an empty change, never a null one" );
    }

    @Test
    void aNoOpSaveByANonAdminDoesNotCrashTheRateLimiter() throws Exception {
        matcherSetUp();
        when( pageManager.getPureText( "TestPage", WikiProvider.LATEST_VERSION ) ).thenReturn( "same text\n" );
        when( page.getAuthor() ).thenReturn( null );
        when( page.getAttribute( Page.CHANGENOTE ) ).thenReturn( null );
        final SpamChange unchanged = matcher.getChange( context, "same text\n" );

        assertDoesNotThrow( () -> limiter( 999, 999, 10 ).checkSinglePageChange( context, unchanged ),
                "SpamFilter.preSave hands getChange's result straight to the rate limiter" );
    }

    @Test
    void getChangeCountsAddedAndRemovedBlocksAndCollectsOnlyTheAddedText() throws Exception {
        matcherSetUp();
        when( pageManager.getPureText( "TestPage", WikiProvider.LATEST_VERSION ) ).thenReturn( "keep\nremove me\n" );
        when( page.getAuthor() ).thenReturn( null );
        when( page.getAttribute( Page.CHANGENOTE ) ).thenReturn( null );

        final SpamChange ch = matcher.getChange( context, "keep\n" );

        assertEquals( 0, ch.adds );
        assertEquals( 1, ch.removals );
        assertEquals( "", ch.change, "a pure deletion adds no text" );

        final SpamChange added = matcher.getChange( context, "keep\nremove me\nbrand new\n" );
        assertEquals( 1, added.adds );
        assertEquals( 0, added.removals );
        assertTrue( added.change.contains( "brand new" ), added.change );
        assertTrue( !added.change.contains( "keep" ), "unchanged lines are not part of the change: " + added.change );
    }

    // ---- DefaultSpamExternalSignals ---------------------------------------------------------------------------------

    private AkismetComment akismetComment( final int adds, final int removals ) {
        final AkismetGateway gateway = mock( AkismetGateway.class );
        when( gateway.verifyKey() ).thenReturn( true );
        when( gateway.checkComment( any() ) ).thenReturn( false );
        final Properties props = new Properties();
        props.setProperty( "akismet-apikey", "key" );
        final DefaultSpamExternalSignals signals =
                new DefaultSpamExternalSignals( props, true, "RejectedMessage", ( key, url ) -> gateway );

        final com.wikantik.api.core.Engine engine = mock( com.wikantik.api.core.Engine.class );
        when( engine.getBaseURL() ).thenReturn( "http://localhost:8080/" );
        when( request.getRequestURL() ).thenReturn( new StringBuffer( "http://localhost:8080/edit/TestPage" ) );
        when( request.getHeader( "User-Agent" ) ).thenReturn( "TestAgent/1.0" );
        when( request.getHeader( "Referer" ) ).thenReturn( "http://localhost:8080/from" );
        when( context.getEngine() ).thenReturn( engine );
        when( context.getViewURL( anyString() ) ).thenReturn( "http://localhost:8080/TestPage" );
        when( context.getCurrentUser() ).thenReturn( ( Principal ) () -> "Fred" );
        when( context.getRequestContext() ).thenReturn( ContextEnum.PAGE_EDIT.getRequestContext() );

        final SpamChange ch = change( "the edit text" );
        ch.adds = adds;
        ch.removals = removals;
        assertDoesNotThrow( () -> signals.checkAkismet( context, ch ) );

        final ArgumentCaptor< AkismetComment > captor = ArgumentCaptor.forClass( AkismetComment.class );
        verify( gateway, org.mockito.Mockito.atMost( 1 ) ).checkComment( captor.capture() );
        return captor.getAllValues().isEmpty() ? null : captor.getValue();
    }

    @Test
    void akismetIsAskedAboutTheEditWithItsContentPermalinkReferrerAndAuthor() {
        final AkismetComment comment = akismetComment( 1, 0 );

        assertNotNull( comment, "an edit that adds text is sent to Akismet" );
        assertEquals( "the edit text", comment.getContent() );
        assertEquals( "http://localhost:8080/TestPage", comment.getPermalink() );
        assertEquals( "http://localhost:8080/from", comment.getReferrer() );
        assertEquals( "Fred", comment.getAuthor() );
        assertEquals( "10.0.0.1", comment.getUserIp() );
    }

    @Test
    void akismetIsAskedAboutAnEditThatAddsAndRemovesAndAboutOneThatChangesNothing() {
        assertNotNull( akismetComment( 1, 3 ), "adds and removals: sent" );
        assertNotNull( akismetComment( 0, 0 ), "no adds and no removals is not a pure removal: sent" );
    }

    @Test
    void akismetIsNotAskedAboutAPureRemoval() {
        assertEquals( null, akismetComment( 0, 1 ), "Akismet marks every empty statement as spam, so a removal is skipped" );
    }
}
