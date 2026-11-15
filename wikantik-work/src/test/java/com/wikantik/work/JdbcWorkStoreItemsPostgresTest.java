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
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.TreeSet;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Items, revisions, events and the board query of {@link JdbcWorkStore}, against real PostgreSQL. */
@RequiresPostgres
class JdbcWorkStoreItemsPostgresTest {

    private JdbcWorkStore store;
    private Project plat;

    @BeforeEach
    void setUp() throws SQLException {
        store = new JdbcWorkStore( PostgresTestDb.createDataSource() );
        PostgresTestDb.truncate( "work_link", "work_mention", "work_comment", "work_event",
            "work_item_revision", "work_item", "work_member", "work_product", "work_project" );
        plat = store.createProject( "PLAT", "Platform", "" );
    }

    static Item draft( final String title ) {
        return draft( WorkLevel.TASK, title );
    }

    static Item draft( final WorkLevel level, final String title ) {
        return new Item( 0, 0, null, level, null, null, WorkKind.SOFTWARE, title, "body of " + title,
            WorkStatus.DRAFT, 3, 0, null, null, null, null, null, null, 0, null, null, null );
    }

    private Item create( final String title ) throws SQLException {
        return store.createItem( plat, draft( title ), "alice", Actor.Kind.HUMAN );
    }

    private static Item withStatus( final Item i, final WorkStatus s ) {
        return new Item( i.id(), i.projectId(), i.key(), i.level(), i.parentId(), i.productId(), i.kind(), i.title(),
            i.body(), s, i.priority(), i.rank(), i.dueOn(), i.assignee(), i.reporter(), i.autonomy(), i.doneMeans(),
            i.allowDirect(), i.version(), i.createdAt(), i.updatedAt(), i.closedAt() );
    }

    private static Item withAssignee( final Item i, final String a ) {
        return new Item( i.id(), i.projectId(), i.key(), i.level(), i.parentId(), i.productId(), i.kind(), i.title(),
            i.body(), i.status(), i.priority(), i.rank(), i.dueOn(), a, i.reporter(), i.autonomy(), i.doneMeans(),
            i.allowDirect(), i.version(), i.createdAt(), i.updatedAt(), i.closedAt() );
    }

    private static Item withPriority( final Item i, final int p ) {
        return new Item( i.id(), i.projectId(), i.key(), i.level(), i.parentId(), i.productId(), i.kind(), i.title(),
            i.body(), i.status(), p, i.rank(), i.dueOn(), i.assignee(), i.reporter(), i.autonomy(), i.doneMeans(),
            i.allowDirect(), i.version(), i.createdAt(), i.updatedAt(), i.closedAt() );
    }

    private static Item withTitle( final Item i, final String t ) {
        return new Item( i.id(), i.projectId(), i.key(), i.level(), i.parentId(), i.productId(), i.kind(), t,
            i.body(), i.status(), i.priority(), i.rank(), i.dueOn(), i.assignee(), i.reporter(), i.autonomy(),
            i.doneMeans(), i.allowDirect(), i.version(), i.createdAt(), i.updatedAt(), i.closedAt() );
    }

    private static Item withParentAndProduct( final Item i, final Long parent, final Long product ) {
        return new Item( i.id(), i.projectId(), i.key(), i.level(), parent, product, i.kind(), i.title(),
            i.body(), i.status(), i.priority(), i.rank(), i.dueOn(), i.assignee(), i.reporter(), i.autonomy(),
            i.doneMeans(), i.allowDirect(), i.version(), i.createdAt(), i.updatedAt(), i.closedAt() );
    }

    @Test
    void keysAreSequentialPerProject() throws SQLException {
        assertEquals( "PLAT-1", create( "a" ).key() );
        assertEquals( "PLAT-2", create( "b" ).key() );
        final Project other = store.createProject( "OPS", "Ops", "" );
        assertEquals( "OPS-1", store.createItem( other, draft( "c" ), "alice", Actor.Kind.HUMAN ).key() );
    }

