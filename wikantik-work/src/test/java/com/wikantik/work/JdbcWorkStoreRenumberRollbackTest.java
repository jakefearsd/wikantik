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
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** A column is renumbered whole or not at all. */
@RequiresPostgres
class JdbcWorkStoreRenumberRollbackTest {

    private final Actor alice = Actor.human( "alice", Set.of(), false );
    private DataSource ds;
    private JdbcWorkStore store;
    private Item a;
    private Item b;
    private Item c;

    @BeforeEach
    void setUp() throws SQLException {
        ds = PostgresTestDb.createDataSource();
        PostgresTestDb.truncate( WorkAgentFixtures.TABLES );
        store = new JdbcWorkStore( ds );
        final Project plat = store.createProject( "PLAT", "Platform", "" );
        a = store.createItem( plat, JdbcWorkStoreItemsPostgresTest.draft( "a" ), alice );
        b = store.createItem( plat, JdbcWorkStoreItemsPostgresTest.draft( "b" ), alice );
        c = store.createItem( plat, JdbcWorkStoreItemsPostgresTest.draft( "c" ), alice );
        WorkAgentFixtures.exec( ds, "UPDATE work_item SET rank = 1024 WHERE id IN (" + a.id() + "," + b.id() + ")" );
    }

    private List< Double > ranks() throws SQLException {
        return List.of( store.itemById( a.id() ).orElseThrow().rank(), store.itemById( b.id() ).orElseThrow().rank(),
            store.itemById( c.id() ).orElseThrow().rank() );
    }

    @Test
    void aFailedRenumberLeavesEveryRankAsItWas() throws SQLException {
        final List< Double > before = ranks();
        // statements: 1 lock and read the column, 2 the batch of rank updates
        final FaultInjectingDataSource faulty = new FaultInjectingDataSource( ds );
        faulty.failOn( 2, new RuntimeException( "boom: rank batch" ) );
        assertThrows( RuntimeException.class,
            () -> new JdbcWorkStore( faulty ).renumberColumn( a.projectId(), WorkStatus.DRAFT, c.id(), b.id() ) );
        assertEquals( 1, faulty.rollbacks() );
        assertEquals( before, ranks() );
        assertTrue( store.renumberColumn( a.projectId(), WorkStatus.DRAFT, c.id(), b.id() ), "and it still works after" );
    }
}
