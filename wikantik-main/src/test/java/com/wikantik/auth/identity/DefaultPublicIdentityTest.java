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
package com.wikantik.auth.identity;

import com.wikantik.api.identity.PublicIdentity;
import com.wikantik.auth.WikiSecurityException;
import com.wikantik.auth.user.AccountSummary;
import com.wikantik.auth.user.UserDatabase;
import com.wikantik.auth.user.UserProfile;
import com.wikantik.event.WikiSecurityEvent;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;

class DefaultPublicIdentityTest {

    /** A clock the test moves by hand. */
    private static final class TestClock extends Clock {
        private Instant now = Instant.parse( "2026-10-13T00:00:00Z" );

        void advance( final Duration d ) {
            now = now.plus( d );
        }

        @Override public ZoneId getZone() {
            return ZoneOffset.UTC;
        }

        @Override public Clock withZone( final ZoneId zone ) {
            return this;
        }

        @Override public Instant instant() {
            return now;
        }
    }

    private UserDatabase db;
    private TestClock clock;
    private DefaultPublicIdentity identity;
    private final List< AccountSummary > accounts = new ArrayList<>();

    @BeforeEach
    void setUp() throws Exception {
        db = Mockito.mock( UserDatabase.class );
        clock = new TestClock();
        identity = new DefaultPublicIdentity( db, clock );
        publish();
    }

    /** Makes the mock database report the current {@code accounts}. */
    private void publish() throws Exception {
        doReturn( List.copyOf( accounts ) ).when( db ).accountSummaries();
    }

    private void account( final String login, final String fullName, final String uid ) throws Exception {
        noAccount( login );
        accounts.add( new AccountSummary( login, fullName, uid ) );
        publish();
    }

    private void noAccount( final String login ) throws Exception {
        accounts.removeIf( a -> a.login().equals( login ) );
        publish();
    }

    // ---- displayName ----

    @Test
    void aLoginResolvesToTheCurrentFullName() throws Exception {
        account( "jane@example.com", "Jane Doe", "u1" );
        assertEquals( "Jane Doe", identity.displayName( "jane@example.com" ) );
        assertEquals( "Jane Doe", identity.displayName( "  jane@example.com " ), "the stored value is trimmed" );
    }

    @Test
    void legacyFullNamesAndAgentLabelsPassThroughUnchanged() {
        for ( final String stored : new String[] { "Jake Fear", "Guest", "unknown", "Claude (agent)", "Knowledge Admin" } ) {
            assertEquals( stored, identity.displayName( stored ) );
        }
    }

    @Test
    void anEmailShapedValueThatIsNotAnAccountIsMasked() {
        assertEquals( PublicIdentity.UNKNOWN_CONTRIBUTOR, identity.displayName( "ghost@example.com" ) );
    }

    @Test
    void anAccountWhoseStoredNameIsBlankOrEmailShapedIsMasked() throws Exception {
        account( "a@b.example", "a@b.example", "u1" );
        account( "blankname", "   ", "u2" );
        account( "nullname", null, "u3" );
        assertEquals( PublicIdentity.UNKNOWN_CONTRIBUTOR, identity.displayName( "a@b.example" ) );
        assertEquals( PublicIdentity.UNKNOWN_CONTRIBUTOR, identity.displayName( "blankname" ),
                "an account with no display name must not fall back to its login" );
        assertEquals( PublicIdentity.UNKNOWN_CONTRIBUTOR, identity.displayName( "nullname" ) );
    }

    @Test
    void nullAndBlankAreUnknown() {
        assertEquals( PublicIdentity.UNKNOWN, identity.displayName( null ) );
        assertEquals( PublicIdentity.UNKNOWN, identity.displayName( "" ) );
        assertEquals( PublicIdentity.UNKNOWN, identity.displayName( "   " ) );
    }

    @Test
    void onlyAnExactCaseLoginMatchResolves() throws Exception {
        account( "Fred", "Fred Flintstone", "u-fred" );
        noAccount( "fred" );
        assertEquals( "fred", identity.displayName( "fred" ), "a case variant of a login is not that account" );
        assertEquals( "Fred Flintstone", identity.displayName( "Fred" ) );
    }

    @Test
    void aDeletedAccountsEmailLoginIsMasked() throws Exception {
        noAccount( "gone@example.test" );
        assertEquals( PublicIdentity.UNKNOWN_CONTRIBUTOR, identity.displayName( "gone@example.test" ) );
    }

    // ---- snapshot lifetime ----

    @Test
    void oneQueryServesEveryLookupIncludingMissesForSixtySeconds() throws Exception {
        account( "jane", "Jane Doe", "u1" );
        for ( int i = 0; i < 50; i++ ) {
            identity.displayName( "jane" );
            identity.displayName( "Jake Fear" );   // not an account: a negative answer, also from memory
            identity.userIdForLogin( "jane" );
            identity.loginForDisplayName( "jane doe" );
        }
        verify( db, times( 1 ) ).accountSummaries();
    }

