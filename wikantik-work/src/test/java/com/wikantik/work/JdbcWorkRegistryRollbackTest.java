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

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Registering and updating an agent are all-or-nothing across the agent row and its queue rows. */
@RequiresPostgres
class JdbcWorkRegistryRollbackTest {

    private DataSource ds;
    private JdbcWorkStore store;

    @BeforeEach
    void setUp() throws SQLException {
        ds = PostgresTestDb.createDataSource();
        PostgresTestDb.truncate( WorkAgentFixtures.TABLES );
        store = new JdbcWorkStore( ds );
        store.createQueue( "implementer", "Implementer", "" );
    }

    private int newKey() throws SQLException {
        return ( int ) WorkAgentFixtures.apiKey( ds, "work" );
    }

    @Test
    void registerRollsBackWhenQueueRowFails() throws SQLException {
        final FaultInjectingDataSource faulty = new FaultInjectingDataSource( ds );
        faulty.failOn( 2, new RuntimeException( "boom: queue rows" ) ); // 1 insert agent, 2 insert queue rows
        assertThrows( RuntimeException.class,
            () -> new JdbcWorkStore( faulty ).registerAgent( "a", "", "", newKey(), List.of( "implementer" ) ) );
        assertEquals( 1, faulty.rollbacks() );
        assertTrue( store.agents().isEmpty() );
    }

    @Test
    void registerRollsBackWhenQueueIsUnknown() throws SQLException {
        assertThrows( SQLException.class,
            () -> store.registerAgent( "a", "", "", newKey(), List.of( "implementer", "ghost" ) ) );
        assertTrue( store.agents().isEmpty() );
    }

    @Test
    void updateRollsBackWhenQueueRowFails() throws SQLException {
        final Agent a = store.registerAgent( "before", "v", "r", newKey(), List.of( "implementer" ) );
        final FaultInjectingDataSource faulty = new FaultInjectingDataSource( ds );
        faulty.failOn( 3, new RuntimeException( "boom: queue rows" ) ); // 1 update, 2 delete, 3 insert
        assertThrows( RuntimeException.class,
            () -> new JdbcWorkStore( faulty ).updateAgent( a.id(), "after", "v2", "r2", List.of( "implementer" ) ) );
        assertEquals( 1, faulty.rollbacks() );
        assertEquals( a, store.agent( a.id() ).orElseThrow() );
    }

    @Test
    void updateRollsBackWhenQueueIsUnknown() throws SQLException {
        final Agent a = store.registerAgent( "before", "v", "r", newKey(), List.of( "implementer" ) );
        assertThrows( SQLException.class, () -> store.updateAgent( a.id(), "after", "v2", "r2", List.of( "ghost" ) ) );
        assertEquals( a, store.agent( a.id() ).orElseThrow() );
    }

    @Test
    void rebindRollsBackTheHistoryRowWhenTheUpdateFails() throws SQLException {
        final int first = newKey();
        final Agent a = store.registerAgent( "before", "v", "r", first, List.of() );
        final FaultInjectingDataSource faulty = new FaultInjectingDataSource( ds );
        faulty.failOn( 2, new RuntimeException( "boom: update" ) ); // 1 insert history, 2 update binding
        assertThrows( RuntimeException.class, () -> new JdbcWorkStore( faulty ).rebindAgentKey( a.id(), newKey() ) );
        assertEquals( 1, faulty.rollbacks() );
        assertFalse( store.wasEverAgentKey( first ) );
        assertEquals( first, store.agent( a.id() ).orElseThrow().apiKeyId() );
    }
}
