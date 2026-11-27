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
package com.wikantik.work;

import com.wikantik.jdbc.testing.RequiresPostgres;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.sql.SQLException;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Email through the real service and store with a recording mailer (never a real transport) and the fixture's fixed
 * clock. Every {@code created_at} is set explicitly relative to that clock: nothing here reads the wall clock.
 */
@RequiresPostgres
class WorkEmailPostgresTest {

    private static final Instant NOW = NotifyServiceFixture.NOW;
    private static final Actor LENA = NotifyServiceFixture.LENA;
    private static final Actor OTTO = NotifyServiceFixture.OTTO;

    private record Sent( String to, String subject, String text ) {
    }

    private final List< Sent > sent = new ArrayList<>();
    private boolean failing;
    private final java.util.Set< String > failFor = new java.util.HashSet<>();
    private NotifyServiceFixture fx;
    private Item item;

    private static NotifySettings settings( final boolean enabled ) {
        return new NotifySettings( Duration.ofSeconds( 60 ), enabled, EmailDetail.SUMMARY, Duration.ofMinutes( 5 ), 8,
            "https://wiki.example.com", false );
    }

    private void start( final boolean enabled ) throws SQLException {
        final WorkMailer mailer = ( to, subject, text ) -> {
            if ( failing || failFor.contains( to ) ) {
                throw new IOException( "relay down" );
            }
            sent.add( new Sent( to, subject, text ) );
        };
        fx = new NotifyServiceFixture( new WorkNotifyPorts( mailer, WorkOutbound.NONE, settings( enabled ) ) );
        fx.emails.put( "otto", "otto@example.com" );
        fx.plant();
        item = fx.newItem( LENA );
    }

    /** Otto watches the item; Lena changes it twice; the two notices are dispatched and aged one minute. */
    private void twoNoticesForOtto() throws SQLException {
        fx.notices.setWatch( OTTO, item.key(), WatchState.WATCHING );
        fx.patch( LENA, item, "assignee", "otto" );
        fx.patch( LENA, item, "status", "in_progress" );
        fx.dispatchAll();
        assertEquals( 2, fx.rows( "recipient = 'otto'" ) );
        age( Duration.ofMinutes( 1 ) );
    }

    private void age( final Duration by ) throws SQLException {
        WorkAgentFixtures.exec( fx.ds, "UPDATE work_notification SET created_at = '" + NOW.minus( by ) + "'" );
    }

    @BeforeEach
    void setUp() {
        sent.clear();
        failing = false;
        failFor.clear();
    }

    @Test
    void twoEventsOnOneItemInACycleAreOneMessage() throws SQLException {
        start( true );
        twoNoticesForOtto();
        assertEquals( 1, fx.notices.sendEmail( NOW ) );
        assertEquals( 1, sent.size() );
        assertEquals( "otto@example.com", sent.get( 0 ).to() );
        assertEquals( "Work: 2 updates on WIKI-1", sent.get( 0 ).subject() );
        assertTrue( sent.get( 0 ).text().contains( "https://wiki.example.com/work/item/WIKI-1" ) );
        assertEquals( 2, fx.rows( "recipient = 'otto' AND emailed_at IS NOT NULL" ) );
        assertEquals( 0, fx.notices.sendEmail( NOW ), "nothing is sent twice" );
        assertTrue( fx.notified.contains( "email/delivered" ) );
    }

    @Test
    void offSendsNothing() throws SQLException {
        start( true );
        twoNoticesForOtto();
        fx.notices.setPreference( OTTO, EmailMode.OFF );
        assertEquals( 0, fx.notices.sendEmail( NOW ) );
        assertTrue( sent.isEmpty() );
        assertEquals( 0, fx.rows( "emailed_at IS NOT NULL" ) );
    }

