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

import javax.sql.DataSource;
import java.sql.SQLException;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Agent attribution, queue assignment, claim clearing and hand-off of {@link JdbcWorkStore}, against PostgreSQL. */
@RequiresPostgres
class JdbcWorkStoreAttributionPostgresTest {

    private static final Actor LENA = Actor.human( "lena", Set.of(), false );

    private DataSource ds;
    private JdbcWorkStore store;
    private Project plat;
    private long agentId;
    private Actor agent;

    @BeforeEach
    void setUp() throws SQLException {
        ds = PostgresTestDb.createDataSource();
        PostgresTestDb.truncate( WorkAgentFixtures.TABLES );
        store = new JdbcWorkStore( ds );
        plat = store.createProject( "PLAT", "Platform", "" );
        WorkAgentFixtures.queue( ds, "implementer" );
        WorkAgentFixtures.queue( ds, "reviewer" );
        agentId = WorkAgentFixtures.agent( ds, "codex@docker2" );
        agent = Actor.agent( agentId, "codex@docker2" );
    }

    private Item create( final String title ) throws SQLException {
        return store.createItem( plat, JdbcWorkStoreItemsPostgresTest.draft( title ), LENA );
    }

    private static Item with( final Item i, final String title, final String body, final WorkStatus status,
                              final String assignee, final String queue ) {
        return new Item( i.id(), i.projectId(), i.key(), i.level(), i.parentId(), i.productId(), i.kind(), title,
            body, status, i.priority(), i.rank(), i.dueOn(), assignee, i.reporter(), i.autonomy(), i.doneMeans(),
            i.allowDirect(), i.version(), i.createdAt(), i.updatedAt(), i.closedAt(), queue, i.claimedByAgent(),
            i.claimedAt(), i.claimExpiresAt(), i.reporterAgentId(), null, null, null, null );
    }

    private static Item moved( final Item i, final WorkStatus status, final String assignee, final String queue ) {
        return with( i, i.title(), i.body(), status, assignee, queue );
    }

    private void claim( final Item i ) throws SQLException {
        WorkAgentFixtures.exec( ds, "UPDATE work_item SET claimed_by_agent = " + agentId
            + ", claimed_at = NOW(), claim_expires_at = NOW() + INTERVAL '1 hour' WHERE id = " + i.id() );
    }

    private Item reload( final Item i ) throws SQLException {
        return store.itemById( i.id() ).orElseThrow();
    }

    private WorkEvent last( final Item i ) throws SQLException {
        final List< WorkEvent > events = store.itemEvents( i.id() );
        return events.get( events.size() - 1 );
    }

    @Test
    void agentCommentRecordsAgentId() throws SQLException {
        final Item item = create( "x" );
        final Comment c = store.addComment( item, agent, "hi", List.of() );
        assertEquals( agentId, c.authorAgentId() );
        assertEquals( "codex@docker2", c.author() );
        final WorkEvent e = last( item );
        assertEquals( WorkEvent.COMMENTED, e.type() );
        assertEquals( Actor.Kind.AGENT, e.actorKind() );
        assertEquals( agentId, e.agentId() );
    }

    @Test
    void agentCreateRecordsReporterAgent() throws SQLException {
        final Item item = store.createItem( plat, JdbcWorkStoreItemsPostgresTest.draft( "x" ), agent );
        assertEquals( agentId, item.reporterAgentId() );
        assertEquals( "codex@docker2", item.reporter() );
        assertEquals( agentId, store.itemEvents( item.id() ).get( 0 ).agentId() );
        assertEquals( agentId, store.revisions( item.id() ).get( 0 ).editedByAgentId() );
    }