    @Test
    void concurrentCreatesGetDistinctKeys() throws Exception {
        final ExecutorService pool = Executors.newFixedThreadPool( 8 );
        try {
            final List< Future< List< String > > > futures = new ArrayList<>();
            for ( int t = 0; t < 8; t++ ) {
                final int thread = t;
                final Callable< List< String > > job = () -> {
                    final List< String > keys = new ArrayList<>();
                    for ( int n = 0; n < 5; n++ ) {
                        keys.add( store.createItem( plat, draft( "t" + thread + "-" + n ), "alice",
                            Actor.Kind.HUMAN ).key() );
                    }
                    return keys;
                };
                futures.add( pool.submit( job ) );
            }
            final Set< String > all = new TreeSet<>();
            for ( final Future< List< String > > f : futures ) {
                all.addAll( f.get() );
            }
            assertEquals( 40, all.size() );
        } finally {
            pool.shutdownNow();
        }
    }

    @Test
    void createWritesRevisionAndEvent() throws SQLException {
        final Item i = create( "first" );
        assertEquals( 1, i.version() );
        assertEquals( "alice", i.reporter() );
        assertEquals( WorkStatus.DRAFT, i.status() );
        assertNotNull( i.createdAt() );
        assertNull( i.closedAt() );
        assertEquals( 1024.0, i.rank() );
        assertEquals( 2048.0, create( "second" ).rank() );
        final List< ItemRevision > revisions = store.revisions( i.id() );
        assertEquals( 1, revisions.size() );
        assertEquals( 1, revisions.get( 0 ).version() );
        assertEquals( "first", revisions.get( 0 ).title() );
        final List< WorkEvent > events = store.itemEvents( i.id() );
        assertEquals( 1, events.size() );
        assertEquals( WorkEvent.CREATED, events.get( 0 ).type() );
        assertEquals( "PLAT-1", events.get( 0 ).itemKey() );
        assertEquals( "alice", events.get( 0 ).actor() );
        assertEquals( Actor.Kind.HUMAN, events.get( 0 ).actorKind() );
    }

    @Test
    void lookupIsCaseInsensitive() throws SQLException {
        final Item i = create( "first" );
        assertEquals( i.id(), store.item( "plat-1" ).orElseThrow().id() );
        assertEquals( i.id(), store.item( "PLAT-1" ).orElseThrow().id() );
        assertEquals( Optional.empty(), store.item( "PLAT-99" ) );
        assertEquals( Optional.empty(), store.item( "garbage" ) );
        assertEquals( Optional.empty(), store.item( "PLAT-x" ) );
    }

    @Test
    void staleVersionThrowsConflict() throws SQLException {
        final Item i = create( "first" );
        store.updateItem( i, withPriority( i, 1 ), "alice", Actor.Kind.HUMAN, null );
        assertThrows( WorkConflictException.class,
            () -> store.updateItem( i, withPriority( i, 2 ), "bob", Actor.Kind.HUMAN, null ) );
        assertEquals( 1, store.item( "PLAT-1" ).orElseThrow().priority() );
    }

    @Test
    void titleChangeWritesRevisionAndEditedEvent() throws SQLException {
        final Item i = create( "first" );
        final Item u = store.updateItem( i, withTitle( i, "renamed" ), "bob", Actor.Kind.HUMAN, null );
        assertEquals( 2, u.version() );
        assertEquals( "renamed", u.title() );
        final List< ItemRevision > revisions = store.revisions( i.id() );
        assertEquals( 2, revisions.size() );
        assertEquals( "first", revisions.get( 0 ).title() );
        assertEquals( "renamed", revisions.get( 1 ).title() );
        assertEquals( "bob", revisions.get( 1 ).editedBy() );
        final List< WorkEvent > events = store.itemEvents( i.id() );
        assertEquals( List.of( WorkEvent.CREATED, WorkEvent.EDITED ), events.stream().map( WorkEvent::type ).toList() );
        assertNull( events.get( 1 ).fromValue() );
        assertNull( events.get( 1 ).toValue() );
    }

    @Test
    void priorityChangeWritesFieldEventOnly() throws SQLException {
        final Item i = create( "first" );
        final Item u = store.updateItem( i, withPriority( i, 1 ), "bob", Actor.Kind.HUMAN, null );
        assertEquals( 1, u.priority() );
        assertEquals( 1, store.revisions( i.id() ).size() );
        final List< WorkEvent > events = store.itemEvents( i.id() );
        assertEquals( 2, events.size() );
        final WorkEvent e = events.get( 1 );
        assertEquals( WorkEvent.FIELD, e.type() );
        assertEquals( "3", e.fromValue() );
        assertEquals( "1", e.toValue() );
        assertEquals( "priority", e.note() );
    }

