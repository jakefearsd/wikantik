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

import com.wikantik.jdbc.testing.FaultInjectingDataSource;
import com.wikantik.jdbc.testing.PostgresTestDb;
import com.wikantik.jdbc.testing.RequiresPostgres;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import javax.sql.DataSource;
import java.sql.SQLException;
import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Comments, mentions, links and the waiting-on-me queries of {@link JdbcWorkStore}, against real PostgreSQL. */
@RequiresPostgres
class JdbcWorkStoreDiscussionPostgresTest {

    private DataSource ds;
    private JdbcWorkStore store;
    private Project plat;

    @BeforeEach
    void setUp() throws SQLException {
        ds = PostgresTestDb.createDataSource();
        PostgresTestDb.truncate( "work_link", "work_mention", "work_comment", "work_event",
            "work_item_revision", "work_item", "work_member", "work_product", "work_project" );
        store = new JdbcWorkStore( ds );
        plat = store.createProject( "PLAT", "Platform", "" );
    }

    private Item create( final Project p, final String title ) throws SQLException {
        return store.createItem( p, JdbcWorkStoreItemsPostgresTest.draft( title ), "alice", Actor.Kind.HUMAN );
    }

    private Item move( final Item i, final WorkStatus s ) throws SQLException {
        final Item next = new Item( i.id(), i.projectId(), i.key(), i.level(), i.parentId(), i.productId(), i.kind(),
            i.title(), i.body(), s, i.priority(), i.rank(), i.dueOn(), i.assignee(), i.reporter(), i.autonomy(),
            i.doneMeans(), i.allowDirect(), i.version(), i.createdAt(), i.updatedAt(), i.closedAt() );
        return store.updateItem( i, next, "alice", Actor.Kind.HUMAN, null );
    }

    private Item assign( final Item i, final String who ) throws SQLException {
        final Item next = new Item( i.id(), i.projectId(), i.key(), i.level(), i.parentId(), i.productId(), i.kind(),
            i.title(), i.body(), i.status(), i.priority(), i.rank(), i.dueOn(), who, i.reporter(), i.autonomy(),
            i.doneMeans(), i.allowDirect(), i.version(), i.createdAt(), i.updatedAt(), i.closedAt() );
        return store.updateItem( i, next, "alice", Actor.Kind.HUMAN, null );
    }

    private int count( final String sql ) throws SQLException {
        try ( var c = ds.getConnection(); var ps = c.prepareStatement( sql ); var rs = ps.executeQuery() ) {
            rs.next();
            return rs.getInt( 1 );
        }
    }

    @Test
    void commentWritesMentionsAndEvent() throws SQLException {
        final Item item = create( plat, "x" );
        final Comment c = store.addComment( item, "alice", Actor.Kind.HUMAN, "hi @bob and @carol",
            List.of( "bob", "carol" ) );
        assertNotNull( c.createdAt() );
        assertEquals( "hi @bob and @carol", store.comment( c.id() ).orElseThrow().body() );
        assertEquals( List.of( c.id() ), store.comments( item.id() ).stream().map( Comment::id ).toList() );
        assertEquals( 2, count( "SELECT COUNT(*) FROM work_mention WHERE comment_id = " + c.id() ) );
        final WorkEvent e = store.itemEvents( item.id() ).stream()
            .filter( ev -> WorkEvent.COMMENTED.equals( ev.type() ) ).findFirst().orElseThrow();
        assertEquals( String.valueOf( c.id() ), e.toValue() );
        assertEquals( "alice", e.actor() );
        assertNull( e.agentId() );
        assertEquals( item.key(), e.itemKey() );
    }

    @Test
    void removedCommentKeepsRowAndHidesBody() throws SQLException {
        final Item item = create( plat, "x" );
        final Comment c = store.addComment( item, "alice", Actor.Kind.HUMAN, "secret", List.of() );
        store.removeComment( c.id(), "alice" );
        final Comment after = store.comment( c.id() ).orElseThrow();
        assertEquals( "", after.body() );
        assertNotNull( after.removedAt() );
        assertEquals( "", store.comments( item.id() ).get( 0 ).body() );
        assertEquals( 1, count( "SELECT COUNT(*) FROM work_comment WHERE body = 'secret' AND removed_at IS NOT NULL" ) );
        assertTrue( store.itemEvents( item.id() ).stream()
            .anyMatch( ev -> WorkEvent.COMMENT_REMOVED.equals( ev.type() )
                && String.valueOf( c.id() ).equals( ev.toValue() ) ) );
    }

