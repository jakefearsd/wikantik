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
import org.junit.jupiter.api.function.Executable;

import java.sql.SQLException;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The Part 8 second fix round: a removed comment's files are unreachable for everyone (I1), a lapsed claim has no actor
 * for a reporter (M2), no email for a reporter of an archived project (M3), and the per-project standing of one person
 * who is a member in one project and a reporter in another (M5). Project WIKI: lena leads, otto is a member, rita
 * reports; WIKI-1 is rita's.
 */
@RequiresPostgres
class WorkIntakeFixTwoPostgresTest {

    private static final Actor ROOT = NotifyServiceFixture.ROOT;
    private static final Actor LENA = NotifyServiceFixture.LENA;
    private static final Actor OTTO = NotifyServiceFixture.OTTO;
    private static final Actor RITA = Actor.human( "rita", Set.of(), false );
    private static final Actor MIA = Actor.human( "mia", Set.of(), false );
    private static final byte[] PNG = { (byte) 0x89, 'P', 'N', 'G', 0x0D, 0x0A, 0x1A, 0x0A, 1, 2, 3 };

    private record Sent( String to, String subject, String text ) {
    }

    private final List< Sent > sent = new ArrayList<>();
    private NotifyServiceFixture fx;
    private WorkIntakeService intake;
    private WorkAttachmentsService files;
    private String ritas;

    @BeforeEach
    void setUp() throws SQLException {
        sent.clear();
        final WorkMailer mailer = ( to, subject, text ) -> sent.add( new Sent( to, subject, text ) );
        fx = new NotifyServiceFixture( new WorkNotifyPorts( mailer, WorkOutbound.NONE, new NotifySettings(
            Duration.ofSeconds( 60 ), true, EmailDetail.FULL, Duration.ofMinutes( 5 ), 8, "https://wiki.example.com", false ) ) );
        for ( final String who : List.of( "rita", "mia" ) ) {
            fx.accounts.add( who );
            fx.emails.put( who, who + "@example.com" );
        }
        fx.emails.put( "otto", "otto@example.com" );
        fx.store.putMember( fx.project.id(), "rita", "user", WorkRole.REPORTER, ROOT );
        intake = fx.service.features().intake();
        files = fx.service.features().attachments();
        fx.plant();
        ritas = intake.submit( RITA, new IntakeDraft( "WIKI", null, null, "rita's report", "it breaks" ) ).key();
        fx.dispatchAll();
    }

    private void ageNotices() throws SQLException {
        WorkAgentFixtures.exec( fx.ds, "UPDATE work_notification SET created_at = '"
            + NotifyServiceFixture.NOW.minus( Duration.ofMinutes( 1 ) ) + "'" );
    }

    private Item item( final String key ) throws SQLException {
        return fx.store.item( key ).orElseThrow();
    }

    private static String q( final String s ) {
        return s == null ? "NULL" : "'" + s.replace( "'", "''" ) + "'";
    }

    private long event( final long project, final Item on, final String type, final String from, final String to,
                        final String note, final Actor by ) throws SQLException {
        return WorkAgentFixtures.scalar( fx.ds, "INSERT INTO work_event (project_id, item_id, actor, actor_kind, agent_id, "
            + "type, from_value, to_value, note) VALUES (" + project + ", " + on.id() + ", " + q( by.login() ) + ", "
            + q( by.kind() == Actor.Kind.AGENT ? "agent" : "human" ) + ", "
            + ( by.agentId() == null ? "NULL" : by.agentId() ) + ", " + q( type ) + ", " + q( from ) + ", " + q( to ) + ", "
            + q( note ) + ") RETURNING id" );
    }

    private void notice( final String recipient, final long eventId, final long project, final Item on,
                         final NoticeReason reason ) throws SQLException {
        WorkAgentFixtures.exec( fx.ds, "INSERT INTO work_notification (recipient, event_id, project_id, item_id, reason) "
            + "VALUES (" + q( recipient ) + ", " + eventId + ", " + project + ", " + on.id() + ", " + q( reason.wire() ) + ")" );
    }

    // ---- I1: a removed comment's files

