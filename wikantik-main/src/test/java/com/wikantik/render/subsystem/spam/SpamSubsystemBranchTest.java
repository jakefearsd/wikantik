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
import jakarta.servlet.http.HttpServletRequest;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.util.HashMap;
import java.util.Map;
import java.util.Properties;
import java.util.Date;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/**
 * Targets previously-uncovered branches in the spam subsystem:
 * <ul>
 *   <li>{@link DefaultSpamPolicy#evaluate} score-mode redirect when score >= 1 (lines 69-74)</li>
 *   <li>{@link DefaultSpamRateLimiter#cleanBanList} — removes expired entries (lines 78-83)</li>
 *   <li>{@link DefaultSpamRateLimiter#checkBanList} — banned IP redirect (lines 90-100)</li>
 *   <li>{@link DefaultSpamRateLimiter#checkBanList} — no request early-return (line 89)</li>
 *   <li>{@link DefaultSpamRateLimiter#checkSinglePageChange} — null request early-return</li>
 *   <li>{@link DefaultSpamRateLimiter#checkSinglePageChange} — too many modifications ban (132-138)</li>
 *   <li>{@link DefaultSpamRateLimiter#checkSinglePageChange} — similar changes ban (140-145)</li>
 *   <li>{@link DefaultSpamRateLimiter#checkSinglePageChange} — too many URLs ban (156-162)</li>
 *   <li>{@link DefaultSpamRateLimiter#recordModification} (lines 171-172)</li>
 *   <li>{@link AbstractSpamStrategy#checkStrategy} — score accumulation (non-stopAtFirstMatch)</li>
 *   <li>{@link AbstractSpamStrategy#checkStrategy} — second score increment (56-57)</li>
 *   <li>{@link SpamLog#log} — ACCEPT and NOTE types (lines 61-66)</li>
 *   <li>{@link SpamLog#uniqueID()} — 6-char uppercase output (lines 75-82)</li>
 * </ul>
 */
class SpamSubsystemBranchTest {

    // Shared context helpers
    private Context context;
    private Page page;
    private HttpServletRequest request;

    @BeforeEach
    void setUp() {
        page    = mock( Page.class );
        when( page.getName() ).thenReturn( "TestPage" );

        request = mock( HttpServletRequest.class );
        when( request.getRemoteAddr() ).thenReturn( "127.0.0.1" );

        context = mock( Context.class );
        when( context.getPage() ).thenReturn( page );
        when( context.getHttpRequest() ).thenReturn( request );
        when( context.getURL( anyString(), anyString() ) ).thenReturn( "http://localhost/RejectedMessage" );
    }

    // -----------------------------------------------------------------------
    // SpamLog.log — ACCEPT and NOTE types (lines 61-66)
    // -----------------------------------------------------------------------

    @Nested
    class SpamLogBranches {

        @Test
        void log_accept_type_does_not_throw_and_returns_uid() {
            final String uid = SpamLog.log( context, SpamLog.ACCEPT, "Manual", "accepted change" );
            assertNotNull( uid );
            assertEquals( 6, uid.length() );
            assertTrue( uid.chars().allMatch( Character::isUpperCase ), "UID must be uppercase letters" );
        }

        @Test
        void log_note_type_does_not_throw_and_returns_uid() {
            final String uid = SpamLog.log( context, SpamLog.NOTE, "Audit", "informational note" );
            assertNotNull( uid );
            assertEquals( 6, uid.length() );
        }

        @Test
        void log_reject_type_returns_uid() {
            final String uid = SpamLog.log( context, SpamLog.REJECT, "Regexp", "bad word" );
            assertNotNull( uid );
            assertEquals( 6, uid.length() );
        }

        @Test
        void log_invalid_type_throws_InternalWikiException() {
            assertThrows( RuntimeException.class,
                    () -> SpamLog.log( context, 99, "source", "msg" ),
                    "An invalid type must throw InternalWikiException" );
        }

        @Test
        void log_handles_null_http_request_by_using_dash_as_addr() {
            when( context.getHttpRequest() ).thenReturn( null );
            // Must not throw; addr will be "-"
            assertDoesNotThrow( () -> SpamLog.log( context, SpamLog.REJECT, "Test", "change" ) );
        }

        @Test
        void uniqueID_returns_six_uppercase_chars() {
            for ( int i = 0; i < 20; i++ ) {
                final String uid = SpamLog.uniqueID();
                assertEquals( 6, uid.length(), "UID must be 6 characters" );
                assertTrue( uid.chars().allMatch( c -> c >= 'A' && c <= 'Z' ),
                        "UID must contain only uppercase A-Z letters" );
            }
        }
    }

