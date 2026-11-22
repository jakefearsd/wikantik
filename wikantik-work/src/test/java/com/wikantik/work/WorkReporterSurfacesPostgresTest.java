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

import java.io.IOException;
import java.lang.reflect.Field;
import java.lang.reflect.Modifier;
import java.math.BigDecimal;
import java.sql.SQLException;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.TreeSet;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The surfaces Parts 1-7 added, met by a person who only reports (Part 8, Task 8.2c), over real PostgreSQL with the
 * notify fixture's fixed clock and a recording mailer. Project WIKI: lena leads, otto is a member, vera a viewer; rita
 * holds only the reporter row and files WIKI-1 through intake; lena's own item is WIKI-2. Every leak test builds the
 * forbidden data first (a note, an assignee, a queue, another item) so that the assertion can fail.
 */
@RequiresPostgres
class WorkReporterSurfacesPostgresTest {

    private static final Actor ROOT = NotifyServiceFixture.ROOT;
    private static final Actor LENA = NotifyServiceFixture.LENA;
    private static final Actor OTTO = NotifyServiceFixture.OTTO;
    private static final Actor RITA = Actor.human( "rita", Set.of(), false );
    private static final Instant NOW = NotifyServiceFixture.NOW;
    private static final String SECRET_NOTE = "internal reason";
    private static final byte[] PNG = { (byte) 0x89, 'P', 'N', 'G', 0x0D, 0x0A, 0x1A, 0x0A, 1, 2, 3 };

    private record Sent( String to, String subject, String text ) {
    }

    private final List< Sent > sent = new ArrayList<>();
    private NotifyServiceFixture fx;
    private WorkIntakeService intake;
    private WorkNotificationsService notices;
    private WorkAttachmentsService files;
    private String ritas;
    private Item lenas;

    @BeforeEach
    void setUp() throws SQLException {
        sent.clear();
        final WorkMailer mailer = ( to, subject, text ) -> sent.add( new Sent( to, subject, text ) );
        fx = new NotifyServiceFixture( new WorkNotifyPorts( mailer, WorkOutbound.NONE, new NotifySettings(
            Duration.ofSeconds( 60 ), true, EmailDetail.FULL, Duration.ofMinutes( 5 ), 8, "https://wiki.example.com", false ) ) );
        fx.accounts.add( "rita" );
        fx.emails.put( "rita", "rita@example.com" );
        fx.store.putMember( fx.project.id(), "rita", "user", WorkRole.REPORTER, ROOT );
        intake = fx.service.features().intake();
        notices = fx.notices;
        files = fx.service.features().attachments();
        fx.plant();
        ritas = intake.submit( RITA, new IntakeDraft( "WIKI", null, null, "rita's report", "it breaks" ) ).key();
        lenas = fx.newItem( LENA );
        fx.dispatchAll();
    }

    private Item item( final String key ) throws SQLException {
        return fx.store.item( key ).orElseThrow();
    }

    private static String q( final String s ) {
        return s == null ? "NULL" : "'" + s.replace( "'", "''" ) + "'";
    }

    /** Writes an event straight into the log (as any writer of the module would), returning its id. */
    private long event( final Item on, final String type, final String from, final String to, final String note,
                        final Actor by ) throws SQLException {
        return WorkAgentFixtures.scalar( fx.ds, "INSERT INTO work_event (project_id, item_id, actor, actor_kind, agent_id, "
            + "type, from_value, to_value, note) VALUES (" + fx.project.id() + ", " + ( on == null ? "NULL" : on.id() ) + ", "
            + q( by.login() ) + ", " + q( by.kind() == Actor.Kind.AGENT ? "agent" : "human" ) + ", "
            + ( by.agentId() == null ? "NULL" : by.agentId() ) + ", " + q( type ) + ", " + q( from ) + ", " + q( to ) + ", "
            + q( note ) + ") RETURNING id" );
    }

    /** A notice row as the dispatcher wrote it (used for rows written while the reader was still a member). */
    private void notice( final String recipient, final long eventId, final Item on, final NoticeReason reason )
            throws SQLException {
        WorkAgentFixtures.exec( fx.ds, "INSERT INTO work_notification (recipient, event_id, project_id, item_id, reason) "
            + "VALUES (" + q( recipient ) + ", " + eventId + ", " + fx.project.id() + ", " + ( on == null ? "NULL" : on.id() )
            + ", " + q( reason.wire() ) + ")" );
    }