    /** rita's item holds one plain file and one file on otto's comment, which lena then removes. */
    private long[] plainAndRemovedCommentFile() throws SQLException {
        final long plain = files.upload( OTTO, ritas, null, "plain.png", PNG ).id();
        final CommentView c = fx.service.addComment( OTTO, ritas, "see the screenshot" );
        final long gone = files.upload( OTTO, ritas, c.id(), "salary.png", PNG ).id();
        fx.service.removeComment( LENA, c.id() );
        return new long[] { plain, gone };
    }

    @Test
    void aRemovedCommentsFileIsUnreachableForMembersAndReportersAlike() throws SQLException {
        final long[] ids = plainAndRemovedCommentFile();
        final String unknown = assertThrows( WorkNotFoundException.class, () -> files.content( OTTO, ritas, 9_999_999L ) )
            .getMessage();
        for ( final Actor who : List.of( OTTO, RITA, LENA ) ) {
            assertEquals( List.of( ids[ 0 ] ), files.list( who, ritas ).stream().map( Attachment::id ).toList(), who.login() );
            final AttachmentListing l = files.listing( who, ritas );
            assertEquals( 1, l.attachments().size(), who.login() );
            assertEquals( PNG.length, l.usedBytes(), "only the reachable file is counted: " + who.login() );
            final List< Executable > calls = List.of( () -> files.content( who, ritas, ids[ 1 ] ),
                () -> files.remove( who, ritas, ids[ 1 ] ) );
            for ( final Executable call : calls ) {
                assertEquals( unknown, assertThrows( WorkNotFoundException.class, call ).getMessage(), who.login() );
            }
            assertEquals( ids[ 0 ], files.content( who, ritas, ids[ 0 ] ).attachment().id() );
        }
        assertEquals( 1, WorkAgentFixtures.scalar( fx.ds, "SELECT COUNT(*) FROM work_attachment_blob WHERE attachment_id = "
            + ids[ 1 ] ), "nothing is hard-deleted: the bytes stay" );
        assertEquals( 0, WorkAgentFixtures.scalar( fx.ds, "SELECT COUNT(*) FROM work_attachment WHERE id = " + ids[ 1 ]
            + " AND removed_at IS NOT NULL" ), "the file itself is not stamped removed" );
    }

    @Test
    void aRemovedCommentsFileDoesNotCountTowardTheItemLimit() throws SQLException {
        final CommentView c = fx.service.addComment( OTTO, ritas, "see the screenshot" );
        final Item mine = item( ritas );
        final WorkAttachmentStore store = fx.store.notifyStores().attachments();
        final long limit = 2L * PNG.length - 1;
        store.add( mine, c.id(), new WorkAttachmentStore.NewAttachment( "a.png", "image/png", PNG, "a".repeat( 64 ) ), limit, OTTO );
        assertThrows( WorkConflictException.class, () -> store.add( mine, null,
            new WorkAttachmentStore.NewAttachment( "b.png", "image/png", PNG, "b".repeat( 64 ) ), limit, OTTO ), "two would pass the limit" );
        fx.service.removeComment( LENA, c.id() );
        assertEquals( "b.png", store.add( mine, null,
            new WorkAttachmentStore.NewAttachment( "b.png", "image/png", PNG, "b".repeat( 64 ) ), limit, OTTO ).filename() );
    }

    @Test
    void theReportersTimelineDropsTheRemovedCommentsFileButMembersKeepTheEventLog() throws SQLException {
        plainAndRemovedCommentFile();
        final List< String > shown = intake.item( RITA, ritas ).events().stream()
            .filter( e -> WorkEvent.ATTACHED.equals( e.type() ) ).map( ReportedEvent::toValue ).toList();
        assertEquals( List.of( "plain.png" ), shown );
        final List< String > log = fx.store.itemEvents( item( ritas ).id() ).stream()
            .filter( e -> WorkEvent.ATTACHED.equals( e.type() ) ).map( WorkEvent::toValue ).toList();
        assertEquals( List.of( "plain.png", "salary.png" ), log, "the append-only log is not rewritten" );
    }