    @Test
    void dailySendsOnceADayAfterTheHour() throws SQLException {
        start( true );
        twoNoticesForOtto();
        fx.notices.setPreference( OTTO, EmailMode.DAILY );
        assertEquals( 0, fx.notices.sendEmail( Instant.parse( "2026-03-02T07:59:00Z" ) ), "before the hour" );
        assertEquals( 1, fx.notices.sendEmail( NOW ) );
        assertEquals( 1, WorkAgentFixtures.scalar( fx.ds, "SELECT COUNT(*) FROM work_notify_pref "
            + "WHERE login = 'otto' AND last_digest_on = DATE '2026-03-02'" ) );
        assertEquals( EmailMode.DAILY, fx.notices.preference( OTTO ).email() );
        fx.patch( LENA, item, "status", "review" );
        fx.dispatchAll();
        WorkAgentFixtures.exec( fx.ds, "UPDATE work_notification SET created_at = '" + NOW.minus( Duration.ofMinutes( 1 ) )
            + "' WHERE emailed_at IS NULL" );
        assertEquals( 0, fx.notices.sendEmail( NOW.plus( Duration.ofHours( 1 ) ) ), "already sent today" );
        assertEquals( 1, fx.notices.sendEmail( NOW.plus( Duration.ofDays( 1 ) ) ), "the next day" );
        assertEquals( 2, sent.size() );
    }

    @Test
    void aFailingMailerLeavesRowsForTheNextCycle() throws SQLException {
        start( true );
        twoNoticesForOtto();
        failing = true;
        assertEquals( 0, fx.notices.sendEmail( NOW ) );
        assertEquals( 0, fx.rows( "emailed_at IS NOT NULL" ) );
        assertTrue( fx.notified.contains( "email/failed" ) );
        failing = false;
        assertEquals( 1, fx.notices.sendEmail( NOW ) );
        assertEquals( 2, fx.rows( "emailed_at IS NOT NULL" ) );
    }

    @Test
    void emailDisabledNeverCallsTheMailer() throws SQLException {
        start( false );
        twoNoticesForOtto();
        assertEquals( 0, fx.notices.sendEmail( NOW ) );
        assertTrue( sent.isEmpty() );
        assertEquals( 0, fx.rows( "emailed_at IS NOT NULL" ) );
    }

    @Test
    void aPersonWithNoAddressHasRowsStamped() throws SQLException {
        start( true );
        fx.emails.clear();
        twoNoticesForOtto();
        assertEquals( 0, fx.notices.sendEmail( NOW ) );
        assertTrue( sent.isEmpty() );
        assertEquals( 2, fx.rows( "recipient = 'otto' AND emailed_at IS NOT NULL" ) );
    }

    @Test
    void aMemberRemovedBeforeTheEmailIsNotSentIt() throws SQLException {
        start( true );
        twoNoticesForOtto();
        fx.store.removeMember( fx.project.id(), "otto", "user", NotifyServiceFixture.ROOT );
        assertEquals( 0, fx.notices.sendEmail( NOW ) );
        assertTrue( sent.isEmpty() );
        assertEquals( 2, fx.rows( "recipient = 'otto' AND emailed_at IS NOT NULL" ), "stamped, never sent" );
    }

    @Test
    void anAgentIsNeverMailed() throws SQLException {
        start( true );
        final long agent = WorkAgentFixtures.agent( fx.ds, "scribe-bot" );
        fx.emails.put( "scribe-bot", "bot@example.com" );
        twoNoticesForOtto();
        WorkAgentFixtures.exec( fx.ds, "INSERT INTO work_notification (recipient, event_id, project_id, item_id, reason, "
            + "created_at) SELECT 'scribe-bot', id, project_id, item_id, 'watching', '" + NOW.minus( Duration.ofMinutes( 1 ) )
            + "' FROM work_event WHERE type = 'status' LIMIT 1" );
        assertTrue( agent > 0 );
        assertEquals( 1, fx.notices.sendEmail( NOW ) );
        assertEquals( List.of( "otto@example.com" ), sent.stream().map( Sent::to ).toList() );
        assertEquals( 1, fx.rows( "recipient = 'scribe-bot' AND emailed_at IS NOT NULL" ), "stamped, never sent" );
    }