    private Set< String > ritasNoticeTypes() throws SQLException {
        final Set< String > out = new TreeSet<>();
        try ( java.sql.Connection c = fx.ds.getConnection(); java.sql.Statement st = c.createStatement();
              java.sql.ResultSet rs = st.executeQuery( "SELECT e.type FROM work_notification n JOIN work_event e "
                  + "ON e.id = n.event_id WHERE n.recipient = 'rita'" ) ) {
            while ( rs.next() ) {
                out.add( rs.getString( 1 ) );
            }
        }
        return out;
    }

    private Actor codex() throws SQLException {
        return Actor.agent( WorkAgentFixtures.agent( fx.ds, "codex@docker2" ), "codex@docker2", LENA );
    }

    // ---- A: auto-watch and standing at dispatch (ruling 2)

    @Test
    void aReporterBecomesAWatcherOfTheirOwnItem() throws SQLException {
        assertEquals( 1, fx.watches( "rita" ) );
        assertEquals( 1, WorkAgentFixtures.scalar( fx.ds, "SELECT COUNT(*) FROM work_watch WHERE login = 'rita' AND item_id = "
            + item( ritas ).id() ) );
        assertEquals( WatchState.WATCHING, notices.watchState( RITA, ritas ) );
        // a mention of a person who only reports stores nothing, so it neither watches nor notifies
        final Comment c = fx.store.addComment( lenas, OTTO, "@[rita](user:rita) look", List.of() );
        fx.dispatchAll();
        assertEquals( 1, fx.watches( "rita" ), "no watch on another item, mentioned or not (" + c.id() + ")" );
        assertEquals( 0, fx.rows( "recipient = 'rita'" ) );
    }

    @Test
    void theCacheDoesNotLeakAcrossItemsOfOneProject() throws SQLException {
        // a watch row on lena's item left from before (a member once): rita is a watcher of both items
        fx.store.notifyStores().watches().subscribe( lenas.id(), "rita" );
        fx.patch( OTTO, item( ritas ), "status", "ready" );
        fx.patch( OTTO, lenas, "status", "in_progress" );
        fx.dispatchAll();
        assertEquals( 1, fx.rows( "recipient = 'rita'" ) );
        assertEquals( 1, fx.rows( "recipient = 'rita' AND item_id = " + item( ritas ).id() ) );
        assertEquals( 0, fx.rows( "recipient = 'rita' AND item_id = " + lenas.id() ) );
    }

    @Test
    void aRemovedReporterGetsNoWatchRow() throws SQLException {
        WorkAgentFixtures.exec( fx.ds, "DELETE FROM work_watch" );
        fx.service.removeMember( LENA, "WIKI", "rita", "user" );
        intake.submit( LENA, new IntakeDraft( "WIKI", null, null, "lena files too", "" ) );
        WorkAgentFixtures.exec( fx.ds, "UPDATE work_item SET reporter = 'rita' WHERE title = 'lena files too'" );
        fx.dispatchAll();
        assertEquals( 0, fx.watches( "rita" ) );
    }

    @Test
    void aRemovedReporterSeesNoOldRowsAndGetsNoNewOnes() throws SQLException {
        fx.patch( OTTO, item( ritas ), "status", "ready" );
        fx.dispatchAll();
        assertEquals( 1, notices.unread( RITA ) );
        assertEquals( 1, notices.inbox( RITA, null, 0 ).size() );
        fx.service.removeMember( LENA, "WIKI", "rita", "user" );
        assertEquals( 0, notices.unread( RITA ) );
        assertEquals( List.of(), notices.inbox( RITA, null, 0 ) );
        fx.patch( OTTO, item( ritas ), "status", "refining" );
        fx.dispatchAll();
        assertEquals( 1, fx.rows( "recipient = 'rita'" ), "no new row after the removal" );
        assertThrows( WorkNotFoundException.class, () -> notices.watchState( RITA, ritas ) );
    }

    // ---- B: which events reach a reporter

    @Test
    void theReporterHearsOfStatusAndCommentsOnTheirItem() throws SQLException {
        fx.service.addComment( OTTO, ritas, "we are on it" );
        fx.patch( OTTO, item( ritas ), "status", "ready" );
        fx.dispatchAll();
        assertEquals( Set.of( WorkEvent.COMMENTED, WorkEvent.STATUS ), ritasNoticeTypes() );
        assertEquals( 2, notices.unread( RITA ) );
        intake.comment( RITA, ritas, "thanks" );
        fx.dispatchAll();
        assertEquals( 2, fx.rows( "recipient = 'rita'" ), "her own comment does not notify her" );
        assertEquals( 1, fx.rows( "recipient = 'otto' AND reason = 'watching'" ), "otto, a commenter, hears of hers" );
    }