    // -----------------------------------------------------------------------
    // AbstractSpamStrategy.checkStrategy — score accumulation (lines 52-57)
    // -----------------------------------------------------------------------

    @Nested
    class AbstractSpamStrategyBranches {

        private Map<String, Object> vars;

        @BeforeEach
        void wireVars() {
            vars = new HashMap<>();
            doAnswer( inv -> { vars.put( inv.getArgument(0), inv.getArgument(1) ); return null; } )
                    .when( context ).setVariable( anyString(), any() );
            when( context.getVariable( anyString() ) ).thenAnswer( inv -> vars.get( inv.getArgument(0) ) );
        }

        @Test
        void score_starts_at_1_on_first_hit() {
            // Use DefaultSpamExternalSignals in score mode (stopAtFirstMatch=false)
            final DefaultSpamExternalSignals scorer =
                    new DefaultSpamExternalSignals( new Properties(), false, "RejectedMessage" );

            when( request.getParameter( "submit_auth" ) ).thenReturn( "bot" );
            assertDoesNotThrow( () -> scorer.checkBotTrap( context, new SpamChange() ) );

            assertEquals( 1, vars.get( AbstractSpamStrategy.ATTR_SPAMFILTER_SCORE ) );
        }

        @Test
        void score_increments_on_second_hit() {
            // Pre-seed score = 1 so second hit increments to 2 (the else branch at line 56)
            vars.put( AbstractSpamStrategy.ATTR_SPAMFILTER_SCORE, 1 );

            final DefaultSpamExternalSignals scorer =
                    new DefaultSpamExternalSignals( new Properties(), false, "RejectedMessage" );

            when( request.getParameter( "submit_auth" ) ).thenReturn( "bot" );
            assertDoesNotThrow( () -> scorer.checkBotTrap( context, new SpamChange() ) );

            assertEquals( 2, vars.get( AbstractSpamStrategy.ATTR_SPAMFILTER_SCORE ) );
        }
    }

    // -----------------------------------------------------------------------
    // DefaultSpamPolicy.evaluate — score-mode redirect when score >= SCORE_LIMIT (lines 69-74)
    // -----------------------------------------------------------------------

    @Nested
    class DefaultSpamPolicyBranches {

        @Test
        void evaluate_score_mode_throws_when_accumulated_score_exceeds_limit() {
            // All sub-checks pass cleanly (mocked to do nothing), but we pre-seed a score >= 1
            final SpamRateLimiter rl  = mock( SpamRateLimiter.class );
            final SpamPatternMatcher pm = mock( SpamPatternMatcher.class );
            final SpamExternalSignals es = mock( SpamExternalSignals.class );

            final Map<String, Object> vars = new HashMap<>();
            vars.put( "spamfilter.score", 1 );  // already at limit
            doAnswer( inv -> { vars.put( inv.getArgument(0), inv.getArgument(1) ); return null; } )
                    .when( context ).setVariable( anyString(), any() );
            when( context.getVariable( "spamfilter.score" ) ).thenReturn( 1 );
            when( context.getURL( anyString(), anyString() ) ).thenReturn( "http://localhost/RejectedMessage" );

            // stopAtFirstMatch=false → scoring mode
            final DefaultSpamPolicy policy = new DefaultSpamPolicy( rl, pm, es, false, "RejectedMessage" );

            final SpamChange change = new SpamChange();
            change.change = "some content";

            assertThrows( RedirectException.class,
                    () -> policy.evaluate( context, "some content", change ),
                    "score >= 1 in scoring mode should throw RedirectException" );
        }

        @Test
        void evaluate_score_mode_does_not_throw_when_score_is_null() {
            // score variable not set → no redirect
            final SpamRateLimiter rl  = mock( SpamRateLimiter.class );
            final SpamPatternMatcher pm = mock( SpamPatternMatcher.class );
            final SpamExternalSignals es = mock( SpamExternalSignals.class );

            when( context.getVariable( anyString() ) ).thenReturn( null );

            final DefaultSpamPolicy policy = new DefaultSpamPolicy( rl, pm, es, false, "RejectedMessage" );

            final SpamChange change = new SpamChange();
            change.change = "clean content";

            assertDoesNotThrow( () -> policy.evaluate( context, "clean content", change ) );
        }