    @Test
    void agentEditRecordsRevisionAgent() throws SQLException {
        final Item item = create( "x" );
        store.updateItem( item, with( item, "renamed", item.body(), item.status(), null, null ), agent, null );
        final List< ItemRevision > revisions = store.revisions( item.id() );
        assertNull( revisions.get( 0 ).editedByAgentId() );
        assertEquals( agentId, revisions.get( 1 ).editedByAgentId() );
        store.itemEvents( item.id() ).stream().filter( e -> WorkEvent.EDITED.equals( e.type() ) )
            .forEach( e -> assertEquals( agentId, e.agentId() ) );
    }

    @Test
    void everyAgentWriteCarriesTheAgentId() throws SQLException {
        final Item item = create( "x" );
        final Comment c = store.addComment( item, agent, "one", List.of() );
        store.editComment( c.id(), "two", agent, List.of() );
        final Link l = store.addLink( item, "url", "https://example.com", agent );
        store.removeLink( l.id(), agent );
        store.removeComment( c.id(), agent );
        store.updateItem( item, with( item, "t", item.body(), WorkStatus.READY, "bob", null ), agent, null );
        store.putMember( plat.id(), "bob", "user", WorkRole.MEMBER, agent );
        store.updateProject( plat.id(), "New name", "", plat.defaultAutonomy(), agent );
        store.removeMember( plat.id(), "bob", "user", agent );
        store.archiveProject( plat.id(), java.time.Instant.now(), agent );
        final long agentEvents = WorkAgentFixtures.scalar( ds, "SELECT COUNT(*) FROM work_event "
            + "WHERE actor_kind = 'agent' AND agent_id = " + agentId );
        final long all = WorkAgentFixtures.scalar( ds, "SELECT COUNT(*) FROM work_event WHERE actor_kind = 'agent'" );
        assertTrue( all >= 10, "events: " + all );
        assertEquals( all, agentEvents, "an agent event without its agent id" );
    }

    @Test
    void agentActorWithoutAnIdIsRefusedAndWritesNothing() throws SQLException {
        final Item item = create( "x" );
        final Actor anonymousAgent = new Actor( "ghost", Set.of(), false, Actor.Kind.AGENT, null, null );
        assertThrows( IllegalArgumentException.class, () -> store.addComment( item, anonymousAgent, "hi", List.of() ) );
        assertThrows( IllegalArgumentException.class, () -> store.createItem( plat,
            JdbcWorkStoreItemsPostgresTest.draft( "y" ), anonymousAgent ) );
        assertThrows( IllegalArgumentException.class, () -> store.updateItem( item,
            with( item, "z", item.body(), item.status(), null, null ), anonymousAgent, null ) );
        assertThrows( IllegalArgumentException.class, () -> store.putMember( plat.id(), "bob", "user",
            WorkRole.MEMBER, anonymousAgent ) );
        assertEquals( 0, WorkAgentFixtures.scalar( ds, "SELECT COUNT(*) FROM work_comment" ) );
        assertEquals( 1, store.items( new ItemFilter( plat.id(), null, null, null, null, null, null, null, 0, null,
            null, null, null, null ) ).size() );
        assertEquals( 0, store.members( plat.id() ).size() );
    }

    @Test
    void humanWritesLeaveAgentColumnsNull() throws SQLException {
        final Item item = create( "x" );
        final Comment c = store.addComment( item, LENA, "hi", List.of() );
        store.updateItem( item, with( item, "renamed", item.body(), item.status(), null, null ), LENA, null );
        assertNull( item.reporterAgentId() );
        assertNull( c.authorAgentId() );
        store.revisions( item.id() ).forEach( r -> assertNull( r.editedByAgentId() ) );
        store.itemEvents( item.id() ).forEach( e -> {
            assertNull( e.agentId() );
            assertEquals( Actor.Kind.HUMAN, e.actorKind() );
        } );
    }