    /** B: every event type the module defines, against a reporter who watches her item: only the allow-list reaches her. */
    @Test
    void onlyTheAllowListedTypesReachAReporter() throws Exception {
        final long comment = fx.store.addComment( item( ritas ), OTTO, "hello", List.of() ).id();
        final Set< String > every = new TreeSet<>();
        for ( final Field f : WorkEvent.class.getFields() ) {
            if ( Modifier.isStatic( f.getModifiers() ) && f.getType() == String.class ) {
                every.add( (String) f.get( null ) );
            }
        }
        assertTrue( every.size() > 30, "every type constant was found: " + every );
        final Item mine = item( ritas );
        for ( final String type : every ) {
            switch ( type ) {
                case WorkEvent.HANDOFF -> event( mine, type, "ready|queue:implementer", "done|otto", SECRET_NOTE, OTTO );
                case WorkEvent.RELEASED -> event( mine, type, "agent:1", "ready", "released", OTTO );
                case WorkEvent.COMMENTED, WorkEvent.COMMENT_EDITED -> event( mine, type, null, String.valueOf( comment ), null, OTTO );
                case WorkEvent.ASSIGNED -> event( mine, type, null, "rita", SECRET_NOTE, OTTO );
                case WorkEvent.SETTINGS -> event( mine, type, "https://h.example", "failed", "webhook", OTTO );
                default -> event( mine, type, "ready", "done", SECRET_NOTE, OTTO );
            }
        }
        fx.dispatchAll();
        assertEquals( new TreeSet<>( ReporterEvents.NOTIFIED ), ritasNoticeTypes() );
        assertEquals( Set.of( WorkEvent.STATUS, WorkEvent.HANDOFF, WorkEvent.CLAIMED, WorkEvent.CLAIM_EXPIRED,
            WorkEvent.RELEASED, WorkEvent.COMMENTED ), ReporterEvents.NOTIFIED );
        for ( final NoticeView v : notices.inbox( RITA, null, 100 ) ) {
            assertTrue( Set.of( WorkEvent.STATUS, WorkEvent.COMMENTED ).contains( v.event().type() ), v.toString() );
            assertEquals( NoticeReason.WATCHING, v.reason() );
            assertFalse( v.toString().contains( SECRET_NOTE ) || v.toString().contains( "queue:" ), v.toString() );
        }
        assertEquals( notices.inbox( RITA, null, 100 ).size(), notices.unread( RITA ) );
    }

    /** The inbox SQL and the dispatcher's Java rule are the same rule: compared over every type and the edge values. */
    @Test
    void theInboxSqlAndTheJavaRuleAgree() throws Exception {
        final Item mine = item( ritas );
        final List< String[] > values = List.of( new String[] { "ready", "done", null }, new String[] { "review", "review", null },
            new String[] { null, "ready", null }, new String[] { "ready", null, null }, new String[] { "ready", "otto", null },
            new String[] { "ready|otto", "done|queue:x", "n" }, new String[] { "ready|otto", "ready|queue:x", "n" },
            new String[] { null, "done|otto", null }, new String[] { "agent:1", "ready", "released" },
            new String[] { "agent:1", "ready", "moved" }, new String[] { "agent:1", "ready", "reassigned" },
            new String[] { "agent:1", "review", null }, new String[] { "agent:1", "ready", null } );
        for ( final Field f : WorkEvent.class.getFields() ) {
            if ( Modifier.isStatic( f.getModifiers() ) && f.getType() == String.class ) {
                for ( final String[] v : values ) {
                    event( mine, (String) f.get( null ), v[ 0 ], v[ 1 ], v[ 2 ], OTTO );
                }
            }
        }
        final Set< Long > java = new TreeSet<>();
        for ( final WorkEvent e : fx.store.itemEvents( mine.id() ) ) {
            if ( ReporterEvents.notifies( e ) ) {
                java.add( e.id() );
            }
        }
        final Set< Long > sql = new TreeSet<>();
        try ( java.sql.Connection c = fx.ds.getConnection(); java.sql.Statement st = c.createStatement();
              java.sql.ResultSet rs = st.executeQuery( "SELECT re.id FROM work_event re WHERE re.item_id = " + mine.id()
                  + " AND " + ReporterEvents.notifiedSql( "re" ) ) ) {
            while ( rs.next() ) {
                sql.add( rs.getLong( 1 ) );
            }
        }
        assertTrue( java.size() > 20, "the comparison covers real rows: " + java.size() );
        assertEquals( java, sql );
    }