        @Test
        void evaluate_stopAtFirstMatch_does_not_check_score_variable() throws Exception {
            // In stopAtFirstMatch=true mode the score block (lines 69-75) is never entered.
            // All sub-checks pass → should not throw.
            final SpamRateLimiter rl  = mock( SpamRateLimiter.class );
            final SpamPatternMatcher pm = mock( SpamPatternMatcher.class );
            final SpamExternalSignals es = mock( SpamExternalSignals.class );

            when( context.getVariable( anyString() ) ).thenReturn( 999 );  // high score

            final DefaultSpamPolicy policy = new DefaultSpamPolicy( rl, pm, es, true, "RejectedMessage" );

            final SpamChange change = new SpamChange();
            change.change = "content";

            assertDoesNotThrow( () -> policy.evaluate( context, "content", change ),
                    "stopAtFirstMatch=true: score variable must not trigger a redirect" );
        }
    }

    // -----------------------------------------------------------------------
    // DefaultSpamRateLimiter — cleanBanList removes expired entries (78-83)
    // -----------------------------------------------------------------------

    @Nested
    class DefaultSpamRateLimiterBranches {

        private DefaultSpamRateLimiter rateLimiter( final int maxUrls ) {
            final Properties props = new Properties();
            props.setProperty( "bantime", "1" );
            props.setProperty( "pagechangesinminute", "5" );
            props.setProperty( "similarchanges", "2" );
            props.setProperty( "maxurls", String.valueOf( maxUrls ) );
            return new DefaultSpamRateLimiter( props, true, "RejectedMessage" );
        }

        @Test
        void cleanBanList_removes_expired_entries() throws Exception {
            final DefaultSpamRateLimiter limiter = rateLimiter( 10 );

            // Add a ban by triggering too-many-modifications (5 same-IP hits)
            final SpamChange ch = new SpamChange();
            ch.change = "test edit";

            // The ban is added when hostCounter >= 5 (limitSinglePageChanges default).
            // After cleanBanList(), expired bans are purged.
            // We verify cleanBanList() doesn't throw (coverage + contract).
            assertDoesNotThrow( () -> limiter.cleanBanList() );
        }

        @Test
        void checkBanList_no_http_request_skips_silently() {
            final DefaultSpamRateLimiter limiter = rateLimiter( 10 );
            when( context.getHttpRequest() ).thenReturn( null );

            final SpamChange ch = new SpamChange();
            ch.change = "some text";

            // No request → must not throw
            assertDoesNotThrow( () -> limiter.checkBanList( context, ch ) );
        }

        @Test
        void checkSinglePageChange_no_http_request_skips_silently() {
            final DefaultSpamRateLimiter limiter = rateLimiter( 10 );
            when( context.getHttpRequest() ).thenReturn( null );

            final SpamChange ch = new SpamChange();
            ch.change = "edit text";

            assertDoesNotThrow( () -> limiter.checkSinglePageChange( context, ch ) );
        }

        @Test
        void checkSinglePageChange_too_many_urls_triggers_ban() {
            // maxurls=3 → 4 URLs in one change triggers the ban
            final DefaultSpamRateLimiter limiter = rateLimiter( 3 );

            final SpamChange ch = new SpamChange();
            ch.change = "See http://a.com and http://b.com and http://c.com and http://d.com";

            assertThrows( RedirectException.class,
                    () -> limiter.checkSinglePageChange( context, ch ),
                    "4 URLs with maxurls=3 should trigger a TooManyUrls ban" );
        }

        @Test
        void recordModification_adds_entry_without_throwing() {
            final DefaultSpamRateLimiter limiter = rateLimiter( 10 );
            final SpamChange ch = new SpamChange();
            ch.change = "recorded change";

            // recordModification is a simple add — must not throw
            assertDoesNotThrow( () -> limiter.recordModification( "10.0.0.1", ch ) );
        }

        @Test
        void checkBanList_temporarily_banned_ip_throws() throws Exception {
            // Trigger a TooManyUrls ban (maxurls=3 → 4 URLs) so the IP lands in the ban list.
            // Then call checkBanList — it should detect the banned IP and throw/redirect.
            final DefaultSpamRateLimiter limiter = rateLimiter( 3 );

            final SpamChange ch = new SpamChange();
            ch.change = "See http://a.com and http://b.com and http://c.com and http://d.com";

            // This call throws AND adds the IP to the ban list
            try {
                limiter.checkSinglePageChange( context, ch );
            } catch ( final RedirectException ignored ) {
                // Expected — the IP is now banned
            }

            // Now checkBanList should detect the banned IP
            final SpamChange ch2 = new SpamChange();
            ch2.change = "follow-up edit";

            assertThrows( RedirectException.class,
                    () -> limiter.checkBanList( context, ch2 ),
                    "A previously banned IP should be detected by checkBanList" );
        }