    @Test
    void statusAndAssigneeWriteSeparateEventsWithoutNote() throws SQLException {
        final Item i = create( "first" );
        final Item next = withAssignee( withStatus( i, WorkStatus.READY ), "carol" );
        store.updateItem( i, next, "bob", Actor.Kind.HUMAN, null );
        final List< WorkEvent > events = store.itemEvents( i.id() );
        assertEquals( List.of( WorkEvent.CREATED, WorkEvent.STATUS, WorkEvent.ASSIGNED ),
            events.stream().map( WorkEvent::type ).toList() );
        assertEquals( "draft", events.get( 1 ).fromValue() );
        assertEquals( "ready", events.get( 1 ).toValue() );
        assertNull( events.get( 2 ).fromValue() );
        assertEquals( "carol", events.get( 2 ).toValue() );
    }

    @Test
    void statusPlusAssigneeWithNoteIsOneHandoffEvent() throws SQLException {
        final Item i = create( "first" );
        final Item next = withAssignee( withStatus( i, WorkStatus.REVIEW ), "carol" );
        store.updateItem( i, next, "bob", Actor.Kind.HUMAN, "over to you" );
        final List< WorkEvent > events = store.itemEvents( i.id() );
        assertEquals( List.of( WorkEvent.CREATED, WorkEvent.HANDOFF ), events.stream().map( WorkEvent::type ).toList() );
        assertEquals( "over to you", events.get( 1 ).note() );
        assertEquals( "draft|", events.get( 1 ).fromValue() );
        assertEquals( "review|carol", events.get( 1 ).toValue() );
    }

    @Test
    void closingSetsClosedAtAndReopeningClearsIt() throws SQLException {
        final Item i = create( "first" );
        final Item closed = store.updateItem( i, withStatus( i, WorkStatus.DONE ), "bob", Actor.Kind.HUMAN, null );
        assertNotNull( closed.closedAt() );
        final Item stillClosed = store.updateItem( closed, withStatus( closed, WorkStatus.CANCELLED ), "bob",
            Actor.Kind.HUMAN, null );
        assertEquals( closed.closedAt(), stillClosed.closedAt() );
        final Item reopened = store.updateItem( stillClosed, withStatus( stillClosed, WorkStatus.IN_PROGRESS ), "bob",
            Actor.Kind.HUMAN, null );
        assertNull( reopened.closedAt() );
    }

    @Test
    void boardReturnsAllSevenColumnsInOrder() throws SQLException {
        create( "a" );
        final List< BoardColumn > board = store.board( plat.id(), new ItemFilter( null, null, null, null, null, null,
            null, null, 0 ) );
        assertEquals( List.of( WorkStatus.values() ), board.stream().map( BoardColumn::status ).toList() );
        assertEquals( 1, board.get( 0 ).total() );
        assertEquals( 1, board.get( 0 ).items().size() );
        assertEquals( 0, board.get( 6 ).total() );
    }

    @Test
    void boardColumnsOrderByRankAndRespectFilter() throws SQLException {
        final Item a = create( "a" );
        final Item b = create( "b" );
        final Item c = create( "c" );
        final Item mine = store.updateItem( c, withAssignee( c, "dave" ), "alice", Actor.Kind.HUMAN, null );
        final List< BoardColumn > all = store.board( plat.id(),
            new ItemFilter( null, null, null, null, null, null, null, null, 0 ) );
        assertEquals( List.of( a.id(), b.id(), c.id() ), all.get( 0 ).items().stream().map( Item::id ).toList() );
        final List< BoardColumn > filtered = store.board( plat.id(),
            new ItemFilter( null, null, "dave", null, null, null, null, null, 0 ) );
        assertEquals( 1, filtered.get( 0 ).total() );
        assertEquals( mine.id(), filtered.get( 0 ).items().get( 0 ).id() );
    }

    @Test
    void doneColumnCappedAtFiftyWithTrueTotal() throws SQLException {
        for ( int n = 0; n < 60; n++ ) {
            final Item i = create( "t" + n );
            store.updateItem( i, withStatus( i, WorkStatus.DONE ), "alice", Actor.Kind.HUMAN, null );
        }
        final BoardColumn done = store.board( plat.id(),
            new ItemFilter( null, null, null, null, null, null, null, null, 0 ) ).get( 5 );
        assertEquals( WorkStatus.DONE, done.status() );
        assertEquals( 60, done.total() );
        assertEquals( 50, done.items().size() );
        assertEquals( "t59", done.items().get( 0 ).title() );
    }