    @Test
    void assigningAQueueWritesQueueValue() throws SQLException {
        final Item item = create( "x" );
        final Item assigned = store.updateItem( item, with( item, item.title(), item.body(), item.status(), "lena",
            null ), LENA, null );
        final Item queued = store.updateItem( assigned, with( assigned, assigned.title(), assigned.body(),
            assigned.status(), null, "implementer" ), LENA, null );
        assertNull( queued.assignee() );
        assertEquals( "implementer", queued.queue() );
        final List< WorkEvent > events = store.itemEvents( item.id() );
        assertEquals( 3, events.size() );
        assertEquals( WorkEvent.ASSIGNED, events.get( 2 ).type() );
        assertEquals( "lena", events.get( 2 ).fromValue() );
        assertEquals( "queue:implementer", events.get( 2 ).toValue() );
    }

    @Test
    void createWithQueueStoresIt() throws SQLException {
        final Item d = JdbcWorkStoreItemsPostgresTest.draft( "q" );
        final Item draft = new Item( 0, 0, null, d.level(), null, null, d.kind(), "q", "b", WorkStatus.DRAFT, 3, 0,
            null, null, null, null, null, null, 0, null, null, null, "reviewer", null, null, null, null, null, null, null, null );
        assertEquals( "reviewer", store.createItem( plat, draft, LENA ).queue() );
    }

    @Test
    void handoffToQueueIsOneEvent() throws SQLException {
        final Item item = create( "x" );
        final Item working = store.updateItem( item, moved( item, WorkStatus.IN_PROGRESS, null, "implementer" ),
            LENA, null );
        final int before = store.itemEvents( item.id() ).size();
        final Item next = store.handoff( working, moved( working, WorkStatus.REVIEW, null, "reviewer" ), agent,
            "ready for review" );
        assertTrue( released( item ).isEmpty(), "no claim, no released event" );
        assertEquals( WorkStatus.REVIEW, next.status() );
        assertEquals( "reviewer", next.queue() );
        final List< WorkEvent > events = store.itemEvents( item.id() );
        assertEquals( before + 1, events.size() );
        final WorkEvent e = events.get( events.size() - 1 );
        assertEquals( WorkEvent.HANDOFF, e.type() );
        assertEquals( "in_progress|queue:implementer", e.fromValue() );
        assertEquals( "review|queue:reviewer", e.toValue() );
        assertEquals( "ready for review", e.note() );
        assertEquals( agentId, e.agentId() );
    }

    @Test
    void handoffWithoutNoteOrAssignmentChangeStillWritesOneHandoff() throws SQLException {
        final Item item = create( "x" );
        final int before = store.itemEvents( item.id() ).size();
        store.handoff( item, moved( item, WorkStatus.READY, null, null ), LENA, null );
        final List< WorkEvent > events = store.itemEvents( item.id() );
        assertEquals( before + 1, events.size() );
        assertEquals( WorkEvent.HANDOFF, events.get( before ).type() );
        assertEquals( "draft|", events.get( before ).fromValue() );
        assertEquals( "ready|", events.get( before ).toValue() );
    }

    @Test
    void humanMoveClearsClaim() throws SQLException {
        final Item item = create( "x" );
        claim( item );
        final Item claimed = reload( item );
        assertEquals( agentId, claimed.claimedByAgent() );
        final Item moved = store.updateItem( claimed, moved( claimed, WorkStatus.READY, null, null ), LENA, null );
        assertNull( moved.claimedByAgent() );
        assertNull( moved.claimedAt() );
        assertNull( moved.claimExpiresAt() );
        final List< WorkEvent > released = released( item );
        assertEquals( 1, released.size() );
        assertEquals( "agent:" + agentId, released.get( 0 ).fromValue() );
        assertEquals( "ready", released.get( 0 ).toValue() );
        assertEquals( "moved", released.get( 0 ).note() );
        assertEquals( "lena", released.get( 0 ).actor() );
        assertEquals( Actor.Kind.HUMAN, released.get( 0 ).actorKind() );
        assertEquals( WorkEvent.RELEASED, last( item ).type(), "released follows the change's own event" );
    }