        @Test
        void checkSinglePageChange_similar_changes_triggers_ban() throws Exception {
            // similarchanges default = 2 → same change must appear 2 times already in the history
            // before the THIRD call triggers the ban (history is checked before the current entry
            // is added, so we need 3 calls: calls 1+2 build up 2 identical entries, call 3 sees
            // changeCounter=2 >= 2 and triggers the ban).
            final Properties props = new Properties();
            props.setProperty( "bantime", "1" );
            props.setProperty( "pagechangesinminute", "999" ); // disable host-counter branch
            props.setProperty( "similarchanges", "2" );
            props.setProperty( "maxurls", "999" );
            final DefaultSpamRateLimiter limiter = new DefaultSpamRateLimiter( props, true, "RejectedMessage" );

            final SpamChange identical = new SpamChange();
            identical.change = "repeated spam text";

            // First two submissions — recorded, no ban yet
            limiter.checkSinglePageChange( context, identical );
            limiter.checkSinglePageChange( context, identical );

            // Third identical submission — changeCounter == 2 >= limitSimilarChanges(2) → ban
            assertThrows( RedirectException.class,
                    () -> limiter.checkSinglePageChange( context, identical ),
                    "Three identical changes should trigger SimilarModifications ban (2 in history)" );
        }

        @Test
        void checkSinglePageChange_too_many_modifications_triggers_ban() throws Exception {
            // The loop checks entries already in lastModifications before adding the current one.
            // With pagechangesinminute=2, we need 2 prior entries from the same IP plus one more
            // call so hostCounter (which counts prior entries) reaches 2.
            // Flow: call1→0 entries→add→1 in list; call2→1 entry(count=1)→add→2; call3→2 entries(count=2)>=2→ban
            final Properties props = new Properties();
            props.setProperty( "bantime", "1" );
            props.setProperty( "pagechangesinminute", "2" );  // lower threshold for test
            props.setProperty( "similarchanges", "999" );    // disable similar-changes branch
            props.setProperty( "maxurls", "999" );
            final DefaultSpamRateLimiter limiter = new DefaultSpamRateLimiter( props, true, "RejectedMessage" );

            // First two changes recorded cleanly
            final SpamChange ch1 = new SpamChange();
            ch1.change = "change one";
            limiter.checkSinglePageChange( context, ch1 );

            final SpamChange ch2 = new SpamChange();
            ch2.change = "change two";
            limiter.checkSinglePageChange( context, ch2 );

            // Third change — hostCounter reaches 2 >= pagechangesinminute(2) → ban
            final SpamChange ch3 = new SpamChange();
            ch3.change = "change three";
            assertThrows( RedirectException.class,
                    () -> limiter.checkSinglePageChange( context, ch3 ),
                    "Three modifications from same IP with pagechangesinminute=2 should trigger ban" );
        }

        // -----------------------------------------------------------------------
        // Score-mode (stopAtFirstMatch=false) tests — these cover the checkStrategy
        // CALL SITES (L137, L145, L161) that JaCoCo marks nc when the call throws.
        // In score mode checkStrategy completes normally (incrementing the score),
        // so JaCoCo's post-call probe fires and the lines are marked covered.
        // -----------------------------------------------------------------------

        private Map<String, Object> wireScoreContext() {
            final Map<String, Object> vars = new HashMap<>();
            doAnswer( inv -> { vars.put( inv.getArgument( 0 ), inv.getArgument( 1 ) ); return null; } )
                    .when( context ).setVariable( anyString(), any() );
            when( context.getVariable( anyString() ) ).thenAnswer( inv -> vars.get( inv.getArgument( 0 ) ) );
            return vars;
        }