    @Test
    void aStatusRouteThatMovesNothingIsNotHeard() throws SQLException {
        final Item mine = item( ritas );
        event( mine, WorkEvent.HANDOFF, "ready|otto", "ready|queue:implementer", SECRET_NOTE, LENA );
        event( mine, WorkEvent.CLAIMED, "review", "review", null, LENA );
        event( mine, WorkEvent.RELEASED, "agent:1", "ready", "moved", LENA );
        event( mine, WorkEvent.RELEASED, "agent:1", "review", "released", LENA );
        event( mine, WorkEvent.STATUS, "ready", "nonsense", null, LENA );
        fx.dispatchAll();
        assertEquals( 0, fx.rows( "recipient = 'rita'" ) );
    }

    @Test
    void theReporterDoesNotHearOfHandoffsChangesOrDeployments() throws SQLException {
        final Product web = fx.store.createProduct( new Product( 0, "web", "Web", null, null, null, Autonomy.DELEGATED,
            DeployGate.HUMAN, null ) );
        fx.patch( LENA, item( ritas ), "productKey", "web" );
        fx.patch( LENA, item( ritas ), "labels", List.of( "secret-label" ) );
        fx.patch( LENA, item( ritas ), "assignee", "otto" );
        fx.store.addLink( item( ritas ), "url", "https://example.com/secret", LENA );
        final Change ch = fx.store.changes().open( item( ritas ), web.id(),
            new ChangeDraft( "work/secret-branch", "main", "abc1234", null, null, null ), LENA );
        fx.store.changes().merge( item( ritas ), ch.id(), "def5678", c -> MergeKind.UNREVIEWED, LENA );
        event( item( ritas ), WorkEvent.HANDOFF, "draft|otto", "draft|queue:implementer", SECRET_NOTE, LENA );
        event( item( ritas ), WorkEvent.SHIPPED, "deployment:1", "1.0", null, LENA );
        fx.dispatchAll();
        assertEquals( 0, fx.rows( "recipient = 'rita'" ), ritasNoticeTypes().toString() );
    }

    /** Choice 16(b): an agent's hand-off that moves the item reaches the reporter as one safe status line. */
    @Test
    void anAgentsHandoffAsDoneReachesTheReporterAsOneSafeLine() throws SQLException {
        final Actor codex = codex();
        event( item( ritas ), WorkEvent.HANDOFF, "review|queue:implementer", "done|otto", SECRET_NOTE, codex );
        fx.dispatchAll();
        final List< NoticeView > inbox = notices.inbox( RITA, null, 0 );
        assertEquals( 1, inbox.size() );
        final WorkEvent e = inbox.get( 0 ).event();
        assertEquals( WorkEvent.STATUS, e.type() );
        assertEquals( "review", e.fromValue() );
        assertEquals( "done", e.toValue() );
        assertNull( e.note() );
        assertNull( e.agentId() );
        assertEquals( "codex@docker2", e.actor() );
        assertEquals( NoticeReason.WATCHING, inbox.get( 0 ).reason() );
        final String text = inbox.toString();
        for ( final String hidden : List.of( SECRET_NOTE, "otto", "queue:", "implementer", "|" ) ) {
            assertFalse( text.contains( hidden ), hidden + " in " + text );
        }
        assertEquals( "codex@docker2 moved this to done", NoticeText.reporterLine( e, n -> n ) );
        // the member who was assigned reads the event as it was
        final NoticeView otto = notices.inbox( OTTO, null, 0 ).get( 0 );
        assertEquals( WorkEvent.HANDOFF, otto.event().type() );
        assertEquals( SECRET_NOTE, otto.event().note() );
    }

    // ---- C: what a notice carries (ruling 1)