    @Test
    void textFilterMatchesTitleAndBody() throws SQLException {
        store.createItem( plat, new Item( 0, 0, null, WorkLevel.TASK, null, null, WorkKind.DOCS, "Fix Login",
            "plain", WorkStatus.DRAFT, 3, 0, null, null, null, null, null, null, 0, null, null, null ),
            "alice", Actor.Kind.HUMAN );
        store.createItem( plat, new Item( 0, 0, null, WorkLevel.TASK, null, null, WorkKind.DOCS, "Other",
            "mentions LOGIN flow", WorkStatus.DRAFT, 3, 0, null, null, null, null, null, null, 0, null, null, null ),
            "alice", Actor.Kind.HUMAN );
        create( "unrelated" );
        assertEquals( 2, store.items( new ItemFilter( plat.id(), null, null, null, null, null, "login", null, 0 ) ).size() );
    }

    @Test
    void textFilterTreatsPercentAndUnderscoreLiterally() throws SQLException {
        final Item pct = create( "100% done" );
        create( "1000 done" );
        final Item under = create( "a_b marker" );
        create( "axb marker" );
        assertEquals( List.of( pct.id() ), store.items( new ItemFilter( plat.id(), null, null, null, null, null,
            "100%", null, 0 ) ).stream().map( Item::id ).toList() );
        assertEquals( List.of( under.id() ), store.items( new ItemFilter( plat.id(), null, null, null, null, null,
            "a_b", null, 0 ) ).stream().map( Item::id ).toList() );
    }

    @Test
    void filtersByStatusLevelAndDue() throws SQLException {
        final Item epic = store.createItem( plat, draft( WorkLevel.EPIC, "e" ), "alice", Actor.Kind.HUMAN );
        final Item t = create( "t" );
        final Item due = store.updateItem( t, new Item( t.id(), t.projectId(), t.key(), t.level(), null, null,
            t.kind(), t.title(), t.body(), WorkStatus.READY, 3, t.rank(), LocalDate.of( 2026, 12, 1 ), null,
            t.reporter(), null, null, null, t.version(), null, null, null ), "alice", Actor.Kind.HUMAN, null );
        assertEquals( List.of( epic.id() ), store.items( new ItemFilter( plat.id(), null, null, null, WorkLevel.EPIC,
            null, null, null, 0 ) ).stream().map( Item::id ).toList() );
        assertEquals( List.of( due.id() ), store.items( new ItemFilter( plat.id(), Set.of( WorkStatus.READY ), null,
            null, null, null, null, null, 0 ) ).stream().map( Item::id ).toList() );
        assertEquals( 1, store.items( new ItemFilter( plat.id(), null, null, null, null, null, null,
            LocalDate.of( 2027, 1, 1 ), 0 ) ).size() );
        assertEquals( 0, store.items( new ItemFilter( plat.id(), null, null, null, null, null, null,
            LocalDate.of( 2026, 11, 1 ), 0 ) ).size() );
        assertEquals( 1, store.items( new ItemFilter( plat.id(), null, null, null, null, null, null, null, 1 ) ).size() );
    }

    @Test
    void epicProductsDerivedFromDescendants() throws SQLException {
        final Product p1 = store.createProduct( new Product( 0, "web", "Web", null, "main", null, Autonomy.AUTONOMOUS,
            DeployGate.HUMAN, null ) );
        final Product p2 = store.createProduct( new Product( 0, "api", "API", null, "main", null, Autonomy.AUTONOMOUS,
            DeployGate.HUMAN, null ) );
        final Item epic = store.createItem( plat, draft( WorkLevel.EPIC, "e" ), "alice", Actor.Kind.HUMAN );
        final Item story = store.createItem( plat, withParentAndProduct( draft( WorkLevel.STORY, "s" ), epic.id(),
            p1.id() ), "alice", Actor.Kind.HUMAN );
        store.createItem( plat, withParentAndProduct( draft( "t1" ), story.id(), p2.id() ), "alice", Actor.Kind.HUMAN );
        store.createItem( plat, withParentAndProduct( draft( "t2" ), story.id(), p2.id() ), "alice", Actor.Kind.HUMAN );
        store.createItem( plat, withParentAndProduct( draft( "t3" ), story.id(), null ), "alice", Actor.Kind.HUMAN );
        assertEquals( Set.of( p1.id(), p2.id() ), store.productIdsUnder( epic.id() ) );
        assertTrue( store.productIdsUnder( story.id() ).contains( p2.id() ) );
        assertTrue( store.productIdsUnder( 987654L ).isEmpty() );
    }