    @Test
    void aPersonWhoSeesTheProjectOnlyThroughAGroupIsMailed() throws SQLException {
        start( true );
        fx.store.putMember( fx.project.id(), "Crew", "group", WorkRole.VIEWER, NotifyServiceFixture.ROOT );
        fx.accounts.add( "gwen" );
        fx.accountGroups.put( "gwen", java.util.Set.of( "Crew" ) );
        fx.emails.put( "gwen", "gwen@example.com" );
        fx.service.addComment( LENA, item.key(), "ping @gwen" );
        fx.dispatchAll();
        age( Duration.ofMinutes( 1 ) );
        assertEquals( 1, fx.notices.sendEmail( NOW ) );
        assertEquals( "gwen@example.com", sent.get( 0 ).to() );
        fx.accountGroups.put( "gwen", java.util.Set.of() );   // the group membership ends
        fx.service.addComment( LENA, item.key(), "again @gwen" );
        fx.dispatchAll();
        assertEquals( 1, sent.size(), "no standing, no new row" );
    }

    @Test
    void rowsOlderThan48HoursAreNeverMailed() throws SQLException {
        start( true );
        twoNoticesForOtto();
        age( Duration.ofHours( 49 ) );
        assertEquals( 0, fx.notices.sendEmail( NOW ) );
        assertTrue( sent.isEmpty() );
        assertEquals( 0, fx.rows( "emailed_at IS NOT NULL" ) );
    }

    @Test
    void anAccountWinsOverAnAgentOfTheSameName() throws SQLException {
        start( true );
        WorkAgentFixtures.agent( fx.ds, "alice" );   // an agent of that name existed first
        fx.accounts.add( "alice" );
        fx.emails.put( "alice", "alice@example.com" );
        fx.store.putMember( fx.project.id(), "alice", "user", WorkRole.VIEWER, NotifyServiceFixture.ROOT );
        final Actor alice = Actor.human( "alice", java.util.Set.of(), false );
        fx.notices.setWatch( alice, item.key(), WatchState.WATCHING );
        fx.patch( LENA, item, "status", "in_progress" );
        fx.dispatchAll();
        assertEquals( 1, fx.rows( "recipient = 'alice'" ), "Alice still gets her notice" );
        age( Duration.ofMinutes( 1 ) );
        assertEquals( 1, fx.notices.sendEmail( NOW ) );
        assertEquals( "alice@example.com", sent.get( 0 ).to(), "and her mail" );
    }

    @Test
    void oneRecipientsFailureNeitherBlocksNorStampsAnother() throws SQLException {
        start( true );
        fx.emails.put( "vera", "vera@example.com" );
        fx.notices.setWatch( OTTO, item.key(), WatchState.WATCHING );
        fx.notices.setWatch( NotifyServiceFixture.VERA, item.key(), WatchState.WATCHING );
        fx.patch( LENA, item, "status", "in_progress" );
        fx.dispatchAll();
        age( Duration.ofMinutes( 1 ) );
        failFor.add( "otto@example.com" );   // otto sorts before vera
        assertEquals( 1, fx.notices.sendEmail( NOW ) );
        assertEquals( List.of( "vera@example.com" ), sent.stream().map( Sent::to ).toList() );
        assertEquals( 0, fx.rows( "recipient = 'otto' AND emailed_at IS NOT NULL" ), "the failed one is left to retry" );
        assertEquals( 1, fx.rows( "recipient = 'vera' AND emailed_at IS NOT NULL" ) );
        failFor.clear();
        assertEquals( 1, fx.notices.sendEmail( NOW ) );
        assertEquals( "otto@example.com", sent.get( 1 ).to() );
    }