    @Test
    void theReportersInboxCarriesNoNote() throws SQLException {
        fx.patch( OTTO, item( ritas ), "status", "ready" );
        WorkAgentFixtures.exec( fx.ds, "UPDATE work_event SET note = '" + SECRET_NOTE + "' WHERE type = 'status'" );
        fx.notices.setWatch( OTTO, ritas, WatchState.WATCHING );
        fx.patch( LENA, item( ritas ), "status", "refining" );
        WorkAgentFixtures.exec( fx.ds, "UPDATE work_event SET note = '" + SECRET_NOTE + "' WHERE type = 'status'" );
        fx.dispatchAll();
        final List< NoticeView > mine = notices.inbox( RITA, null, 0 );
        assertEquals( 2, mine.size() );
        assertFalse( mine.toString().contains( SECRET_NOTE ), mine.toString() );
        // the same event, read by a member: unchanged
        final List< NoticeView > ottos = notices.inbox( OTTO, null, 0 );
        assertEquals( SECRET_NOTE, ottos.get( 0 ).event().note() );
        assertEquals( mine.get( 0 ).event().id(), ottos.get( 0 ).event().id() );
    }

    /**
     * Rows written while rita was still a member (a hand-off with a note, an assignment, an item-less settings event,
     * a notice about lena's item): after the demotion only the status moves of her own item show, reshaped, and the
     * unread count and mark-all-read cover exactly those.
     */
    @Test
    void aDemotedMembersOldNoticesShowOnlyAsAReporterMay() throws SQLException {
        final Item mine = item( ritas );
        notice( "rita", event( mine, WorkEvent.HANDOFF, "draft|", "ready|otto", SECRET_NOTE, LENA ), mine, NoticeReason.ASSIGNED );
        notice( "rita", event( mine, WorkEvent.HANDOFF, "ready|otto", "ready|queue:implementer", SECRET_NOTE, LENA ), mine,
            NoticeReason.ASSIGNED );
        notice( "rita", event( mine, WorkEvent.ASSIGNED, null, "rita", SECRET_NOTE, LENA ), mine, NoticeReason.ASSIGNED );
        notice( "rita", event( mine, WorkEvent.STATUS, "ready", "review", SECRET_NOTE, LENA ), mine, NoticeReason.WATCHING );
        notice( "rita", event( null, WorkEvent.SETTINGS, "https://h.example", "failed", "webhook", LENA ), null,
            NoticeReason.WEBHOOK_DISABLED );
        notice( "rita", event( lenas, WorkEvent.STATUS, "ready", "done", SECRET_NOTE, LENA ), lenas, NoticeReason.WATCHING );
        final List< NoticeView > inbox = notices.inbox( RITA, null, 0 );
        assertEquals( List.of( "review", "ready" ), inbox.stream().map( v -> v.event().toValue() ).toList() );
        assertTrue( inbox.stream().allMatch( v -> WorkEvent.STATUS.equals( v.event().type() )
            && v.reason() == NoticeReason.WATCHING && ritas.equals( v.event().itemKey() ) ) );
        assertFalse( inbox.toString().contains( SECRET_NOTE ) || inbox.toString().contains( "otto" ), inbox.toString() );
        assertEquals( 2, notices.unread( RITA ) );
        notices.markAllRead( RITA );
        assertEquals( 0, notices.unread( RITA ) );
        assertEquals( 2, fx.rows( "recipient = 'rita' AND read_at IS NOT NULL" ) );
        assertEquals( 4, fx.rows( "recipient = 'rita' AND read_at IS NULL" ), "rows she may not read are not touched" );
    }

    // ---- D: email

    @Test
    void emailForAReporterCarriesOnlyTheirEvents() throws SQLException {
        final Item mine = item( ritas );
        fx.patch( OTTO, mine, "status", "ready" );
        fx.service.addComment( OTTO, ritas, "we are on it" );
        event( item( ritas ), WorkEvent.HANDOFF, "ready|queue:implementer", "done|otto", SECRET_NOTE, codex() );
        fx.patch( LENA, item( ritas ), "assignee", "otto" );
        fx.dispatchAll();
        // rows from her member days: one about lena's item, one hand-off that moved nothing
        notice( "rita", event( lenas, WorkEvent.STATUS, "ready", "done", SECRET_NOTE, LENA ), lenas, NoticeReason.WATCHING );
        notice( "rita", event( mine, WorkEvent.HANDOFF, "done|otto", "done|queue:implementer", SECRET_NOTE, LENA ), mine,
            NoticeReason.ASSIGNED );
        WorkAgentFixtures.exec( fx.ds, "UPDATE work_notification SET created_at = '" + NOW.minus( Duration.ofMinutes( 1 ) ) + "'" );
        fx.notices.sendEmail( NOW );
        final List< Sent > toRita = sent.stream().filter( s -> s.to().equals( "rita@example.com" ) ).toList();
        assertEquals( 1, toRita.size() );
        final Sent mail = toRita.get( 0 );
        assertEquals( "Work: 3 updates on " + ritas, mail.subject() );
        assertTrue( mail.text().contains( "otto moved this to ready" ), mail.text() );
        assertTrue( mail.text().contains( "otto commented" ), mail.text() );
        assertTrue( mail.text().contains( "codex@docker2 moved this to done" ), mail.text() );
        for ( final String hidden : List.of( SECRET_NOTE, lenas.key(), "queue", "implementer", "handed", "|" ) ) {
            assertFalse( mail.text().contains( hidden ) || mail.subject().contains( hidden ), hidden + " in " + mail.text() );
        }
        assertEquals( 0, fx.rows( "recipient = 'rita' AND emailed_at IS NULL" ), "the rows she may not read are stamped" );
    }

