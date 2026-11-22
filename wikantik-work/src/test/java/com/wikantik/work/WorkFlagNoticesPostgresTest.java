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

import java.sql.SQLException;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

import static com.wikantik.work.CodeEvents.A;
import static com.wikantik.work.CodeEvents.ANA;
import static com.wikantik.work.CodeEvents.M;
import static com.wikantik.work.CodeEvents.MIA;
import static com.wikantik.work.CodeEvents.at;
import static com.wikantik.work.CodeEvents.commit;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * I-3: a {@code change_flagged} notice is live only while its change is still flagged, not superseded and not
 * acknowledged. The inbox, the unread count, mark-all-read and email decide that when they read, so the answer is
 * right whichever of the dispatch and the clearing delivery happened first. Rows are never deleted.
 */
@RequiresPostgres
class WorkFlagNoticesPostgresTest {

    private record Sent( String to, String subject, String text ) {
    }

    private final List< Sent > sent = new ArrayList<>();
    private CodeHostFixture f;
    private WorkNotificationsService notices;
    private final Actor otto = Actor.human( "otto", java.util.Set.of(), false );

    @BeforeEach
    void setUp() throws SQLException {
        sent.clear();
        final WorkMailer mailer = ( to, subject, text ) -> sent.add( new Sent( to, subject, text ) );
        f = new CodeHostFixture( new WorkNotifyPorts( mailer, WorkOutbound.NONE, new NotifySettings(
            Duration.ofSeconds( 60 ), true, EmailDetail.SUMMARY, Duration.ofMinutes( 5 ), 8, "https://wiki.example.com",
            false ) ) );
        f.svc.putMember( f.admin, "PLAT", "otto", "user", WorkRole.LEAD );
        f.addresses.put( "otto", "otto@example.com" );
        notices = f.svc.features().notifications();
        notices.dispatch( 10 );
    }

    private void dispatchAll() throws SQLException {
        DispatchAwait.until( () -> notices.dispatch( 100 ), () -> f.count( "SELECT COUNT(*) FROM work_event e, "
            + "work_notify_cursor c WHERE c.name = 'inbox' AND (e.tx, e.id) > (c.tx, c.event_id)" ) == 0 );
    }

    /** A pull request on the item opened at 11:00 and merged at 11:20 with no approval: flagged {@code unreviewed}. */
    private Change mergedWithoutApproval( final Item item ) throws SQLException {
        final String branch = branch( item );
        f.hooks.apply( f.delivery( "github" ), List.of( CodeEvents.opened( branch, "12", A, at( "11:00" ) ),
            CodeEvents.merged( branch, "12", A, M, at( "11:20" ), ANA ) ) );
        final Change c = f.only( item );
        assertEquals( "unreviewed", c.policyFlag() );
        return c;
    }

    /** Mia's approval of the merged head, stamped before the merge, arriving after it: clears the flag. */
    private void lateApproval( final Item item ) throws SQLException {
        f.hooks.apply( f.delivery( "github" ), List.of( CodeEvents.approved( branch( item ), "12", A, at( "11:10" ), MIA ) ) );
        assertNull( f.only( item ).policyFlag(), "the late approval cleared the flag" );
    }

    private long directChange( final Item item ) throws SQLException {
        return f.changes( item ).stream().filter( c -> c.mergeKind() == MergeKind.DIRECT ).findFirst().orElseThrow().id();
    }

    private static String branch( final Item item ) {
        return "work/" + item.key() + "-fix";
    }

    /** The change ids the reader's inbox holds a {@code change_flagged} notice for. */
    private List< String > flagNotices( final Actor who ) throws SQLException {
        return notices.inbox( who, null, 100 ).stream().filter( n -> WorkEvent.CHANGE_FLAGGED.equals( n.event().type() ) )
            .map( n -> n.event().fromValue() ).toList();
    }

    private long flagRows( final String recipient ) throws SQLException {
        return f.count( "SELECT COUNT(*) FROM work_notification n JOIN work_event e ON e.id = n.event_id "
            + "WHERE n.recipient = '" + recipient + "' AND e.type = 'change_flagged'" );
    }

    @Test
    void aStillFlaggedMergeStaysInEveryLeadsInbox() throws SQLException {
        final Change c = mergedWithoutApproval( f.task() );
        dispatchAll();
        assertEquals( List.of( "change:" + c.id() ), flagNotices( f.lena ) );
        assertEquals( List.of( "change:" + c.id() ), flagNotices( otto ) );
        assertEquals( 1, notices.unread( otto ) );
    }

    @Test
    void aLateApprovalAfterDispatchTakesTheNoticeAwayAndTheUnreadCountDrops() throws SQLException {
        final Item item = f.task();
        mergedWithoutApproval( item );
        dispatchAll();
        assertEquals( 1, notices.unread( otto ) );
        final int lenasBefore = notices.unread( f.lena );
        lateApproval( item );
        assertEquals( List.of(), flagNotices( otto ) );
        assertEquals( List.of(), flagNotices( f.lena ) );
        assertEquals( 0, notices.unread( otto ) );
        assertEquals( lenasBefore - 1, notices.unread( f.lena ) );
        assertEquals( 1, flagRows( "otto" ), "the row is kept; it is just no longer live" );
    }