    /** Gives the first {@code n} events (by id) the instant {@code at}; the rest keep theirs. */
    private void stamp( final int n, final java.time.Instant at ) throws SQLException {
        try ( java.sql.Connection c = PostgresTestDb.createDataSource().getConnection();
              java.sql.PreparedStatement ps = c.prepareStatement(
                  "UPDATE work_event SET at = ? WHERE id IN (SELECT id FROM work_event ORDER BY id LIMIT ?)" ) ) {
            ps.setTimestamp( 1, java.sql.Timestamp.from( at ) );
            ps.setInt( 2, n );
            ps.executeUpdate();
        }
    }

    private List< Long > walk( final int pageSize, final boolean compound ) throws SQLException {
        final List< Long > ids = new ArrayList<>();
        WorkEvent last = null;
        for ( int guard = 0; guard < 50; guard++ ) {
            final List< WorkEvent > page = last == null
                ? store.projectEvents( plat.id(), null, null, pageSize )
                : store.projectEvents( plat.id(), last.at(), compound ? last.id() : null, pageSize );
            if ( page.isEmpty() ) {
                break;
            }
            page.forEach( e -> ids.add( e.id() ) );
            last = page.get( page.size() - 1 );
        }
        return ids;
    }

    @Test
    void compoundCursorWalksEventsSharingOneTimestampExactlyOnce() throws SQLException {
        for ( int i = 0; i < 8; i++ ) {
            create( "t" + i );
        }
        final java.time.Instant newer = java.time.Instant.parse( "2026-11-10T10:00:00Z" );
        final java.time.Instant older = java.time.Instant.parse( "2026-11-09T10:00:00Z" );
        // events 1-6 share the older instant; the newest ones are 7-8, sharing the newer instant
        stamp( 6, older );
        try ( java.sql.Connection c = PostgresTestDb.createDataSource().getConnection();
              java.sql.PreparedStatement ps = c.prepareStatement( "UPDATE work_event SET at = ? WHERE at > ?" ) ) {
            ps.setTimestamp( 1, java.sql.Timestamp.from( newer ) );
            ps.setTimestamp( 2, java.sql.Timestamp.from( older ) );
            ps.executeUpdate();
        }
        final List< Long > all = new ArrayList<>( walk( 100, true ) );
        assertEquals( 8, all.size() );
        for ( final int pageSize : new int[] { 1, 2, 3, 4, 5, 7 } ) {
            final List< Long > ids = walk( pageSize, true );
            assertEquals( all, ids, "page size " + pageSize + " must return every event exactly once, in order" );
        }
        // the timestamp-only cursor is the defect: a boundary inside a shared-timestamp group drops the rest of it
        assertTrue( walk( 3, false ).size() < 8, "a timestamp-only cursor loses same-timestamp events" );
    }

    @Test
    void projectEventsPageNewestFirstAndCarryItemKey() throws SQLException {
        create( "a" );
        final Item b = create( "b" );
        store.updateItem( b, withPriority( b, 1 ), "alice", Actor.Kind.HUMAN, null );
        final List< WorkEvent > page = store.projectEvents( plat.id(), null, 2 );
        assertEquals( 2, page.size() );
        assertEquals( WorkEvent.FIELD, page.get( 0 ).type() );
        assertEquals( "PLAT-2", page.get( 0 ).itemKey() );
        assertEquals( 3, store.projectEvents( plat.id(), java.time.Instant.now().plusSeconds( 60 ), 10 ).size() );
        assertEquals( 0, store.projectEvents( plat.id(), java.time.Instant.EPOCH, 10 ).size() );
    }

    @Test
    void itemByIdAndMaxRank() throws SQLException {
        final Item a = create( "a" );
        final Item b = create( "b" );
        assertEquals( a.key(), store.itemById( a.id() ).orElseThrow().key() );
        assertTrue( store.itemById( -1 ).isEmpty() );
        assertEquals( b.rank(), store.maxRank( plat.id(), WorkStatus.DRAFT ) );
        assertEquals( 0.0, store.maxRank( plat.id(), WorkStatus.REVIEW ) );
    }
}