    @Test
    void emailStandingIsRecheckedAtSendTime() throws SQLException {
        fx.patch( OTTO, item( ritas ), "status", "ready" );
        fx.dispatchAll();
        fx.service.removeMember( LENA, "WIKI", "rita", "user" );
        WorkAgentFixtures.exec( fx.ds, "UPDATE work_notification SET created_at = '" + NOW.minus( Duration.ofMinutes( 1 ) ) + "'" );
        fx.notices.sendEmail( NOW );
        assertTrue( sent.stream().noneMatch( s -> s.to().equals( "rita@example.com" ) ) );
    }

    // ---- E: the watch and read entry points (ruling 4)

    @Test
    void aReporterReadsTheirWatchStateAndClearsTheirUnread() throws SQLException {
        assertEquals( WatchState.WATCHING, notices.watchState( RITA, ritas ) );
        fx.patch( OTTO, item( ritas ), "status", "ready" );
        fx.service.addComment( OTTO, ritas, "a" );
        fx.dispatchAll();
        assertEquals( 2, notices.unread( RITA ) );
        notices.markAllRead( RITA );
        assertEquals( 0, notices.unread( RITA ) );
        fx.service.addComment( OTTO, ritas, "b" );
        fx.dispatchAll();
        assertEquals( 1, notices.unread( RITA ) );
        notices.markRead( RITA, ritas );
        assertEquals( 0, notices.unread( RITA ) );
    }

    @Test
    void aReporterWatchesAndMarksReadOnlyTheirOwnItem() throws SQLException {
        assertEquals( WatchState.MUTED, notices.setWatch( RITA, ritas, WatchState.MUTED ) );
        assertEquals( WatchState.NONE, notices.setWatch( RITA, ritas, WatchState.NONE ) );
        fx.patch( OTTO, item( ritas ), "status", "ready" );
        fx.dispatchAll();
        assertEquals( 0, fx.rows( "recipient = 'rita'" ), "she stopped watching" );
        assertEquals( WatchState.WATCHING, notices.setWatch( RITA, ritas, WatchState.WATCHING ) );
        fx.patch( OTTO, item( ritas ), "status", "refining" );
        fx.dispatchAll();
        assertEquals( 1, fx.rows( "recipient = 'rita'" ), "and started again" );
        for ( final String other : List.of( lenas.key(), "WIKI-99", "WIKI" ) ) {
            final List< Executable > calls = List.of( () -> notices.watchState( RITA, other ),
                () -> notices.setWatch( RITA, other, WatchState.WATCHING ), () -> notices.markRead( RITA, other ) );
            for ( final Executable call : calls ) {
                final WorkNotFoundException e = assertThrows( WorkNotFoundException.class, call, other );
                assertEquals( WorkScopes.NOT_FOUND, e.getMessage() );
            }
        }
        assertEquals( 1, fx.watches( "rita" ), "no watch row on another item" );
    }

    /** I2: the caller's own records, driven as a reporter: preference reads and writes only her own row. */
    @Test
    void aReportersOwnPreferenceIsHers() throws SQLException {
        assertEquals( EmailMode.EACH, notices.preference( RITA ).email() );
        assertEquals( EmailMode.DAILY, notices.setPreference( RITA, EmailMode.DAILY ).email() );
        assertEquals( EmailMode.DAILY, notices.preference( RITA ).email() );
        assertEquals( EmailMode.EACH, notices.preference( OTTO ).email(), "nobody else's choice changed" );
    }

    // ---- F: attachments (ruling 3)

