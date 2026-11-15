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
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** {@link JdbcWorkStore#itemCounts()}: one grouped count per status, level and kind. */
@RequiresPostgres
class JdbcWorkStoreItemCountsPostgresTest {

    private JdbcWorkStore store;
    private Project plat;

    @BeforeEach
    void setUp() throws SQLException {
        store = new JdbcWorkStore( PostgresTestDb.createDataSource() );
        PostgresTestDb.truncate( "work_link", "work_mention", "work_comment", "work_event",
            "work_item_revision", "work_item", "work_member", "work_product", "work_project" );
        plat = store.createProject( "PLAT", "Platform", "" );
    }

    @Test
    void emptyStoreHasNoCounts() throws SQLException {
        assertTrue( store.itemCounts().isEmpty() );
    }

    @Test
    void countsGroupByStatusLevelAndKind() throws SQLException {
        store.createItem( plat, JdbcWorkStoreItemsPostgresTest.draft( "one" ), "alice", Actor.Kind.HUMAN );
        store.createItem( plat, JdbcWorkStoreItemsPostgresTest.draft( "two" ), "alice", Actor.Kind.HUMAN );
        store.createItem( plat, JdbcWorkStoreItemsPostgresTest.draft( WorkLevel.EPIC, "big" ), "alice",
            Actor.Kind.HUMAN );

        final List< WorkStore.ItemCount > counts = store.itemCounts();

        assertEquals( 2, counts.size() );
        assertTrue( counts.contains( new WorkStore.ItemCount( WorkStatus.DRAFT, WorkLevel.TASK, WorkKind.SOFTWARE, 2 ) ) );
        assertTrue( counts.contains( new WorkStore.ItemCount( WorkStatus.DRAFT, WorkLevel.EPIC, WorkKind.SOFTWARE, 1 ) ) );
    }
}