    @Test
    void theSnapshotExpiresAfterSixtySecondsOnTheInjectedClock() throws Exception {
        account( "jane", "Jane Doe", "u1" );
        assertEquals( "Jane Doe", identity.displayName( "jane" ) );
        doReturn( List.of( new AccountSummary( "jane", "Jane Roe", "u1" ) ) ).when( db ).accountSummaries();

        clock.advance( Duration.ofSeconds( 59 ) );
        assertEquals( "Jane Doe", identity.displayName( "jane" ), "still cached at 59 s" );
        verify( db, times( 1 ) ).accountSummaries();

        clock.advance( Duration.ofSeconds( 1 ) );
        assertEquals( "Jane Roe", identity.displayName( "jane" ), "expired at 60 s: the rename is seen" );
        verify( db, times( 2 ) ).accountSummaries();
    }

    @Test
    void invalidateDropsTheSnapshotForOneLoginOrEverything() throws Exception {
        account( "jane", "Jane Doe", "u1" );
        identity.displayName( "jane" );
        doReturn( List.of( new AccountSummary( "jane", "Jane Roe", "u1" ) ) ).when( db ).accountSummaries();

        identity.invalidate( "jane" );
        assertEquals( "Jane Roe", identity.displayName( "jane" ) );
        identity.invalidate( null );
        identity.displayName( "jane" );
        verify( db, times( 3 ) ).accountSummaries();
    }

    @Test
    void anInvalidationDuringALoadKeepsThatLoadsPossiblyStaleResultOutOfTheCache() throws Exception {
        account( "jane", "Jane Doe", "u1" );
        // The profile is saved (and invalidated) while the query is in flight: what that query read may predate the save.
        doAnswer( inv -> {
            identity.invalidate( "jane" );
            return List.of( new AccountSummary( "jane", "Jane Doe", "u1" ) );
        } ).doReturn( List.of( new AccountSummary( "jane", "Jane Roe", "u1" ) ) ).when( db ).accountSummaries();

        assertEquals( "Jane Doe", identity.displayName( "jane" ), "this call is answered from what the query read" );
        assertEquals( "Jane Roe", identity.displayName( "jane" ), "but that snapshot was not kept: the next call reloads" );
        verify( db, times( 2 ) ).accountSummaries();
    }

    // ---- uid / login / display-name lookups ----

    @Test
    void userIdForLoginReadsTheStoredUidAndIsEmptyWhenItIsNull() throws Exception {
        account( "jane", "Jane Doe", "uid-1" );
        account( "nouid", "No Uid", null );   // users.uid NULL: findByLoginName would invent a random one — never exposed here
        assertEquals( Optional.of( "uid-1" ), identity.userIdForLogin( "jane" ) );
        assertEquals( Optional.empty(), identity.userIdForLogin( "nouid" ) );
        assertEquals( Optional.empty(), identity.userIdForLogin( "ghost" ) );
        assertEquals( Optional.empty(), identity.userIdForLogin( null ) );
        assertEquals( Optional.empty(), identity.userIdForLogin( "  " ) );
    }

    @Test
    void aBlankStoredUidIsNotAnIdentifier() throws Exception {
        account( "blankuid", "Blank Uid", "  " );
        assertEquals( Optional.empty(), identity.userIdForLogin( "blankuid" ) );
        assertEquals( Optional.empty(), identity.loginForUserId( "  " ) );
    }

    @Test
    void loginForUserId() throws Exception {
        account( "jane", "Jane Doe", "uid-1" );
        assertEquals( Optional.of( "jane" ), identity.loginForUserId( "uid-1" ) );
        assertEquals( Optional.of( "jane" ), identity.loginForUserId( " uid-1 " ) );
        assertEquals( Optional.empty(), identity.loginForUserId( "nope" ) );
        assertEquals( Optional.empty(), identity.loginForUserId( null ) );
    }

    @Test
    void loginForDisplayNameIsCaseInsensitiveAndTrimmed() throws Exception {
        account( "jane", "Jane Doe", "u1" );
        account( "bob", "Bob Roe", "u2" );
        assertEquals( Optional.of( "jane" ), identity.loginForDisplayName( "  jane DOE " ) );
        assertEquals( Optional.of( "jane" ), identity.loginForDisplayName( "Jane Doe" ) );
        assertEquals( Optional.empty(), identity.loginForDisplayName( "Nobody Atall" ) );
        assertEquals( Optional.empty(), identity.loginForDisplayName( "  " ) );
        assertEquals( Optional.empty(), identity.loginForDisplayName( null ) );
    }