    @Test
    void editSetsEditedAt() throws SQLException {
        final Item item = create( plat, "x" );
        final Comment c = store.addComment( item, "alice", Actor.Kind.HUMAN, "one", List.of() );
        assertNull( c.editedAt() );
        final Comment edited = store.editComment( c.id(), "two", "alice" );
        assertEquals( "two", edited.body() );
        assertNotNull( edited.editedAt() );
        assertEquals( "two", store.comment( c.id() ).orElseThrow().body() );
        assertTrue( store.itemEvents( item.id() ).stream()
            .anyMatch( ev -> WorkEvent.COMMENT_EDITED.equals( ev.type() )
                && String.valueOf( c.id() ).equals( ev.toValue() ) ) );
    }

    @Test
    void editingUnknownCommentIsNotFound() {
        assertThrows( WorkNotFoundException.class, () -> store.editComment( 999_999, "x", "alice" ) );
        assertThrows( WorkNotFoundException.class, () -> store.removeComment( 999_999, "alice" ) );
    }

    @Test
    void linkAndUnlinkWriteEvents() throws SQLException {
        final Item item = create( plat, "x" );
        final Link l = store.addLink( item, "url", "https://example.com", "alice" );
        assertEquals( List.of( l ), store.links( item.id() ) );
        store.removeLink( l.id(), "alice" );
        assertTrue( store.links( item.id() ).isEmpty() );
        final List< WorkEvent > events = store.itemEvents( item.id() );
        assertTrue( events.stream().anyMatch( ev -> WorkEvent.LINKED.equals( ev.type() )
            && "url:https://example.com".equals( ev.toValue() ) ) );
        assertTrue( events.stream().anyMatch( ev -> WorkEvent.UNLINKED.equals( ev.type() )
            && "url:https://example.com".equals( ev.fromValue() ) ) );
    }

    @Test
    void blockedByOpenItemBlocks() throws SQLException {
        final Item blocker = create( plat, "blocker" );
        final Item item = create( plat, "x" );
        assertFalse( store.isBlocked( item.id() ) );
        store.addLink( item, "blocked_by", blocker.key().toLowerCase(), "alice" );
        assertTrue( store.isBlocked( item.id() ) );
    }

    @Test
    void blockedByDoneItemDoesNot() throws SQLException {
        final Item blocker = create( plat, "blocker" );
        final Item item = create( plat, "x" );
        store.addLink( item, "blocked_by", blocker.key(), "alice" );
        store.addLink( item, "blocked_by", "PLAT-999", "alice" );
        assertTrue( store.isBlocked( item.id() ) );
        move( blocker, WorkStatus.DONE );
        assertFalse( store.isBlocked( item.id() ), "done blocker and unknown key do not block" );
    }

    @Test
    void mentioningIsScopedToGivenProjects() throws SQLException {
        final Project ops = store.createProject( "OPS", "Ops", "" );
        final Item a = create( plat, "a" );
        final Item b = create( ops, "b" );
        store.addComment( a, "alice", Actor.Kind.HUMAN, "@bob", List.of( "bob" ) );
        store.addComment( b, "alice", Actor.Kind.HUMAN, "@bob", List.of( "bob" ) );
        assertEquals( List.of( a.id() ), store.mentioning( "bob", Set.of( plat.id() ) ).stream().map( Item::id ).toList() );
        assertEquals( 2, store.mentioning( "bob", Set.of( plat.id(), ops.id() ) ).size() );
        assertTrue( store.mentioning( "bob", Set.of() ).isEmpty() );
        assertTrue( store.mentioning( "carol", Set.of( plat.id() ) ).isEmpty() );
    }

    @Test
    void mentioningAndAssignedOpenSkipClosedItemsAndListEachItemOnce() throws SQLException {
        final Item open = assign( create( plat, "open" ), "bob" );
        final Item done = assign( create( plat, "done" ), "bob" );
        store.addComment( open, "alice", Actor.Kind.HUMAN, "1", List.of( "bob" ) );
        store.addComment( open, "alice", Actor.Kind.HUMAN, "2", List.of( "bob" ) );
        store.addComment( done, "alice", Actor.Kind.HUMAN, "3", List.of( "bob" ) );
        move( store.item( done.key() ).orElseThrow(), WorkStatus.DONE );
        final Set< Long > scope = Set.of( plat.id() );
        assertEquals( List.of( open.id() ), store.mentioning( "bob", scope ).stream().map( Item::id ).toList() );
        assertEquals( List.of( open.id() ), store.assignedOpen( "bob", scope ).stream().map( Item::id ).toList() );
        assertTrue( store.assignedOpen( "bob", Set.of() ).isEmpty() );
    }