    @Test
    void theFortyEightHourBoundary() throws SQLException {
        start( true );
        twoNoticesForOtto();
        age( Duration.ofHours( 48 ).plusMinutes( 1 ) );
        assertEquals( 0, fx.notices.sendEmail( NOW ), "just outside" );
        age( Duration.ofHours( 47 ).plusMinutes( 59 ) );
        assertEquals( 1, fx.notices.sendEmail( NOW ), "just inside" );
    }

    @Test
    void aListOfAddressesIsNeverMailed() throws SQLException {
        start( true );
        for ( final String bad : new String[] { "a@example.com, b@example.com", "a@example.com;b@example.com",
            "a@example.com\r\nBcc: x@example.com", "Otto <a@example.com>", "not-an-address" } ) {
            fx.emails.put( "otto", bad );
            twoNoticesForOtto();
            assertEquals( 0, fx.notices.sendEmail( NOW ), bad );
            assertTrue( sent.isEmpty(), bad );
            WorkAgentFixtures.exec( fx.ds, "UPDATE work_notification SET emailed_at = NULL" );
        }
        fx.emails.put( "otto", " otto@example.com " );
        assertEquals( 1, fx.notices.sendEmail( NOW ) );
        assertEquals( "otto@example.com", sent.get( 0 ).to() );
    }

    @Test
    void offAndNotYetDueRowsNeverCrowdOutADueRecipient() throws SQLException {
        start( true );
        final WorkNotificationStore store = fx.store.notifyStores().notifications();
        final Instant since = NOW.minus( Duration.ofHours( 48 ) );
        fx.notices.setPreference( OTTO, EmailMode.OFF );
        fx.notices.setWatch( OTTO, item.key(), WatchState.WATCHING );
        fx.notices.setWatch( NotifyServiceFixture.VERA, item.key(), WatchState.WATCHING );
        for ( int i = 0; i < 6; i++ ) {
            fx.patch( LENA, item, "status", i % 2 == 0 ? "in_progress" : "ready" );
        }
        fx.dispatchAll();
        age( Duration.ofMinutes( 1 ) );
        assertEquals( 12, fx.rows( "TRUE" ) );
        final List< PendingEmail > page = store.pendingEmail( since, 5, true, java.time.LocalDate.of( 2026, 3, 2 ) );
        assertEquals( 5, page.size(), "a page of rows that can be mailed" );
        assertTrue( page.stream().allMatch( r -> r.recipient().equals( "vera" ) ), "the OFF person's rows are not in it" );
        fx.notices.setPreference( NotifyServiceFixture.VERA, EmailMode.DAILY );
        assertEquals( 0, store.pendingEmail( since, 5, false, java.time.LocalDate.of( 2026, 3, 2 ) ).size(), "before the hour" );
        assertEquals( 5, store.pendingEmail( since, 5, true, java.time.LocalDate.of( 2026, 3, 2 ) ).size() );
        WorkAgentFixtures.exec( fx.ds, "UPDATE work_notify_pref SET last_digest_on = DATE '2026-03-02' WHERE login = 'vera'" );
        assertEquals( 0, store.pendingEmail( since, 5, true, java.time.LocalDate.of( 2026, 3, 2 ) ).size(), "already sent today" );
        assertEquals( 5, store.pendingEmail( since, 5, true, java.time.LocalDate.of( 2026, 3, 3 ) ).size(), "due tomorrow" );
    }

    @Test
    void aDailyDigestGoesOutAtTheConfiguredHourExactlyNotASecondBefore() throws SQLException {
        start( true );
        twoNoticesForOtto();
        fx.notices.setPreference( OTTO, EmailMode.DAILY );
        assertEquals( 0, fx.notices.sendEmail( Instant.parse( "2026-03-02T07:59:59Z" ) ) );
        assertEquals( 0, fx.rows( "emailed_at IS NOT NULL" ) );
        assertEquals( 1, fx.notices.sendEmail( Instant.parse( "2026-03-02T08:00:00Z" ) ), "the hour itself is open" );
        assertEquals( 2, fx.rows( "emailed_at IS NOT NULL" ) );
    }
}
