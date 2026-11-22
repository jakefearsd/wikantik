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

import com.wikantik.jdbc.testing.PostgresTestDb;
import com.wikantik.jdbc.testing.RequiresPostgres;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.sql.SQLException;
import java.time.Duration;
import java.time.Instant;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Retention of notifications: old rows go (read or not), in bounded batches, and never the e-mail window. */
@RequiresPostgres
class WorkNotifyRetentionPostgresTest {

    private static final Instant NOW = Instant.parse( "2026-06-01T12:00:00Z" );

    private NotifyFixtures fx;
    private int next;

    @BeforeEach
    void setUp() throws SQLException {
        fx = new NotifyFixtures();
        PostgresTestDb.truncate( "work_notify_skipped" );
    }

    private WorkNotificationsService service( final int retentionDays ) throws SQLException {
        final WorkNotifyPorts ports = new WorkNotifyPorts( WorkMailer.NONE, WorkOutbound.NONE, new NotifySettings(
            Duration.ofSeconds( 60 ), false, EmailDetail.SUMMARY, Duration.ofMinutes( 5 ), 8, "", false,
            retentionDays ) );
        return new NotifyServiceFixture( ports ).notices;
    }

    /** One notification created {@code daysAgo} days before NOW; the fixture rebuilt by service() is truncated, so call after. */
    private void notice( final int daysAgo, final boolean read, final boolean emailed ) throws SQLException {
        final long event = fx.event( WorkEvent.EDITED, "bob", null );
        WorkAgentFixtures.exec( fx.ds, "INSERT INTO work_notification (recipient, event_id, project_id, item_id, reason, "
            + "created_at, read_at, emailed_at) VALUES ('otto" + ( next++ ) + "'," + event + "," + fx.project + ","
            + fx.item + ",'watching','" + NOW.minus( Duration.ofDays( daysAgo ) ) + "',"
            + ( read ? "'" + NOW + "'" : "NULL" ) + "," + ( emailed ? "'" + NOW + "'" : "NULL" ) + ")" );
    }

    private void skipped( final int daysAgo ) throws SQLException {
        WorkAgentFixtures.exec( fx.ds, "INSERT INTO work_notify_skipped (event_id, tx, error_class, skipped_at) VALUES (1, "
            + "pg_current_xact_id(), 'x.Y', '" + NOW.minus( Duration.ofDays( daysAgo ) ) + "')" );
    }

    private WorkNotificationsService seeded( final int retentionDays ) throws SQLException {
        final WorkNotificationsService svc = service( retentionDays );
        fx = new NotifyFixtures();   // the service fixture truncated everything; rebuild the rows on the fresh state
        PostgresTestDb.truncate( "work_notify_skipped" );
        return svc;
    }

    @Test
    void oldNoticesGoReadOrUnreadAndRecentOnesStay() throws SQLException {
        final WorkNotificationsService svc = seeded( 30 );
        notice( 40, true, true );
        notice( 40, false, false );
        notice( 31, false, false );
        notice( 29, false, false );
        notice( 1, true, false );
        skipped( 40 );
        skipped( 5 );
        assertEquals( 4, svc.prune( NOW ).deleted() );
        assertEquals( 2, fx.count( "work_notification" ) );
        assertEquals( 1, fx.count( "work_notify_skipped" ) );
        assertEquals( 0, svc.prune( NOW ).deleted(), "a second pass finds nothing" );
    }

    @Test
    void zeroDaysKeepsEverything() throws SQLException {
        final WorkNotificationsService svc = seeded( 0 );
        notice( 4000, false, false );
        skipped( 4000 );
        assertEquals( 0, svc.prune( NOW ).deleted() );
        assertEquals( 1, fx.count( "work_notification" ) );
        assertEquals( 1, fx.count( "work_notify_skipped" ) );
    }

    @Test
    void aShortRetentionNeverReachesTheEmailWindow() throws SQLException {
        final WorkNotificationsService svc = seeded( 1 );
        notice( 2, false, false );   // inside the 48 h e-mail window plus margin: kept
        notice( 4, false, false );
        assertEquals( 1, svc.prune( NOW ).deleted() );
        assertEquals( 1, fx.count( "work_notification" ) );
        assertEquals( 1, fx.count( "work_notification WHERE created_at > '" + NOW.minus( Duration.ofDays( 3 ) ) + "'" ) );
    }

    @Test
    void aPrunedRowIsNoLongerPendingEmail() throws SQLException {
        notice( 100, false, false );
        final WorkNotificationStore store = new JdbcWorkStore( fx.ds ).notifyStores().notifications();
        assertEquals( 1, store.prune( NOW.minus( Duration.ofDays( 50 ) ), 10, 10 ).deleted() );
        assertEquals( 0, store.pendingEmail( NOW.minus( Duration.ofDays( 200 ) ), 10, true, java.time.LocalDate.of( 2026, 6, 1 ) ).size() );
    }

    @Test
    void deletionIsInBoundedBatches() throws SQLException {
        for ( int i = 0; i < 5; i++ ) {
            notice( 100, false, false );
        }
        final WorkNotificationStore store = new JdbcWorkStore( fx.ds ).notifyStores().notifications();
        final Instant before = NOW.minus( Duration.ofDays( 50 ) );
        final WorkNotificationStore.Pruned first = store.prune( before, 2, 2 );
        assertEquals( 4, first.deleted(), "two batches of two" );
        assertTrue( first.more(), "the cap was reached with a full batch: more may remain" );
        assertEquals( 1, fx.count( "work_notification" ) );
        final WorkNotificationStore.Pruned last = store.prune( before, 2, 2 );
        assertEquals( 1, last.deleted() );
        assertFalse( last.more() );
        assertEquals( 0, fx.count( "work_notification" ) );
    }

    @Test
    void workRecordsAreNeverPruned() throws SQLException {
        final WorkNotificationsService svc = seeded( 1 );
        notice( 400, false, false );
        final long events = fx.count( "work_event" );
        svc.prune( NOW );
        assertEquals( events, fx.count( "work_event" ) );
        assertEquals( 1, fx.count( "work_item" ) );
    }

    @Test
    void oneSaturatedTableAloneSaysMoreRemainsAndAnEmptyOneDoesNot() throws SQLException {
        for ( int i = 0; i < 4; i++ ) {
            notice( 100, false, false );
        }
        final WorkNotificationStore store = new JdbcWorkStore( fx.ds ).notifyStores().notifications();
        final Instant before = NOW.minus( Duration.ofDays( 50 ) );
        final WorkNotificationStore.Pruned p = store.prune( before, 2, 2 );   // notifications saturated, skipped empty
        assertEquals( 4, p.deleted() );
        assertTrue( p.more(), "a saturated notification table must not be hidden by the empty skipped table" );
        notice( 100, false, false );
        final WorkNotificationStore.Pruned q = store.prune( before, 2, 2 );
        assertFalse( q.more(), "one row left over, under the cap: done" );
        assertEquals( 1, q.deleted() );
    }
}