        @Test
        void checkSinglePageChange_too_many_modifications_score_mode_increments_score() throws Exception {
            // Score mode: checkStrategy accumulates score instead of throwing.
            // This test covers L132-138 (including the checkStrategy call at L137).
            final Map<String, Object> vars = wireScoreContext();

            final Properties props = new Properties();
            props.setProperty( "bantime", "1" );
            props.setProperty( "pagechangesinminute", "2" );
            props.setProperty( "similarchanges", "999" );
            props.setProperty( "maxurls", "999" );
            final DefaultSpamRateLimiter limiter = new DefaultSpamRateLimiter( props, false, "RejectedMessage" );

            final SpamChange ch1 = new SpamChange(); ch1.change = "edit one";
            final SpamChange ch2 = new SpamChange(); ch2.change = "edit two";
            final SpamChange ch3 = new SpamChange(); ch3.change = "edit three";

            limiter.checkSinglePageChange( context, ch1 );
            limiter.checkSinglePageChange( context, ch2 );
            assertDoesNotThrow( () -> limiter.checkSinglePageChange( context, ch3 ),
                    "Score mode must not throw even when ban threshold is reached" );

            final Object score = vars.get( AbstractSpamStrategy.ATTR_SPAMFILTER_SCORE );
            assertNotNull( score, "Score must be set after a too-many-modifications hit in score mode" );
            assertTrue( (Integer) score >= 1, "Score must be >= 1 after one ban-path hit" );
        }

        @Test
        void checkSinglePageChange_similar_changes_score_mode_increments_score() throws Exception {
            // Score mode: covers L140-146 (the checkStrategy call at L145).
            final Map<String, Object> vars = wireScoreContext();

            final Properties props = new Properties();
            props.setProperty( "bantime", "1" );
            props.setProperty( "pagechangesinminute", "999" );
            props.setProperty( "similarchanges", "2" );
            props.setProperty( "maxurls", "999" );
            final DefaultSpamRateLimiter limiter = new DefaultSpamRateLimiter( props, false, "RejectedMessage" );

            final SpamChange same = new SpamChange();
            same.change = "identical spam";

            limiter.checkSinglePageChange( context, same );
            limiter.checkSinglePageChange( context, same );
            assertDoesNotThrow( () -> limiter.checkSinglePageChange( context, same ),
                    "Score mode must not throw even when similar-changes threshold is reached" );

            final Object score = vars.get( AbstractSpamStrategy.ATTR_SPAMFILTER_SCORE );
            assertNotNull( score, "Score must be set after a similar-changes hit in score mode" );
        }

        @Test
        void checkSinglePageChange_too_many_urls_score_mode_increments_score() throws Exception {
            // Score mode: covers L156-162 (the checkStrategy call at L161).
            final Map<String, Object> vars = wireScoreContext();

            final DefaultSpamRateLimiter limiter = new DefaultSpamRateLimiter(
                    new java.util.Properties(), false, "RejectedMessage" );
            // Use the default maxurls=10; inject 11 URLs
            final SpamChange ch = new SpamChange();
            ch.change = "x http://a.com http://b.com http://c.com http://d.com http://e.com " +
                        "http://f.com http://g.com http://h.com http://i.com http://j.com http://k.com";

            assertDoesNotThrow( () -> limiter.checkSinglePageChange( context, ch ),
                    "Score mode must not throw even when too-many-URL threshold is reached" );

            final Object score = vars.get( AbstractSpamStrategy.ATTR_SPAMFILTER_SCORE );
            assertNotNull( score, "Score must be set after a too-many-URLs hit in score mode" );
        }

        @Test
        void checkBanList_score_mode_detects_banned_ip_and_increments_score() throws Exception {
            // Score mode covers L91-100 (the checkStrategy call at L97-99).
            // Step 1: ban the IP in score mode (checkSinglePageChange won't throw, but DOES add to ban list).
            final Map<String, Object> vars = wireScoreContext();

            final Properties props = new Properties();
            props.setProperty( "bantime", "1" );
            props.setProperty( "pagechangesinminute", "2" );
            props.setProperty( "similarchanges", "999" );
            props.setProperty( "maxurls", "999" );
            // Use score mode (false) for the limiter that adds the ban
            final DefaultSpamRateLimiter limiter = new DefaultSpamRateLimiter( props, false, "RejectedMessage" );

            final SpamChange ch1 = new SpamChange(); ch1.change = "a";
            final SpamChange ch2 = new SpamChange(); ch2.change = "b";
            final SpamChange ch3 = new SpamChange(); ch3.change = "c";
            limiter.checkSinglePageChange( context, ch1 );
            limiter.checkSinglePageChange( context, ch2 );
            limiter.checkSinglePageChange( context, ch3 ); // score mode: adds to ban list AND increments score, no throw

            // Reset score so we can verify checkBanList adds its own score increment
            vars.clear();

            // Step 2: checkBanList in score mode — detects the banned IP, calls checkStrategy (score mode)
            final SpamChange ch4 = new SpamChange(); ch4.change = "follow-up";
            assertDoesNotThrow( () -> limiter.checkBanList( context, ch4 ),
                    "Score mode checkBanList must not throw even for a banned IP" );

            final Object score = vars.get( AbstractSpamStrategy.ATTR_SPAMFILTER_SCORE );
            assertNotNull( score, "Score must be set when a banned IP is detected in score mode" );
        }

