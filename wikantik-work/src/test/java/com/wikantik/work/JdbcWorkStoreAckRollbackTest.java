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
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** The acknowledgement flag is written with the close, and an acknowledgement with its event, or not at all. */
@RequiresPostgres
class JdbcWorkStoreAckRollbackTest {

    private final Actor alice = Actor.human( "alice", Set.of(), false );
    private DataSource ds;
    private JdbcWorkStore store;
    private Item item;

    @BeforeEach
    void setUp() throws SQLException {
        ds = PostgresTestDb.createDataSource();
        PostgresTestDb.truncate( WorkAgentFixtures.TABLES );
        store = new JdbcWorkStore( ds );
        final Project plat = store.createProject( "PLAT", "Platform", "" );
        item = store.createItem( plat, JdbcWorkStoreItemsPostgresTest.draft( "x" ), alice );
    }

    private static Item done( final Item i ) {
        return new Item( i.id(), i.projectId(), i.key(), i.level(), i.parentId(), i.productId(), i.kind(), i.title(),
            i.body(), WorkStatus.DONE, i.priority(), i.rank(), i.dueOn(), i.assignee(), i.reporter(), i.autonomy(),
            i.doneMeans(), i.allowDirect(), i.version(), i.createdAt(), i.updatedAt(), i.closedAt(), null, null,
            null, null, null );
    }

    @Test
    void aFlaggedCloseSetsTheFlagWithTheStatus() throws SQLException {
        store.writeItem( item, done( item ), alice, null, false, true );
        assertTrue( store.acks().state( item.id() ).waiting() );
        assertEquals( 1, store.acks().awaiting( Set.of( item.projectId() ) ).size() );
    }

    @Test
    void aFailedCloseLeavesTheItemOpenAndUnflagged() throws SQLException {
        // statements: 1 lock the row, 2 update the item, 3 insert the status event
        final FaultInjectingDataSource faulty = new FaultInjectingDataSource( ds );
        faulty.failOn( 3, new RuntimeException( "boom: status event" ) );
        assertThrows( RuntimeException.class,
            () -> new JdbcWorkStore( faulty ).writeItem( item, done( item ), alice, null, false, true ) );
        assertEquals( 1, faulty.rollbacks() );
        assertEquals( WorkStatus.DRAFT, store.itemById( item.id() ).orElseThrow().status() );
        assertFalse( store.acks().state( item.id() ).required() );
    }

    @Test
    void aFailedAcknowledgementLeavesItWaiting() throws SQLException {
        final Item closed = store.writeItem( item, done( item ), alice, null, false, true ).item();
        // statements: 1 update the item, 2 insert the acknowledged event
        final FaultInjectingDataSource faulty = new FaultInjectingDataSource( ds );
        faulty.failOn( 2, new RuntimeException( "boom: acknowledged event" ) );
        assertThrows( RuntimeException.class, () -> new JdbcWorkStore( faulty ).acks().acknowledge( closed, alice ) );
        assertEquals( 1, faulty.rollbacks() );
        assertTrue( store.acks().state( item.id() ).waiting() );
        assertNull( store.acks().state( item.id() ).acknowledgedBy() );
        assertTrue( store.acks().acknowledge( closed, alice ) );
        assertFalse( store.acks().acknowledge( closed, alice ), "already acknowledged" );
    }

    @Test
    void theStateOfAnUnknownItemIsNone() throws SQLException {
        assertEquals( Ack.NONE, store.acks().state( 999_999 ) );
        assertTrue( store.acks().awaiting( Set.of() ).isEmpty() );
    }
}