    @Test
    void anAmbiguousDisplayNameResolvesToNobody() throws Exception {
        account( "jane", "Same Name", "u1" );
        account( "bob", "same name", "u2" );
        assertEquals( Optional.empty(), identity.loginForDisplayName( "Same Name" ), "refuse to guess between two accounts" );
    }

    // ---- failures ----

    @Test
    void aDatabaseFailureFallsBackWithoutThrowingAndRetriesAfterAShortBackoff() throws Exception {
        doThrow( new WikiSecurityException( "connection refused" ) ).when( db ).accountSummaries();

        assertEquals( PublicIdentity.UNKNOWN, assertDoesNotThrow( () -> identity.displayName( "jane" ) ) );
        assertEquals( PublicIdentity.UNKNOWN_CONTRIBUTOR, assertDoesNotThrow( () -> identity.displayName( "jane@example.com" ) ),
                "an email-shaped value is masked even when the load fails" );
        assertEquals( Optional.empty(), assertDoesNotThrow( () -> identity.userIdForLogin( "jane" ) ) );
        assertEquals( Optional.empty(), assertDoesNotThrow( () -> identity.loginForDisplayName( "Jane Doe" ) ) );
        verify( db, times( 1 ) ).accountSummaries();   // the backoff spares the database (and the log)

        account( "jane", "Jane Doe", "u1" );   // database recovers
        assertEquals( PublicIdentity.UNKNOWN, identity.displayName( "jane" ), "still inside the 5 s backoff" );
        clock.advance( DefaultPublicIdentity.RETRY_AFTER_FAILURE );
        assertEquals( "Jane Doe", identity.displayName( "jane" ), "a failure is not cached beyond the backoff" );
    }

    @Test
    void aFailedReloadKeepsServingThePreviousSnapshot() throws Exception {
        account( "jane", "Jane Doe", "u1" );
        assertEquals( "Jane Doe", identity.displayName( "jane" ) );

        doThrow( new IllegalStateException( "pool exhausted" ) ).when( db ).accountSummaries();
        clock.advance( Duration.ofSeconds( 61 ) );

        assertEquals( "Jane Doe", assertDoesNotThrow( () -> identity.displayName( "jane" ) ), "stale beats unknown" );
        assertEquals( Optional.of( "u1" ), identity.userIdForLogin( "jane" ), "every lookup serves the previous snapshot" );
    }

    @Test
    void aFailedReloadAfterAnInvalidationStillServesThePreviousSnapshot() throws Exception {
        account( "jane", "Jane Doe", "u1" );
        assertEquals( "Jane Doe", identity.displayName( "jane" ) );

        doThrow( new IllegalStateException( "pool exhausted" ) ).when( db ).accountSummaries();
        identity.invalidate( "jane" );

        assertEquals( "Jane Doe", assertDoesNotThrow( () -> identity.displayName( "jane" ) ),
                "an invalidated snapshot is only stale: it still beats showing every author as unknown" );
    }

    @Test
    void withoutADatabaseNoAccountIsKnownButEmailsAreStillMasked() {
        final DefaultPublicIdentity bare = new DefaultPublicIdentity( null, clock );
        assertEquals( "Jake Fear", bare.displayName( "Jake Fear" ) );
        assertEquals( PublicIdentity.UNKNOWN_CONTRIBUTOR, bare.displayName( "x@y.example" ) );
        assertEquals( Optional.empty(), bare.userIdForLogin( "jane" ) );
        assertEquals( Optional.empty(), bare.loginForUserId( "u1" ) );
        assertEquals( Optional.empty(), bare.loginForDisplayName( "Jane Doe" ) );
        assertDoesNotThrow( () -> bare.invalidate( "jane" ) );
    }

    // ---- profile events ----

    private static UserProfile profile( final String login ) {
        final UserProfile p = Mockito.mock( UserProfile.class );
        Mockito.when( p.getLoginName() ).thenReturn( login );
        return p;
    }

    @Test
    void profileSaveAndNameChangeEventsInvalidateButOtherEventsDoNot() throws Exception {
        account( "jane", "Jane Doe", "u1" );
        identity.displayName( "jane" );

        identity.actionPerformed( new WikiSecurityEvent( this, WikiSecurityEvent.PROFILE_SAVE, profile( "jane" ) ) );
        identity.displayName( "jane" );
        verify( db, times( 2 ) ).accountSummaries();

        identity.actionPerformed( new WikiSecurityEvent( this, WikiSecurityEvent.PROFILE_NAME_CHANGED,
                new UserProfile[] { profile( "jane" ), profile( "jane" ) } ) );
        identity.displayName( "jane" );
        verify( db, times( 3 ) ).accountSummaries();

        identity.actionPerformed( new WikiSecurityEvent( this, WikiSecurityEvent.LOGIN_AUTHENTICATED, profile( "jane" ) ) );
        identity.displayName( "jane" );
        verify( db, times( 3 ) ).accountSummaries();   // an unrelated event changed nothing
    }
}