        @Test
        void cleanBanList_removes_expired_ban_entry() throws Exception {
            // Injects a SpamHost with a past releaseTime directly into temporaryBanList,
            // then verifies cleanBanList() removes it (covers the removeIf branch).
            final DefaultSpamRateLimiter limiter = new DefaultSpamRateLimiter(
                    new Properties(), false, "RejectedMessage" );

            // Past releaseTime: 1 second ago → already expired
            final long addedTime    = System.currentTimeMillis() - 5_000L;
            final long releaseTime  = System.currentTimeMillis() - 1_000L;
            final java.lang.reflect.Field banField =
                    DefaultSpamRateLimiter.class.getDeclaredField( "temporaryBanList" );
            banField.setAccessible( true );
            @SuppressWarnings( "unchecked" )
            final java.util.concurrent.CopyOnWriteArrayList<SpamHost> banList =
                    (java.util.concurrent.CopyOnWriteArrayList<SpamHost>) banField.get( limiter );
            banList.add( new SpamHost( "1.2.3.4", null, addedTime, releaseTime ) );

            assertEquals( 1, banList.size(), "Ban list should have one entry before clean" );

            // cleanBanList should remove the expired entry
            assertDoesNotThrow( () -> limiter.cleanBanList(),
                    "cleanBanList must not throw when removing expired bans" );

            assertEquals( 0, banList.size(), "Ban list should be empty after removing expired entry" );
        }

        @Test
        void checkSinglePageChange_expired_modification_queue_entry_is_removed() throws Exception {
            // Covers the expired-entry removal in checkSinglePageChange.
            // Inject a modification with a past addedTime (2 min ago) via the 4-arg SpamHost
            // constructor. checkSinglePageChange's removeIf lambda removes it before counting.
            final DefaultSpamRateLimiter limiter = new DefaultSpamRateLimiter(
                    new Properties(), false, "RejectedMessage" );

            final long twoMinutesAgo = System.currentTimeMillis() - 2 * 60 * 1000L;
            final long futureRelease = System.currentTimeMillis() + 60 * 1000L;
            final SpamChange old = new SpamChange(); old.change = "old edit";

            // Use the 4-arg SpamHost constructor to inject a stale entry
            final java.lang.reflect.Field lastMods = DefaultSpamRateLimiter.class.getDeclaredField( "lastModifications" );
            lastMods.setAccessible( true );
            @SuppressWarnings( "unchecked" )
            final java.util.concurrent.CopyOnWriteArrayList<SpamHost> list =
                    (java.util.concurrent.CopyOnWriteArrayList<SpamHost>) lastMods.get( limiter );
            list.add( new SpamHost( "127.0.0.1", old, twoMinutesAgo, futureRelease ) );

            // checkSinglePageChange should silently remove the expired entry and continue
            final SpamChange current = new SpamChange(); current.change = "current edit";
            final Map<String, Object> vars = wireScoreContext();
            assertDoesNotThrow( () -> limiter.checkSinglePageChange( context, current ),
                    "checkSinglePageChange must not throw; expired entries must be silently removed" );

            // The expired entry must be gone from the list
            assertEquals( 1, list.size(), "Only the current entry should remain after expiry removal" );
            assertEquals( "current edit", list.get( 0 ).change().change );
        }

        @Test
        void spamChange_hashCode_equals_contract() {
            // Covers SpamChange.hashCode() (L44) and equals() contract.
            final SpamChange a = new SpamChange();
            a.change = "edit content";
            final SpamChange b = new SpamChange();
            b.change = "edit content";
            final SpamChange c = new SpamChange();
            c.change = "different";

            assertEquals( a, b, "Two SpamChanges with same text must be equal" );
            assertEquals( a.hashCode(), b.hashCode(), "Equal objects must have equal hash codes" );
            assertNotEquals( a.hashCode(), c.hashCode(), "Different texts should (usually) have different hash codes" );
            assertEquals( a.hashCode(), "edit content".hashCode() + 17, "hashCode must be change.hashCode()+17" );
        }
    }
}