    @Test
    void aReporterAttachesToAndReadsTheirOwnItem() throws SQLException {
        final Attachment ottos = files.upload( OTTO, ritas, null, "screen.png", PNG );
        final Attachment hers = files.upload( RITA, ritas, null, "mine.png", PNG );
        assertEquals( "rita", hers.uploadedBy() );
        assertEquals( List.of( ottos.id(), hers.id() ), files.list( RITA, ritas ).stream().map( Attachment::id ).toList() );
        assertEquals( ottos.id(), files.content( RITA, ritas, ottos.id() ).attachment().id() );
        assertTrue( files.mayAttach( RITA, ritas ) );
        assertEquals( 0, fx.rows( "recipient = 'rita'" ), "an attachment notifies nobody (choice 18)" );
    }

    @Test
    void aReporterSeesTheListingWithMayAttachTrue() throws SQLException {
        files.upload( OTTO, ritas, null, "screen.png", PNG );
        final AttachmentListing l = files.listing( RITA, ritas );
        assertTrue( l.mayAttach() );
        assertEquals( 1, l.attachments().size() );
    }

    @Test
    void aSiblingsAttachmentIsNotFound() throws SQLException {
        final Attachment onLenas = files.upload( LENA, lenas.key(), null, "secret.png", PNG );
        final String other = lenas.key();
        final List< Executable > calls = List.of( () -> files.list( RITA, other ), () -> files.listing( RITA, other ),
            () -> files.content( RITA, other, onLenas.id() ), () -> files.mayAttach( RITA, other ),
            () -> files.upload( RITA, other, null, "x.png", PNG ), () -> files.remove( RITA, other, onLenas.id() ),
            () -> files.content( RITA, ritas, onLenas.id() ), () -> files.remove( RITA, ritas, onLenas.id() ) );
        for ( final Executable call : calls ) {
            assertEquals( WorkScopes.NOT_FOUND, assertThrows( WorkNotFoundException.class, call ).getMessage() );
        }
    }

    @Test
    void aReporterRemovesOnlyTheirOwnUpload() throws SQLException {
        final Attachment hers = files.upload( RITA, ritas, null, "mine.png", PNG );
        final Attachment ottos = files.upload( OTTO, ritas, null, "screen.png", PNG );
        files.remove( RITA, ritas, hers.id() );
        final WorkDeniedException e = assertThrows( WorkDeniedException.class, () -> files.remove( RITA, ritas, ottos.id() ) );
        assertEquals( "Only the uploader may remove this file.", e.getMessage() );
        assertEquals( List.of( ottos.id() ), files.list( RITA, ritas ).stream().map( Attachment::id ).toList() );
    }

    @Test
    void anArchivedProjectRefusesAReportersUpload() throws SQLException {
        fx.service.archiveProject( ROOT, "WIKI" );
        final WorkConflictException e = assertThrows( WorkConflictException.class,
            () -> files.upload( RITA, ritas, null, "mine.png", PNG ) );
        assertEquals( WorkConflictException.ARCHIVED, e.reason() );
        assertFalse( files.listing( RITA, ritas ).mayAttach() );
    }

    // ---- H: no agent widens anything through these paths

    @Test
    void anAgentNamedLikeTheReporterGetsNothingHere() throws SQLException {
        final Actor namesake = Actor.agent( WorkAgentFixtures.agent( fx.ds, "rita" ), "rita", RITA );
        final List< Executable > calls = List.of( () -> files.list( namesake, ritas ), () -> files.listing( namesake, ritas ),
            () -> files.mayAttach( namesake, ritas ), () -> files.upload( namesake, ritas, null, "x.png", PNG ),
            () -> notices.watchState( namesake, ritas ), () -> notices.setWatch( namesake, ritas, WatchState.WATCHING ),
            () -> notices.markRead( namesake, ritas ), () -> intake.item( namesake, ritas ),
            () -> intake.comment( namesake, ritas, "x" ) );
        for ( final Executable call : calls ) {
            assertThrows( WorkNotFoundException.class, call );
        }
        assertEquals( List.of(), notices.inbox( namesake, null, 0 ) );
        assertEquals( 0, notices.unread( namesake ) );
    }

    // ---- G: every other surface still answers a reporter with nothing (data present on her own item)