    private List< WorkEvent > released( final Item i ) throws SQLException {
        return store.itemEvents( i.id() ).stream().filter( e -> WorkEvent.RELEASED.equals( e.type() ) ).toList();
    }

    @Test
    void holderHandingOffItsOwnClaimWritesReleasedAsItself() throws SQLException {
        final Item item = create( "x" );
        claim( item );
        final Item c = reload( item );
        store.handoff( c, moved( c, WorkStatus.REVIEW, null, "reviewer" ), agent, "done" );
        final List< WorkEvent > released = released( item );
        assertEquals( 1, released.size() );
        assertEquals( agentId, released.get( 0 ).agentId() );
        assertEquals( "agent:" + agentId, released.get( 0 ).fromValue() );
        assertEquals( "reassigned", released.get( 0 ).note() );
        assertEquals( "review", released.get( 0 ).toValue() );
        assertEquals( 1, store.itemEvents( item.id() ).stream().filter( e -> WorkEvent.HANDOFF.equals( e.type() ) )
            .count() );
    }

    @Test
    void reassignmentAndQueueMoveClearClaim() throws SQLException {
        final Item item = create( "x" );
        claim( item );
        final Item a = store.updateItem( reload( item ), moved( reload( item ), WorkStatus.DRAFT, "bob", null ), LENA,
            null );
        assertNull( a.claimedByAgent() );
        assertEquals( "reassigned", released( item ).get( 0 ).note() );
        claim( a );
        final Item q = store.handoff( reload( a ), moved( reload( a ), WorkStatus.DRAFT, null, "reviewer" ), agent,
            "to queue" );
        assertNull( q.claimedByAgent() );
        assertNull( q.claimExpiresAt() );
    }

    @Test
    void bodyEditKeepsClaim() throws SQLException {
        final Item item = create( "x" );
        claim( item );
        final Item claimed = reload( item );
        final Item edited = store.updateItem( claimed, with( claimed, claimed.title(), "new body", claimed.status(),
            null, null ), LENA, null );
        assertEquals( claimed.claimedByAgent(), edited.claimedByAgent() );
        assertNotNull( edited.claimedAt() );
        assertTrue( released( item ).isEmpty() );
        assertEquals( claimed.claimExpiresAt(), edited.claimExpiresAt() );
    }

    @Test
    void lastHandoffsPicksNewestPerItem() throws SQLException {
        final Item a = create( "a" );
        final Item b = create( "b" );
        final Item a1 = store.handoff( a, moved( a, WorkStatus.READY, null, "implementer" ), LENA, "first" );
        store.handoff( a1, moved( a1, WorkStatus.IN_PROGRESS, null, "reviewer" ), LENA, "second" );
        final Map< Long, WorkEvent > last = store.lastHandoffs( List.of( a.id(), b.id() ) );
        assertEquals( 1, last.size() );
        assertEquals( "second", last.get( a.id() ).note() );
        assertEquals( "ready|queue:implementer", last.get( a.id() ).fromValue() );
        assertTrue( store.lastHandoffs( List.of() ).isEmpty() );
    }

    @Test
    void queueFilterMatchesOnlyThatQueue() throws SQLException {
        final Item a = create( "a" );
        final Item b = create( "b" );
        create( "c" );
        store.updateItem( a, moved( a, WorkStatus.DRAFT, null, "implementer" ), LENA, null );
        store.updateItem( b, moved( b, WorkStatus.DRAFT, null, "reviewer" ), LENA, null );
        final ItemFilter filter = new ItemFilter( plat.id(), null, null, null, null, null, null, null, 0, null,
            "implementer", null, null, null );
        assertEquals( List.of( a.id() ), store.items( filter ).stream().map( Item::id ).toList() );
        final long onBoard = store.board( plat.id(), filter ).stream().mapToLong( col -> col.items().size() ).sum();
        assertEquals( 1, onBoard );
        assertEquals( 3, store.items( filter.withQueue( null ) ).size() );
    }