    @Test
    void commentRollsBackWhenEventInsertFails() throws SQLException {
        final Item item = create( plat, "x" );
        // statements: 1 insert comment, 2 insert mention, 3 insert event
        final FaultInjectingDataSource faulty = new FaultInjectingDataSource( ds );
        faulty.failOn( 3, new RuntimeException( "boom: event insert" ) );
        final JdbcWorkStore faultyStore = new JdbcWorkStore( faulty );
        assertThrows( RuntimeException.class, () -> faultyStore.addComment( item, "alice", Actor.Kind.HUMAN, "hi",
            List.of( "bob" ) ) );
        assertEquals( 1, faulty.rollbacks() );
        assertEquals( 0, count( "SELECT COUNT(*) FROM work_comment" ) );
        assertEquals( 0, count( "SELECT COUNT(*) FROM work_mention" ) );
    }

    @Test
    void unlinkRollsBackWhenEventInsertFails() throws SQLException {
        final Item item = create( plat, "x" );
        final Link l = store.addLink( item, "url", "https://example.com", "alice" );
        // statements: 1 select link+item, 2 delete link, 3 insert event
        final FaultInjectingDataSource faulty = new FaultInjectingDataSource( ds );
        faulty.failOn( 3, new RuntimeException( "boom: event insert" ) );
        final JdbcWorkStore faultyStore = new JdbcWorkStore( faulty );
        assertThrows( RuntimeException.class, () -> faultyStore.removeLink( l.id(), "alice" ) );
        assertEquals( 1, faulty.rollbacks() );
        assertEquals( 1, store.links( item.id() ).size() );
    }

    @Test
    void linkLookupById() throws SQLException {
        final Item item = create( plat, "x" );
        final Link l = store.addLink( item, "url", "https://example.com", "alice" );
        assertEquals( l, store.link( l.id() ).orElseThrow() );
        assertTrue( store.link( -1 ).isEmpty() );
    }

    @Test
    void crossProjectBlockedByRowDoesNotBlock() throws SQLException {
        final Project ops = store.createProject( "OPS", "Ops", "" );
        final Item other = create( ops, "secret" );
        final Item item = create( plat, "x" );
        store.addLink( item, "blocked_by", other.key(), "alice" );
        assertFalse( store.isBlocked( item.id() ), "an open item in another project must not make this one blocked" );
    }

    private Item child( final Item parent, final Long productId, final String title ) throws SQLException {
        return store.createItem( plat, new Item( 0, 0, null, WorkLevel.TASK, parent == null ? null : parent.id(),
            productId, WorkKind.SOFTWARE, title, "", WorkStatus.DRAFT, 3, 0, null, null, null, null, null, null, 0,
            null, null, null ), "alice", Actor.Kind.HUMAN );
    }

    @Test
    void itemExtrasCountsCommentsBlockedProductAndParentInOneBatch() throws SQLException {
        final Product web = store.createProduct( new Product( 0, "web", "Web", null, null, null,
            Autonomy.DELEGATED, DeployGate.HUMAN, null ) );
        final Item story = create( plat, "story" );
        final Item blocker = create( plat, "blocker" );
        final Item a = child( story, web.id(), "a" );
        final Item b = child( null, null, "b" );
        store.addComment( a, "alice", Actor.Kind.HUMAN, "one", List.of() );
        final Comment gone = store.addComment( a, "alice", Actor.Kind.HUMAN, "two", List.of() );
        store.removeComment( gone.id(), "alice" );
        store.addLink( b, "blocked_by", blocker.key(), "alice" );

        final var extras = store.itemExtras( plat.id(), List.of( a.id(), b.id() ) );

        assertEquals( 2, extras.size() );
        assertEquals( new WorkStore.ItemExtras( 1, false, "web", story.key() ), extras.get( a.id() ) );
        assertEquals( new WorkStore.ItemExtras( 0, true, null, null ), extras.get( b.id() ) );
    }

    @Test
    void itemExtrasBlockedIgnoresDoneBlockersAndOtherProjects() throws SQLException {
        final Project ops = store.createProject( "OPS", "Ops", "" );
        final Item blocker = create( ops, "ops blocker" );
        final Item item = create( plat, "x" );
        store.addLink( item, "blocked_by", blocker.key(), "alice" );
        assertFalse( store.itemExtras( plat.id(), List.of( item.id() ) ).get( item.id() ).blocked(),
            "a blocker in another project does not block" );
        assertTrue( store.itemExtras( ops.id(), List.of( item.id() ) ).isEmpty(),
            "ids outside the project are not returned" );
        final Item local = create( plat, "local blocker" );
        store.addLink( item, "blocked_by", local.key(), "alice" );
        assertTrue( store.itemExtras( plat.id(), List.of( item.id() ) ).get( item.id() ).blocked() );
        move( local, WorkStatus.DONE );
        assertFalse( store.itemExtras( plat.id(), List.of( item.id() ) ).get( item.id() ).blocked() );
    }

    @Test
    void itemExtrasOfNothingIsEmpty() throws SQLException {
        assertTrue( store.itemExtras( plat.id(), List.of() ).isEmpty() );
        assertTrue( store.itemExtras( plat.id(), null ).isEmpty() );
    }
}