    @Test
    void everyOtherSurfaceAnswersAReporterWithNothing() throws SQLException {
        final Product web = fx.store.createProduct( new Product( 0, "web", "Web", null, null, null, Autonomy.DELEGATED,
            DeployGate.HUMAN, null ) );
        final String epic = fx.service.createItem( LENA, new ItemDraft( "WIKI", WorkLevel.EPIC, null, null, WorkKind.SOFTWARE,
            "secret epic", "", null, null, null, null, null, null ) ).item().key();
        final LocalDate day = LocalDate.parse( "2026-03-02" );
        final long sprint = fx.service.features().sprints().create( LENA, "WIKI", new SprintDraft( "S1", "", day,
            day.plusDays( 13 ) ) ).sprint().id();
        fx.patch( LENA, item( ritas ), "status", "ready" );
        fx.patch( LENA, item( ritas ), "productKey", "web" );
        fx.patch( LENA, item( ritas ), "parentKey", epic );
        fx.patch( LENA, item( ritas ), "labels", List.of( "secret-label" ) );
        fx.patch( LENA, item( ritas ), "estimate", 3 );
        fx.patch( LENA, item( ritas ), "sprint", sprint );
        final Change ch = fx.store.changes().open( item( ritas ), web.id(),
            new ChangeDraft( "work/secret-branch", "main", "abc1234", null, null, null ), LENA );
        fx.store.changes().merge( item( ritas ), ch.id(), "def5678", c -> MergeKind.UNREVIEWED, LENA );
        final long dep = fx.store.deployments().request( web, "production", "1.0", List.of( item( ritas ) ), LENA ).id();
        fx.store.deployments().move( dep, DeploymentState.APPROVED, null, null, LENA );
        fx.store.deployments().move( dep, DeploymentState.RUNNING, null, null, LENA );
        fx.store.deployments().move( dep, DeploymentState.VERIFIED, "ok", null, LENA );
        fx.store.notifyStores().filters().insert( "lena", "Team view", fx.project.id(), true,
            new TreeMap<>( Map.of( "projects", "WIKI" ) ) );
        fx.store.notifyStores().webhooks().add( fx.project, java.net.URI.create( "https://hooks.example.com/secret" ),
            WebhookFormat.GENERIC, "s3cret", List.of(), LENA );
        // the data is there: lena sees it
        assertFalse( fx.service.changes().views( LENA, ritas ).isEmpty() );
        assertFalse( fx.service.deployments().shippedIn( LENA, ritas ).isEmpty() );
        final WorkFeatures f = fx.service.features();
        final List< Executable > notFound = List.of(
            () -> f.labelsOf( RITA, "WIKI" ), () -> f.sprints().list( RITA, "WIKI" ), () -> f.sprints().get( RITA, sprint ),
            () -> f.sprints().report( RITA, sprint ), () -> f.sprints().velocity( RITA, "WIKI" ),
            () -> f.flow().project( RITA, "WIKI", new FlowQuery( null, null, null, null, null, null ) ),
            () -> f.flow().dora( RITA, "WIKI", new DoraQuery( null, null, null ) ),
            () -> f.webhooks().list( RITA, "WIKI" ), () -> fx.service.changes().views( RITA, ritas ),
            () -> fx.service.changes().acknowledgeFlag( RITA, ch.id() ), () -> fx.service.deployments().shippedIn( RITA, ritas ),
            () -> fx.service.deployments().deployment( RITA, dep ),
            () -> fx.service.activity( RITA, "WIKI", null, 50 ), () -> fx.service.members( RITA, "WIKI" ),
            () -> fx.service.board( RITA, "WIKI", null ), () -> fx.service.item( RITA, ritas ),
            () -> fx.service.events( RITA, ritas ), () -> fx.service.acknowledge( RITA, ritas ) );
        for ( final Executable call : notFound ) {
            assertThrows( WorkNotFoundException.class, call );
        }
        assertEquals( List.of(), fx.service.waitingOn( RITA ) );
        assertEquals( List.of(), fx.service.assignmentLog( RITA, AssignmentLogQuery.all() ) );
        assertEquals( List.of(), fx.service.listProducts( RITA ) );
        assertEquals( List.of(), fx.service.registry().listAgents( RITA ) );
        assertEquals( List.of(), f.find().find( RITA, FindFilter.all() ).items() );
        assertEquals( List.of(), f.find().saved( RITA ) );
        // her own view carries none of it
        final String view = intake.item( RITA, ritas ).toString();
        for ( final String hidden : List.of( "secret epic", "secret-label", "secret-branch", "abc1234", "otto", "Web" ) ) {
            assertFalse( view.contains( hidden ), hidden + " in " + view );
        }
        assertEquals( new BigDecimal( "3" ).compareTo( item( ritas ).estimate() ), 0, "the estimate is there to hide" );
    }
}