    private static Item both( final Item i ) {
        return with( i, i.title(), i.body(), i.status(), "lena", "implementer" );
    }

    @Test
    void personAndQueueTogetherAreRefusedBeforeAnySql() throws SQLException {
        final Item item = create( "x" );
        final Item d = JdbcWorkStoreItemsPostgresTest.draft( "y" );
        final Item bothDraft = new Item( 0, 0, null, d.level(), null, null, d.kind(), "y", "b", WorkStatus.DRAFT, 3,
            0, null, "lena", null, null, null, null, 0, null, null, null, "implementer", null, null, null, null, null, null, null, null );
        final WorkValidationException e = assertThrows( WorkValidationException.class,
            () -> store.createItem( plat, bothDraft, LENA ) );
        assertEquals( "queue", e.field() );
        assertEquals( "Assign a person or a queue, not both.", e.getMessage() );
        assertEquals( 1, WorkAgentFixtures.scalar( ds, "SELECT COUNT(*) FROM work_item" ) );
        assertThrows( WorkValidationException.class, () -> store.updateItem( item, both( item ), LENA, null ) );
        assertThrows( WorkValidationException.class, () -> store.handoff( item, both( item ), LENA, "n" ) );
        final Item after = reload( item );
        assertNull( after.assignee() );
        assertNull( after.queue() );
        assertEquals( item.version(), after.version() );
    }

    @Test
    void staleCopyWithoutClaimStillRecordsRelease() throws SQLException {
        final Item item = create( "x" );
        claim( item );
        store.updateItem( item, moved( item, WorkStatus.READY, null, null ), LENA, null );
        final List< WorkEvent > released = released( item );
        assertEquals( 1, released.size() );
        assertEquals( "agent:" + agentId, released.get( 0 ).fromValue() );
        assertNull( reload( item ).claimedByAgent() );
    }

    @Test
    void staleCopyNamesTheActualHolder() throws SQLException {
        final Item item = create( "x" );
        claim( item );
        final Item seen = reload( item );
        final long other = WorkAgentFixtures.agent( ds, "other" );
        WorkAgentFixtures.exec( ds, "UPDATE work_item SET claimed_by_agent = " + other + " WHERE id = " + item.id() );
        store.updateItem( seen, moved( seen, WorkStatus.DRAFT, "bob", null ), LENA, null );
        assertEquals( "agent:" + other, released( item ).get( 0 ).fromValue() );
    }

    @Test
    void bodyEditKeepsAClaimTakenAfterTheRead() throws SQLException {
        final Item item = create( "x" );
        claim( item );
        store.updateItem( item, with( item, item.title(), "new body", item.status(), null, null ), LENA, null );
        assertEquals( agentId, reload( item ).claimedByAgent() );
        assertTrue( released( item ).isEmpty() );
    }

    @Test
    void everyOtherAgentWritePathIsRefusedToo() throws SQLException {
        final Item item = create( "x" );
        final Comment c = store.addComment( item, LENA, "hi", List.of() );
        final Link l = store.addLink( item, "url", "https://example.com", LENA );
        final Actor ghost = new Actor( "ghost", Set.of(), false, Actor.Kind.AGENT, null, null );
        final long events = WorkAgentFixtures.scalar( ds, "SELECT COUNT(*) FROM work_event" );
        final Item next = with( item, "z", item.body(), item.status(), null, null );
        for ( final org.junit.jupiter.api.function.Executable w : List.< org.junit.jupiter.api.function.Executable >of(
            () -> store.editComment( c.id(), "two", ghost, List.of() ),
            () -> store.removeComment( c.id(), ghost ),
            () -> store.addLink( item, "url", "https://example.org", ghost ),
            () -> store.removeLink( l.id(), ghost ),
            () -> store.archiveProject( plat.id(), java.time.Instant.now(), ghost ),
            () -> store.updateProject( plat.id(), "Renamed", "", plat.defaultAutonomy(), ghost ),
            () -> store.removeMember( plat.id(), "bob", "user", ghost ),
            () -> store.handoff( item, next, ghost, "n" ) ) ) {
            assertThrows( IllegalArgumentException.class, w );
        }
        assertEquals( events, WorkAgentFixtures.scalar( ds, "SELECT COUNT(*) FROM work_event" ) );
        assertEquals( "hi", store.comment( c.id() ).orElseThrow().body() );
        assertEquals( 1, store.links( item.id() ).size() );
        assertEquals( plat.name(), store.project( "PLAT" ).orElseThrow().name() );
        assertNull( store.project( "PLAT" ).orElseThrow().archivedAt() );
        assertEquals( item.version(), reload( item ).version() );
    }

