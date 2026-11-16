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

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** {@link WorkStore#policyChain} against real rows: the chain a level is resolved from. */
@RequiresPostgres
class WorkAutonomyPostgresTest {

    private DataSource ds;
    private JdbcWorkStore store;
    private long project;
    private long product;
    private int seq;

    @BeforeEach
    void setUp() throws SQLException {
        ds = PostgresTestDb.createDataSource();
        PostgresTestDb.truncate( WorkAgentFixtures.TABLES );
        store = new JdbcWorkStore( ds );
        project = WorkAgentFixtures.scalar( ds,
            "INSERT INTO work_project (key, name, default_autonomy) VALUES ('PLAT', 'Platform', 'supervised') RETURNING id" );
        product = WorkAgentFixtures.scalar( ds,
            "INSERT INTO work_product (key, name, autonomy_ceiling) VALUES ('wikantik', 'Wikantik', 'delegated') RETURNING id" );
    }

    private long item( final String level, final Long parent, final Long productId, final String autonomy )
            throws SQLException {
        return WorkAgentFixtures.scalar( ds, "INSERT INTO work_item (project_id, seq, level, kind, title, rank, "
            + "reporter, parent_id, product_id, autonomy) VALUES (" + project + "," + ( ++seq ) + ",'" + level
            + "','software','t',1.0,'alice'," + parent + "," + productId + ","
            + ( autonomy == null ? "NULL" : "'" + autonomy + "'" ) + ") RETURNING id" );
    }

    @Test
    void policyChainReturnsTaskStoryEpicProjectAndProduct() throws SQLException {
        final long epic = item( "epic", null, null, "autonomous" );
        final long story = item( "story", epic, product, null );
        final long task = item( "task", story, product, null );
        final PolicyChain chain = store.policyChain( task );
        assertEquals( task, chain.item().id() );
        assertEquals( List.of( story, epic ), chain.ancestors().stream().map( Item::id ).toList() );
        assertEquals( "PLAT", chain.project().key() );
        assertEquals( "wikantik", chain.productOrNull().key() );
        assertEquals( Autonomy.DELEGATED, WorkAutonomy.resolve( chain ).level(), "the epic's autonomous, capped" );
    }

    @Test
    void anItemWithNoParentOrProductHasAnEmptyChain() throws SQLException {
        final PolicyChain chain = store.policyChain( item( "task", null, null, null ) );
        assertTrue( chain.ancestors().isEmpty() );
        assertNull( chain.productOrNull() );
        assertEquals( Autonomy.SUPERVISED, WorkAutonomy.resolve( chain ).level(), "the project's default" );
    }

    @Test
    void reparentingChangesTheResolvedLevel() throws SQLException {
        final long strict = item( "epic", null, null, "supervised" );
        final long loose = item( "epic", null, null, "delegated" );
        final long story = item( "story", strict, product, null );
        assertEquals( Autonomy.SUPERVISED, WorkAutonomy.resolve( store.policyChain( story ) ).level() );
        WorkAgentFixtures.exec( ds, "UPDATE work_item SET parent_id = " + loose + " WHERE id = " + story );
        assertEquals( Autonomy.DELEGATED, WorkAutonomy.resolve( store.policyChain( story ) ).level() );
        WorkAgentFixtures.exec( ds, "UPDATE work_product SET autonomy_ceiling = 'supervised' WHERE id = " + product );
        assertEquals( Autonomy.SUPERVISED, WorkAutonomy.resolve( store.policyChain( story ) ).level(),
            "a lowered ceiling applies on the next read" );
    }
}
