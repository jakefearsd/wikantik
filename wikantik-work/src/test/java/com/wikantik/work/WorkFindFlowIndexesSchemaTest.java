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
import org.junit.jupiter.api.Test;

import javax.sql.DataSource;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;

/** Pins V094: the indexes of cross-project find and of the DORA deployment read. */
@RequiresPostgres
class WorkFindFlowIndexesSchemaTest {

    private static final List< String > INDEXES = List.of( "idx_work_deployment_started", "idx_work_deployment_live",
        "idx_work_item_find_updated" );

    private final DataSource ds = PostgresTestDb.createDataSource();

    private long count( final String sql ) throws SQLException {
        return WorkAgentFixtures.scalar( ds, sql );
    }

    @Test
    void theIndexesExist() throws SQLException {
        for ( final String index : INDEXES ) {
            assertEquals( 1, count( "SELECT count(*) FROM pg_indexes WHERE indexname = '" + index + "'" ), index );
        }
    }

    @Test
    void theDeploymentIndexesCoverOnlyProductionRows() throws SQLException {
        for ( final String index : List.of( "idx_work_deployment_started", "idx_work_deployment_live" ) ) {
            assertEquals( 1, count( "SELECT count(*) FROM pg_indexes WHERE indexname = '" + index
                + "' AND indexdef LIKE '%production%'" ), index );
        }
    }

    @Test
    void theFindIndexIsOnTheDefaultSortAndItsTieBreak() throws SQLException {
        assertEquals( 1, count( "SELECT count(*) FROM pg_indexes WHERE indexname = 'idx_work_item_find_updated' "
            + "AND indexdef LIKE '%(updated_at, id)%'" ) );
        assertEquals( 0, count( "SELECT count(*) FROM pg_indexes WHERE indexname IN ( 'idx_work_item_find_created', "
            + "'idx_work_item_find_priority', 'idx_work_item_find_due' )" ), "non-default sorts are not indexed" );
    }

    @Test
    void reapplyingTheMigrationChangesNothing() throws Exception {
        final String sql = Files.readString( Path.of( "..", "bin", "db", "migrations",
            "V094__work_find_and_dora_indexes.sql" ) ).replaceAll( "(?m)^--.*$", "" );
        final String indexes = "SELECT count(*) FROM pg_indexes WHERE tablename IN ( 'work_item', 'work_deployment' )";
        final long before = count( indexes );
        try ( Connection c = ds.getConnection(); Statement s = c.createStatement() ) {
            s.execute( sql );
        }
        assertEquals( before, count( indexes ) );
    }
}