    @Test
    void archivingAnAlreadyArchivedProjectIsStillRefusedForAnUnattributedAgent() throws SQLException {
        final java.time.Instant at = java.time.Instant.now();
        store.archiveProject( plat.id(), at, LENA );
        final Actor ghost = new Actor( "ghost", Set.of(), false, Actor.Kind.AGENT, null, null );
        final long events = WorkAgentFixtures.scalar( ds, "SELECT COUNT(*) FROM work_event" );
        assertThrows( IllegalArgumentException.class, () -> store.archiveProject( plat.id(), at.plusSeconds( 5 ), ghost ) );
        assertEquals( events, WorkAgentFixtures.scalar( ds, "SELECT COUNT(*) FROM work_event" ) );
        assertEquals( at.truncatedTo( java.time.temporal.ChronoUnit.MILLIS ),
            store.project( "PLAT" ).orElseThrow().archivedAt().truncatedTo( java.time.temporal.ChronoUnit.MILLIS ) );
    }

    @Test
    void updatingAProjectWithNothingChangedIsStillRefusedForAnUnattributedAgent() throws SQLException {
        final Actor ghost = new Actor( "ghost", Set.of(), false, Actor.Kind.AGENT, null, null );
        final long events = WorkAgentFixtures.scalar( ds, "SELECT COUNT(*) FROM work_event" );
        assertThrows( IllegalArgumentException.class,
            () -> store.updateProject( plat.id(), plat.name(), plat.description(), plat.defaultAutonomy(), ghost ) );
        assertEquals( events, WorkAgentFixtures.scalar( ds, "SELECT COUNT(*) FROM work_event" ) );
    }

    @Test
    void aPersonActorCarryingAnAgentIdIsRefused() throws SQLException {
        // A person is never attributed to an agent: such an actor is a construction error, so nothing is written.
        final Actor mixed = new Actor( "lena", Set.of(), false, Actor.Kind.HUMAN, 7L, null );
        final long events = WorkAgentFixtures.scalar( ds, "SELECT COUNT(*) FROM work_event" );
        assertThrows( IllegalArgumentException.class, () -> store.updateProject( plat.id(), "Renamed", "",
            plat.defaultAutonomy(), mixed ) );
        assertThrows( IllegalArgumentException.class, () -> store.putMember( plat.id(), "bob", "user", WorkRole.MEMBER,
            mixed ) );
        assertEquals( events, WorkAgentFixtures.scalar( ds, "SELECT COUNT(*) FROM work_event" ) );
        assertEquals( plat.name(), store.project( "PLAT" ).orElseThrow().name() );
    }

    @Test
    void theStoreRefusesAnAgentLead() throws SQLException {
        final long agent = WorkAgentFixtures.agent( ds, "lead-hopeful" );
        final WorkValidationException e = assertThrows( WorkValidationException.class, () -> store.putMember( plat.id(),
            String.valueOf( agent ), "agent", WorkRole.LEAD, LENA ) );
        assertEquals( "role", e.field() );
        assertTrue( store.members( plat.id() ).stream().noneMatch( m -> "agent".equals( m.principalKind() ) ) );
    }
}
