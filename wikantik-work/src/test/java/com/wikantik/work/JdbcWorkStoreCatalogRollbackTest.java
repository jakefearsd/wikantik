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
import java.time.Instant;
import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Membership, settings, archive and comment-edit writes are all-or-nothing: the event failing undoes the change. */
@RequiresPostgres
class JdbcWorkStoreCatalogRollbackTest {

    private DataSource ds;
    private JdbcWorkStore store;
    private Project plat;

    @BeforeEach
    void setUp() throws SQLException {
        ds = PostgresTestDb.createDataSource();
        PostgresTestDb.truncate( "work_link", "work_mention", "work_comment", "work_event",
            "work_item_revision", "work_item", "work_member", "work_product_event", "work_product", "work_project" );
        store = new JdbcWorkStore( ds );
        plat = store.createProject( "PLAT", "Platform", "" );
    }

    /** A store whose {@code n}-th statement from now on throws. */
    private FaultInjectingDataSource failingAt( final int n ) {
        final FaultInjectingDataSource faulty = new FaultInjectingDataSource( ds );
        faulty.failOn( n, new RuntimeException( "boom: event insert" ) );
        return faulty;
    }

    private int events() throws SQLException {
        return store.projectEvents( plat.id(), null, null, null, null, true, 200 ).size();
    }

    @Test
    void putMemberRollsBackWhenTheEventFails() throws SQLException {
        // statements: 1 select role for update, 2 upsert, 3 insert event
        final FaultInjectingDataSource faulty = failingAt( 3 );
        assertThrows( RuntimeException.class, () -> new JdbcWorkStore( faulty )
            .putMember( plat.id(), "bob", "user", WorkRole.MEMBER, Actor.human( "lena", java.util.Set.of(), false ) ) );
        assertEquals( 1, faulty.rollbacks() );
        assertTrue( store.members( plat.id() ).isEmpty() );
        assertEquals( 0, events() );
    }

    @Test
    void removeMemberRollsBackWhenTheEventFails() throws SQLException {
        store.putMember( plat.id(), "bob", "user", WorkRole.MEMBER, Actor.human( "lena", java.util.Set.of(), false ) );
        // statements: 1 delete returning, 2 insert event
        final FaultInjectingDataSource faulty = failingAt( 2 );
        assertThrows( RuntimeException.class, () -> new JdbcWorkStore( faulty )
            .removeMember( plat.id(), "bob", "user", Actor.human( "lena", java.util.Set.of(), false ) ) );
        assertEquals( 1, faulty.rollbacks() );
        assertEquals( 1, store.members( plat.id() ).size(), "the grant survives" );
        assertEquals( 1, events() );
    }

    @Test
    void updateProjectRollsBackWhenTheEventFails() throws SQLException {
        // statements: 1 select for update, 2 update, 3 insert the first event
        final FaultInjectingDataSource faulty = failingAt( 3 );
        assertThrows( RuntimeException.class, () -> new JdbcWorkStore( faulty )
            .updateProject( plat.id(), "Renamed", "", Autonomy.DELEGATED, Actor.human( "lena", java.util.Set.of(), false ) ) );
        assertEquals( 1, faulty.rollbacks() );
        assertEquals( "Platform", store.project( "PLAT" ).orElseThrow().name() );
        assertEquals( 0, events() );
    }

    @Test
    void archiveRollsBackWhenTheEventFails() throws SQLException {
        // statements: 1 update, 2 insert event
        final FaultInjectingDataSource faulty = failingAt( 2 );
        assertThrows( RuntimeException.class, () -> new JdbcWorkStore( faulty )
            .archiveProject( plat.id(), Instant.now(), Actor.human( "root", java.util.Set.of(), false ) ) );
        assertEquals( 1, faulty.rollbacks() );
        assertNull( store.project( "PLAT" ).orElseThrow().archivedAt() );
        assertEquals( 0, events() );
    }

    @Test
    void editCommentRollsBackItsMentionsWhenTheEventFails() throws SQLException {
        store.putMember( plat.id(), "bob", "user", WorkRole.MEMBER, Actor.human( "lena", java.util.Set.of(), false ) );
        store.putMember( plat.id(), "carol", "user", WorkRole.MEMBER, Actor.human( "lena", java.util.Set.of(), false ) );
        final Item item = store.createItem( plat, JdbcWorkStoreItemsPostgresTest.draft( "x" ), Actor.human( "lena", java.util.Set.of(), false ) );
        final Comment c = store.addComment( item, Actor.human( "lena", java.util.Set.of(), false ), "hi bob", List.of( "bob" ) );
        // statements: 1 update comment, 2 delete mentions, 3 insert mention, 4 project of item, 5 insert event
        final FaultInjectingDataSource faulty = failingAt( 5 );
        assertThrows( RuntimeException.class, () -> new JdbcWorkStore( faulty )
            .editComment( c.id(), "hi carol", Actor.human( "lena", java.util.Set.of(), false ), List.of( "carol" ) ) );
        assertEquals( 1, faulty.rollbacks() );
        assertEquals( "hi bob", store.comment( c.id() ).orElseThrow().body() );
        assertEquals( 1, store.mentioning( "bob", Set.of( plat.id() ) ).size() );
        assertTrue( store.mentioning( "carol", Set.of( plat.id() ) ).isEmpty() );
    }

    @Test
    void aFailedProductUpdateRecordsNoEventAndChangesNothing() throws SQLException {
        final Product made = store.createProduct( new Product( 0, "wiki", "Wikantik", null, "main", null,
            Autonomy.SUPERVISED, DeployGate.HUMAN, null ) );
        final Product renamed = new Product( made.id(), "wiki", "Renamed", null, "main", null,
            Autonomy.SUPERVISED, DeployGate.HUMAN, null );
        // statements: 1 update the row, 2 insert the event
        final FaultInjectingDataSource faulty = failingAt( 2 );
        assertThrows( RuntimeException.class, () -> new JdbcWorkStore( faulty )
            .updateProduct( made, renamed, Actor.human( "root", java.util.Set.of(), true ) ) );
        assertEquals( 1, faulty.rollbacks() );
        assertEquals( "Wikantik", store.product( "wiki" ).orElseThrow().name() );
        assertTrue( store.productEvents( made.id(), 10 ).isEmpty() );
    }

    @Test
    void aFailedProductArchiveRecordsNoEventAndChangesNothing() throws SQLException {
        final Product made = store.createProduct( new Product( 0, "wiki", "Wikantik", null, "main", null,
            Autonomy.SUPERVISED, DeployGate.HUMAN, null ) );
        final FaultInjectingDataSource faulty = failingAt( 2 );
        assertThrows( RuntimeException.class, () -> new JdbcWorkStore( faulty )
            .archiveProduct( made, Instant.now(), Actor.human( "root", java.util.Set.of(), true ) ) );
        assertEquals( 1, faulty.rollbacks() );
        assertNull( store.product( "wiki" ).orElseThrow().archivedAt() );
        assertTrue( store.productEvents( made.id(), 10 ).isEmpty() );
    }
}
