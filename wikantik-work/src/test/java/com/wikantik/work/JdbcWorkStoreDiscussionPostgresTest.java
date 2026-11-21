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
        PostgresTestDb.truncate( WorkAgentFixtures.TABLES );
        store = new JdbcWorkStore( ds );
        plat = store.createProject( "PLAT", "Platform", "" );
    }

    private Item create( final Project p, final String title ) throws SQLException {
        return store.createItem( p, JdbcWorkStoreItemsPostgresTest.draft( title ), Actor.human( "alice", java.util.Set.of(), false ) );
    }

    private Item move( final Item i, final WorkStatus s ) throws SQLException {
        final Item next = new Item( i.id(), i.projectId(), i.key(), i.level(), i.parentId(), i.productId(), i.kind(),
            i.title(), i.body(), s, i.priority(), i.rank(), i.dueOn(), i.assignee(), i.reporter(), i.autonomy(),
            i.doneMeans(), i.allowDirect(), i.version(), i.createdAt(), i.updatedAt(), i.closedAt(), null, null, null, null, null, null, null );
        return store.updateItem( i, next, Actor.human( "alice", java.util.Set.of(), false ), null );
    }

    private Item assign( final Item i, final String who ) throws SQLException {
        final Item next = new Item( i.id(), i.projectId(), i.key(), i.level(), i.parentId(), i.productId(), i.kind(),
            i.title(), i.body(), i.status(), i.priority(), i.rank(), i.dueOn(), who, i.reporter(), i.autonomy(),
            i.doneMeans(), i.allowDirect(), i.version(), i.createdAt(), i.updatedAt(), i.closedAt(), null, null, null, null, null, null, null );
        return store.updateItem( i, next, Actor.human( "alice", java.util.Set.of(), false ), null );
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
        final Comment c = store.addComment( item, Actor.human( "alice", java.util.Set.of(), false ), "hi @bob and @carol",
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
        final Comment c = store.addComment( item, Actor.human( "alice", java.util.Set.of(), false ), "secret", List.of() );
        store.removeComment( c.id(), Actor.human( "alice", java.util.Set.of(), false ) );
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
        final Comment c = store.addComment( item, Actor.human( "alice", java.util.Set.of(), false ), "one", List.of() );
        assertNull( c.editedAt() );
        final Comment edited = store.editComment( c.id(), "two", Actor.human( "alice", java.util.Set.of(), false ), List.of() );
        assertEquals( "two", edited.body() );
        assertNotNull( edited.editedAt() );
        assertEquals( "two", store.comment( c.id() ).orElseThrow().body() );
        assertTrue( store.itemEvents( item.id() ).stream()
            .anyMatch( ev -> WorkEvent.COMMENT_EDITED.equals( ev.type() )
                && String.valueOf( c.id() ).equals( ev.toValue() ) ) );
    }

    @Test
    void editReplacesTheMentions() throws SQLException {
        final Item item = create( plat, "x" );
        final Comment c = store.addComment( item, Actor.human( "alice", java.util.Set.of(), false ), "hi bob", List.of( "bob" ) );
        store.editComment( c.id(), "hi carol", Actor.human( "alice", java.util.Set.of(), false ), List.of( "carol" ) );
        assertTrue( store.mentioning( "bob", Set.of( plat.id() ) ).isEmpty(), "a name taken out stops waiting" );
        assertEquals( 1, store.mentioning( "carol", Set.of( plat.id() ) ).size(), "an added name waits" );
    }

    @Test
    void discussionEventsRecordTheRealActorKind() throws SQLException {
        final Item item = create( plat, "x" );
        final Actor bot = Actor.agent( WorkAgentFixtures.agent( ds, "bot" ), "bot" );
        final Comment c = store.addComment( item, bot, "one", List.of() );
        store.editComment( c.id(), "two", bot, List.of() );
        final Link l = store.addLink( item, "url", "https://example.com", bot );
        store.removeLink( l.id(), bot );
        store.removeComment( c.id(), bot );
        final List< WorkEvent > discussion = store.itemEvents( item.id() ).stream()
            .filter( ev -> !WorkEvent.CREATED.equals( ev.type() ) ).toList();
        assertEquals( 5, discussion.size() );
        discussion.forEach( ev -> assertEquals( Actor.Kind.AGENT, ev.actorKind(), ev.type() ) );
    }

    @Test
    void removingALinkTwiceWritesOneUnlinkedEvent() throws SQLException {
        final Item item = create( plat, "x" );
        final Link l = store.addLink( item, "url", "https://example.com", Actor.human( "alice", java.util.Set.of(), false ) );
        store.removeLink( l.id(), Actor.human( "alice", java.util.Set.of(), false ) );
        store.removeLink( l.id(), Actor.human( "bob", java.util.Set.of(), false ) );
        assertEquals( 1, store.itemEvents( item.id() ).stream()
            .filter( ev -> WorkEvent.UNLINKED.equals( ev.type() ) ).count() );
    }

    @Test
    void editingUnknownCommentIsNotFound() {
        assertThrows( WorkNotFoundException.class, () -> store.editComment( 999_999, "x", Actor.human( "alice", java.util.Set.of(), false ), List.of() ) );
        assertThrows( WorkNotFoundException.class, () -> store.removeComment( 999_999, Actor.human( "alice", java.util.Set.of(), false ) ) );
    }

    @Test
    void linkAndUnlinkWriteEvents() throws SQLException {
        final Item item = create( plat, "x" );
        final Link l = store.addLink( item, "url", "https://example.com", Actor.human( "alice", java.util.Set.of(), false ) );
        assertEquals( List.of( l ), store.links( item.id() ) );
        store.removeLink( l.id(), Actor.human( "alice", java.util.Set.of(), false ) );
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
        store.addLink( item, "blocked_by", blocker.key().toLowerCase(), Actor.human( "alice", java.util.Set.of(), false ) );
        assertTrue( store.isBlocked( item.id() ) );
    }

    @Test
    void blockedByDoneItemDoesNot() throws SQLException {
        final Item blocker = create( plat, "blocker" );
        final Item item = create( plat, "x" );
        store.addLink( item, "blocked_by", blocker.key(), Actor.human( "alice", java.util.Set.of(), false ) );
        store.addLink( item, "blocked_by", "PLAT-999", Actor.human( "alice", java.util.Set.of(), false ) );
        assertTrue( store.isBlocked( item.id() ) );
        move( blocker, WorkStatus.DONE );
        assertFalse( store.isBlocked( item.id() ), "done blocker and unknown key do not block" );
    }

    @Test
    void mentioningIsScopedToGivenProjects() throws SQLException {
        final Project ops = store.createProject( "OPS", "Ops", "" );
        final Item a = create( plat, "a" );
        final Item b = create( ops, "b" );
        store.addComment( a, Actor.human( "alice", java.util.Set.of(), false ), "@bob", List.of( "bob" ) );
        store.addComment( b, Actor.human( "alice", java.util.Set.of(), false ), "@bob", List.of( "bob" ) );
        assertEquals( List.of( a.id() ), store.mentioning( "bob", Set.of( plat.id() ) ).stream().map( Item::id ).toList() );
        assertEquals( 2, store.mentioning( "bob", Set.of( plat.id(), ops.id() ) ).size() );
        assertTrue( store.mentioning( "bob", Set.of() ).isEmpty() );
        assertTrue( store.mentioning( "carol", Set.of( plat.id() ) ).isEmpty() );
    }

    @Test
    void mentioningAndAssignedOpenSkipClosedItemsAndListEachItemOnce() throws SQLException {
        final Item open = assign( create( plat, "open" ), "bob" );
        final Item done = assign( create( plat, "done" ), "bob" );
        store.addComment( open, Actor.human( "alice", java.util.Set.of(), false ), "1", List.of( "bob" ) );
        store.addComment( open, Actor.human( "alice", java.util.Set.of(), false ), "2", List.of( "bob" ) );
        store.addComment( done, Actor.human( "alice", java.util.Set.of(), false ), "3", List.of( "bob" ) );
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
        assertThrows( RuntimeException.class, () -> faultyStore.addComment( item, Actor.human( "alice", java.util.Set.of(), false ), "hi",
            List.of( "bob" ) ) );
        assertEquals( 1, faulty.rollbacks() );
        assertEquals( 0, count( "SELECT COUNT(*) FROM work_comment" ) );
        assertEquals( 0, count( "SELECT COUNT(*) FROM work_mention" ) );
    }

    @Test
    void unlinkRollsBackWhenEventInsertFails() throws SQLException {
        final Item item = create( plat, "x" );
        final Link l = store.addLink( item, "url", "https://example.com", Actor.human( "alice", java.util.Set.of(), false ) );
        // statements: 1 delete link returning it, 2 project of the item, 3 insert event
        final FaultInjectingDataSource faulty = new FaultInjectingDataSource( ds );
        faulty.failOn( 3, new RuntimeException( "boom: event insert" ) );
        final JdbcWorkStore faultyStore = new JdbcWorkStore( faulty );
        assertThrows( RuntimeException.class, () -> faultyStore.removeLink( l.id(), Actor.human( "alice", java.util.Set.of(), false ) ) );
        assertEquals( 1, faulty.rollbacks() );
        assertEquals( 1, store.links( item.id() ).size() );
    }

    @Test
    void linkLookupById() throws SQLException {
        final Item item = create( plat, "x" );
        final Link l = store.addLink( item, "url", "https://example.com", Actor.human( "alice", java.util.Set.of(), false ) );
        assertEquals( l, store.link( l.id() ).orElseThrow() );
        assertTrue( store.link( -1 ).isEmpty() );
    }

    @Test
    void crossProjectBlockedByRowDoesNotBlock() throws SQLException {
        final Project ops = store.createProject( "OPS", "Ops", "" );
        final Item other = create( ops, "secret" );
        final Item item = create( plat, "x" );
        store.addLink( item, "blocked_by", other.key(), Actor.human( "alice", java.util.Set.of(), false ) );
        assertFalse( store.isBlocked( item.id() ), "an open item in another project must not make this one blocked" );
    }

    private Item child( final Item parent, final Long productId, final String title ) throws SQLException {
        return store.createItem( plat, new Item( 0, 0, null, WorkLevel.TASK, parent == null ? null : parent.id(),
            productId, WorkKind.SOFTWARE, title, "", WorkStatus.DRAFT, 3, 0, null, null, null, null, null, null, 0,
            null, null, null, null, null, null, null, null, null, null ), Actor.human( "alice", java.util.Set.of(), false ) );
    }

    @Test
    void itemExtrasCountsCommentsBlockedProductAndParentInOneBatch() throws SQLException {
        final Product web = store.createProduct( new Product( 0, "web", "Web", null, null, null,
            Autonomy.DELEGATED, DeployGate.HUMAN, null ) );
        final Item story = create( plat, "story" );
        final Item blocker = create( plat, "blocker" );
        final Item a = child( story, web.id(), "a" );
        final Item b = child( null, null, "b" );
        store.addComment( a, Actor.human( "alice", java.util.Set.of(), false ), "one", List.of() );
        final Comment gone = store.addComment( a, Actor.human( "alice", java.util.Set.of(), false ), "two", List.of() );
        store.removeComment( gone.id(), Actor.human( "alice", java.util.Set.of(), false ) );
        store.addLink( b, "blocked_by", blocker.key(), Actor.human( "alice", java.util.Set.of(), false ) );

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
        store.addLink( item, "blocked_by", blocker.key(), Actor.human( "alice", java.util.Set.of(), false ) );
        assertFalse( store.itemExtras( plat.id(), List.of( item.id() ) ).get( item.id() ).blocked(),
            "a blocker in another project does not block" );
        assertTrue( store.itemExtras( ops.id(), List.of( item.id() ) ).isEmpty(),
            "ids outside the project are not returned" );
        final Item local = create( plat, "local blocker" );
        store.addLink( item, "blocked_by", local.key(), Actor.human( "alice", java.util.Set.of(), false ) );
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
