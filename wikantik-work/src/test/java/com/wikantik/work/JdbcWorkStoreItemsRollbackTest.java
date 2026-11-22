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

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Proves item writes are all-or-nothing: a failing statement leaves nothing behind. */
@RequiresPostgres
class JdbcWorkStoreItemsRollbackTest {

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

    private int nextSeq() throws SQLException {
        try ( var c = ds.getConnection(); var ps = c.prepareStatement( "SELECT next_seq FROM work_project WHERE id = ?" ) ) {
            ps.setLong( 1, plat.id() );
            try ( var rs = ps.executeQuery() ) {
                rs.next();
                return rs.getInt( 1 );
            }
        }
    }

    @Test
    void createRollsBackWhenEventInsertFails() throws SQLException {
        final int before = nextSeq();
        // statements: 1 bump next_seq, 2 max rank, 3 insert item, 4 insert revision, 5 insert event
        final FaultInjectingDataSource faulty = new FaultInjectingDataSource( ds );
        faulty.failOn( 5, new RuntimeException( "boom: event insert" ) );
        final JdbcWorkStore faultyStore = new JdbcWorkStore( faulty );
        assertThrows( RuntimeException.class, () -> faultyStore.createItem( plat,
            JdbcWorkStoreItemsPostgresTest.draft( "x" ), Actor.human( "alice", java.util.Set.of(), false ) ) );
        assertEquals( 1, faulty.rollbacks() );
        assertTrue( store.items( new ItemFilter( plat.id(), null, null, null, null, null, null, null, 0, null, null, null, null, null ) ).isEmpty() );
        assertEquals( before, nextSeq() );
    }

    private static Item draftFor( final String assignee, final String queue ) {
        return new Item( 0, 0, null, WorkLevel.TASK, null, null, WorkKind.SOFTWARE, "x", "body", WorkStatus.DRAFT, 3, 0,
            null, assignee, null, null, null, null, 0, null, null, null, queue, null, null, null, null, null, null, null, null );
    }

    private void createRollsBackWhenAssignedInsertFails( final Item draft ) throws SQLException {
        final int before = nextSeq();
        // statements: 1 bump next_seq, 2 max rank, 3 insert item, 4 insert revision, 5 insert created event,
        // 6 insert assigned event (only an item created with a person or a queue writes it)
        final FaultInjectingDataSource faulty = new FaultInjectingDataSource( ds );
        faulty.failOn( 6, new RuntimeException( "boom: assigned insert" ) );
        final JdbcWorkStore faultyStore = new JdbcWorkStore( faulty );
        assertThrows( RuntimeException.class, () -> faultyStore.createItem( plat, draft,
            Actor.human( "alice", java.util.Set.of(), false ) ) );
        assertEquals( 1, faulty.rollbacks() );
        assertTrue( store.items( new ItemFilter( plat.id(), null, null, null, null, null, null, null, 0, null, null, null, null, null ) ).isEmpty() );
        assertEquals( 0, WorkAgentFixtures.scalar( ds, "SELECT count(*) FROM work_event WHERE project_id = " + plat.id() ),
            "the created event rolled back with it" );
        assertEquals( before, nextSeq() );
    }

    @Test
    void createRollsBackWhenTheAssignedEventForAQueueFails() throws SQLException {
        WorkAgentFixtures.exec( ds, "INSERT INTO work_queue (key, name) VALUES ('implementer', 'Implementer') ON CONFLICT DO NOTHING" );
        createRollsBackWhenAssignedInsertFails( draftFor( null, "implementer" ) );
    }

    @Test
    void createRollsBackWhenTheAssignedEventForAPersonFails() throws SQLException {
        createRollsBackWhenAssignedInsertFails( draftFor( "bob", null ) );
    }

    @Test
    void updateRollsBackWhenRevisionInsertFails() throws SQLException {
        final Item i = store.createItem( plat, JdbcWorkStoreItemsPostgresTest.draft( "x" ), Actor.human( "alice", java.util.Set.of(), false ) );
        // statements: 1 update item, 2 insert revision
        final FaultInjectingDataSource faulty = new FaultInjectingDataSource( ds );
        faulty.failOn( 2, new RuntimeException( "boom: revision insert" ) );
        final JdbcWorkStore faultyStore = new JdbcWorkStore( faulty );
        final Item next = new Item( i.id(), i.projectId(), i.key(), i.level(), null, null, i.kind(), "changed",
            i.body(), i.status(), i.priority(), i.rank(), null, null, i.reporter(), null, null, null, i.version(),
            null, null, null, null, null, null, null, null, null, null, null, null );
        assertThrows( RuntimeException.class, () -> faultyStore.updateItem( i, next, Actor.human( "bob", java.util.Set.of(), false ), null ) );
        assertEquals( 1, faulty.rollbacks() );
        final Item after = store.item( "PLAT-1" ).orElseThrow();
        assertEquals( "x", after.title() );
        assertEquals( 1, after.version() );
        assertEquals( 1, store.revisions( i.id() ).size() );
        assertEquals( 1, store.itemEvents( i.id() ).size() );
    }

    @Test
    void aFailedLabelsEventRollsBackTypeAndLabels() throws SQLException {
        final Item i = store.createItem( plat, JdbcWorkStoreItemsPostgresTest.draft( "x" ),
            Actor.human( "alice", java.util.Set.of(), false ) );
        // statements: 1 lockHolder, 2 update item, 3 the type event, 4 the labels event
        final FaultInjectingDataSource faulty = new FaultInjectingDataSource( ds );
        faulty.failOn( 4, new RuntimeException( "boom: labels event" ) );
        final JdbcWorkStore faultyStore = new JdbcWorkStore( faulty );
        final Item next = JdbcWorkStoreItemsPostgresTest.typed( i, WorkType.BUG, java.util.List.of( "ui" ) );
        assertThrows( RuntimeException.class,
            () -> faultyStore.updateItem( i, next, Actor.human( "bob", java.util.Set.of(), false ), null ) );
        assertEquals( 1, faulty.rollbacks() );
        final Item after = store.item( "PLAT-1" ).orElseThrow();
        assertEquals( WorkType.FEATURE, after.type() );
        assertEquals( java.util.List.of(), after.labels() );
        assertTrue( store.itemEvents( i.id() ).stream().noneMatch( e -> "field".equals( e.type() ) ) );
    }
}