    @Test
    void aLateApprovalBeforeDispatchLeavesNothingToRead() throws SQLException {
        final Item item = f.task();
        mergedWithoutApproval( item );
        lateApproval( item );
        dispatchAll();
        assertEquals( List.of(), flagNotices( otto ) );
        assertEquals( 0, notices.unread( otto ) );
        assertEquals( List.of(), flagNotices( f.lena ) );
    }

    @Test
    void aSupersededDirectChangesNoticeGoesAndTheMergesNoticeStays() throws SQLException {
        final Item item = f.task();
        final String branch = branch( item );
        f.hooks.apply( f.delivery( "github" ), List.of( CodeEvents.opened( branch, "13", A, at( "11:00" ) ),
            CodeEvents.push( "main", "dddddd4", at( "11:21" ), commit( "dddddd4", item.key() + " fix login",
                at( "11:20" ), true, ANA ) ) ) );
        dispatchAll();
        final long direct = directChange( item );
        assertEquals( List.of( "change:" + direct ), flagNotices( otto ), "the direct push is flagged" );
        f.hooks.apply( f.delivery( "github" ), List.of( CodeEvents.merged( branch, "13", A, "dddddd4", at( "11:25" ),
            ANA ) ) );
        dispatchAll();
        final Change merge = f.changes( item ).stream().filter( c -> c.id() != direct ).findFirst().orElseThrow();
        assertEquals( merge.id(), f.changes( item ).stream().filter( c -> c.id() == direct ).findFirst().orElseThrow()
            .supersededBy() );
        assertEquals( List.of( "change:" + merge.id() ), flagNotices( otto ),
            "the superseded direct change's notice is gone; the merge's own (no approval) is live" );
        assertEquals( 1, notices.unread( otto ) );
    }

    @Test
    void oneLeadsAcknowledgementTakesTheNoticeAwayForEveryLead() throws SQLException {
        final Change c = mergedWithoutApproval( f.task() );
        dispatchAll();
        f.svc.changes().acknowledgeFlag( f.lena, c.id() );
        assertEquals( List.of(), flagNotices( otto ) );
        assertEquals( 0, notices.unread( otto ) );
        assertEquals( List.of(), flagNotices( f.lena ) );
    }

    @Test
    void markAllReadLeavesANoticeThatIsNoLongerLiveAlone() throws SQLException {
        final Item item = f.task();
        mergedWithoutApproval( item );
        dispatchAll();
        lateApproval( item );
        notices.markAllRead( otto );
        assertEquals( 1, f.count( "SELECT COUNT(*) FROM work_notification WHERE recipient = 'otto' AND read_at IS NULL" ),
            "mark-all-read reads with the same rule as the list" );
    }

    @Test
    void anEmailSentAfterTheFlagClearedOmitsItAndKeepsTheLiveOne() throws SQLException {
        final Item cleared = f.task();
        mergedWithoutApproval( cleared );
        final Item kept = f.task();
        f.hooks.apply( f.delivery( "github" ), List.of( CodeEvents.opened( branch( kept ), "14", A, at( "11:00" ) ),
            CodeEvents.merged( branch( kept ), "14", A, "fffffff6", at( "11:20" ), ANA ) ) );
        dispatchAll();
        lateApproval( cleared );
        final Instant now = f.clock.instant();
        f.exec( "UPDATE work_notification SET created_at = '" + now.minus( Duration.ofMinutes( 1 ) ) + "'" );
        notices.sendEmail( now );
        final List< Sent > toOtto = sent.stream().filter( s -> s.to().equals( "otto@example.com" ) ).toList();
        assertEquals( 1, toOtto.size(), sent.toString() );
        assertTrue( toOtto.get( 0 ).text().contains( kept.key() ), toOtto.get( 0 ).text() );
        assertTrue( !toOtto.get( 0 ).text().contains( cleared.key() ) && !toOtto.get( 0 ).subject().contains( cleared.key() ),
            "nothing about the cleared flag: " + toOtto.get( 0 ) );
    }

    @Test
    void acknowledgingAClearedFlagSaysItWasAlreadyCleared() throws SQLException {
        final Item item = f.task();
        final Change c = mergedWithoutApproval( item );
        lateApproval( item );
        final WorkConflictException e = assertThrows( WorkConflictException.class,
            () -> f.svc.changes().acknowledgeFlag( f.lena, c.id() ) );
        assertEquals( WorkConflictException.NOT_FLAGGED, e.reason() );
        assertTrue( e.getMessage().contains( "already cleared" ), e.getMessage() );
    }

    @Test
    void acknowledgingASupersededFlagSaysItWasAlreadyCleared() throws SQLException {
        final Item item = f.task();
        final String branch = branch( item );
        f.hooks.apply( f.delivery( "github" ), List.of( CodeEvents.opened( branch, "13", A, at( "11:00" ) ),
            CodeEvents.push( "main", "dddddd4", at( "11:21" ), commit( "dddddd4", item.key() + " fix login",
                at( "11:20" ), true, ANA ) ) ) );
        final long direct = directChange( item );
        f.hooks.apply( f.delivery( "github" ), List.of( CodeEvents.merged( branch, "13", A, "dddddd4", at( "11:25" ),
            ANA ) ) );
        final WorkConflictException e = assertThrows( WorkConflictException.class,
            () -> f.svc.changes().acknowledgeFlag( f.lena, direct ) );
        assertEquals( WorkConflictException.NOT_FLAGGED, e.reason() );
        assertTrue( e.getMessage().contains( "already cleared" ), e.getMessage() );
    }
}