    @Test
    void theImageTokenOfARemovedCommentsFileNoLongerResolves() throws SQLException {
        final long[] ids = plainAndRemovedCommentFile();
        assertEquals( 1, files.list( OTTO, ritas ).size() );
        assertTrue( files.list( OTTO, ritas ).stream().noneMatch( a -> a.id() == ids[ 1 ] ),
            "the token's target id is not in the item's reachable files, so its content route answers not found" );
    }

    @Test
    void removingACommentWithoutFilesChangesNothingElse() throws SQLException {
        final long plain = files.upload( OTTO, ritas, null, "plain.png", PNG ).id();
        final CommentView c = fx.service.addComment( OTTO, ritas, "no file here" );
        fx.service.removeComment( LENA, c.id() );
        assertEquals( List.of( plain ), files.list( RITA, ritas ).stream().map( Attachment::id ).toList() );
        assertEquals( plain, files.content( RITA, ritas, plain ).attachment().id() );
        assertEquals( plain, files.listing( OTTO, ritas ).attachments().get( 0 ).id() );
        assertEquals( List.of( "plain.png" ), intake.item( RITA, ritas ).events().stream()
            .filter( e -> WorkEvent.ATTACHED.equals( e.type() ) ).map( ReportedEvent::toValue ).toList() );
    }

    // ---- M2: a lapsed claim

    @Test
    void aLapsedClaimReadsWithoutAnActorForAReporterAndStaysWhoseForAMember() throws SQLException {
        final Actor codex = Actor.agent( WorkAgentFixtures.agent( fx.ds, "codex@docker2" ), "codex@docker2", LENA );
        final Item mine = item( ritas );
        event( fx.project.id(), mine, WorkEvent.CLAIM_EXPIRED, "in_progress", "ready", null, codex );
        fx.dispatchAll();
        ageNotices();
        fx.notices.sendEmail( NotifyServiceFixture.NOW );
        final Sent mail = sent.stream().filter( s -> s.to().equals( "rita@example.com" ) ).findFirst().orElseThrow();
        assertTrue( mail.text().contains( "This moved to ready" ), mail.text() );
        assertFalse( mail.text().contains( "codex" ), mail.text() );
        final ReportedEvent last = intake.item( RITA, ritas ).events().stream()
            .filter( e -> "ready".equals( e.toValue() ) ).reduce( ( a, b ) -> b ).orElseThrow();
        assertNull( last.actor(), "the timeline names nobody for a lapse" );
        final NoticeView seen = fx.notices.inbox( RITA, null, 10 ).stream()
            .filter( n -> WorkEvent.STATUS.equals( n.event().type() ) ).findFirst().orElseThrow();
        assertNull( seen.event().actor() );
    }

    // ---- M3: an archived project

    @Test
    void noEmailGoesToAReporterOfAnArchivedProjectButAMemberStillGetsHisBecauseHisInboxKeepsIt() throws SQLException {
        fx.patch( OTTO, item( ritas ), "status", "ready" );
        fx.patch( LENA, item( ritas ), "assignee", "otto" );
        fx.dispatchAll();
        fx.service.archiveProject( ROOT, "WIKI" );
        ageNotices();
        fx.notices.sendEmail( NotifyServiceFixture.NOW );
        assertTrue( sent.stream().noneMatch( s -> s.to().equals( "rita@example.com" ) ), "the reporter's inbox hides it" );
        assertEquals( 0, fx.rows( "recipient = 'rita' AND emailed_at IS NULL" ), "her rows are stamped, never sent" );
        assertTrue( fx.notices.inbox( RITA, null, 10 ).isEmpty() );
        assertEquals( 1, sent.stream().filter( s -> s.to().equals( "otto@example.com" ) ).count(),
            "a member's inbox lists an archived project, so his email agrees" );
        assertFalse( fx.notices.inbox( OTTO, null, 10 ).isEmpty() );
    }

    // ---- M5: one person, two projects; a member who files

    @Test
    void aMemberHereAndAReporterThereGetsMemberNoticesHereAndOnlyOwnItemSafeOnesThere() throws SQLException {
        final Project bee = fx.store.createProject( "BEE", "Bee", "" );
        fx.store.putMember( bee.id(), "lena", "user", WorkRole.LEAD, ROOT );
        fx.store.putMember( bee.id(), "mia", "user", WorkRole.REPORTER, ROOT );
        fx.store.putMember( fx.project.id(), "mia", "user", WorkRole.MEMBER, ROOT );
        final Item wikiOther = item( ritas );
        final Item beeMine = fx.service.createItem( LENA, new ItemDraft( "BEE", WorkLevel.TASK, null, null, WorkKind.SOFTWARE,
            "mine", "", null, null, null, null, null, null ) ).item();
        WorkAgentFixtures.exec( fx.ds, "UPDATE work_item SET reporter = 'mia', reporter_agent_id = NULL WHERE id = "
            + beeMine.id() );
        final Item beeTheirs = fx.service.createItem( LENA, new ItemDraft( "BEE", WorkLevel.TASK, null, null, WorkKind.SOFTWARE,
            "theirs", "", null, null, null, null, null, null ) ).item();
        final long wikiAssigned = event( fx.project.id(), wikiOther, WorkEvent.ASSIGNED, null, "otto", "a note", LENA );
        final long beeAssignedMine = event( bee.id(), beeMine, WorkEvent.ASSIGNED, null, "otto", "a note", LENA );
        final long beeStatusMine = event( bee.id(), beeMine, WorkEvent.STATUS, "ready", "done", "a note", LENA );
        final long beeStatusTheirs = event( bee.id(), beeTheirs, WorkEvent.STATUS, "ready", "done", null, LENA );
        notice( "mia", wikiAssigned, fx.project.id(), wikiOther, NoticeReason.ASSIGNED );
        notice( "mia", beeAssignedMine, bee.id(), beeMine, NoticeReason.ASSIGNED );
        notice( "mia", beeStatusMine, bee.id(), beeMine, NoticeReason.WATCHING );
        notice( "mia", beeStatusTheirs, bee.id(), beeTheirs, NoticeReason.WATCHING );
        final List< NoticeView > inbox = fx.notices.inbox( MIA, null, 50 );
        assertEquals( Set.of( wikiAssigned, beeStatusMine ), inbox.stream().map( n -> n.event().id() )
            .collect( java.util.stream.Collectors.toSet() ) );
        final NoticeView here = inbox.stream().filter( n -> n.event().id() == wikiAssigned ).findFirst().orElseThrow();
        assertEquals( NoticeReason.ASSIGNED, here.reason() );
        assertEquals( "a note", here.event().note(), "a member's notice is whole" );
        final NoticeView there = inbox.stream().filter( n -> n.event().id() == beeStatusMine ).findFirst().orElseThrow();
        assertNull( there.event().note(), "a reporter's notice carries no note" );
        assertEquals( NoticeReason.WATCHING, there.reason() );
        assertEquals( 2, fx.notices.unread( MIA ) );
    }

    @Test
    void aMemberWhoFilesThroughSubmitIsStillAMemberForNotices() throws SQLException {
        final String ottos = intake.submit( OTTO, new IntakeDraft( "WIKI", null, null, "otto's report", "x" ) ).key();
        fx.dispatchAll();
        final Item mineToo = item( ritas );
        event( fx.project.id(), mineToo, WorkEvent.REVIEW_REQUESTED, null, null, null, LENA );
        event( fx.project.id(), item( ottos ), WorkEvent.REVIEW_REQUESTED, null, null, null, LENA );
        fx.dispatchAll();
        assertEquals( 1, fx.rows( "recipient = 'otto' AND event_id IN (SELECT id FROM work_event WHERE type = 'review_requested')" ),
            "a member who filed hears everything members hear" );
        assertTrue( fx.notices.inbox( OTTO, null, 20 ).stream().anyMatch( n -> WorkEvent.REVIEW_REQUESTED.equals( n.event().type() ) ) );
        assertEquals( 0, fx.rows( "recipient = 'rita' AND event_id IN (SELECT id FROM work_event WHERE type = 'review_requested')" ),
            "a person who only reports never hears of it" );
    }
}
