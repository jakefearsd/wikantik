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
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.SQLException;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Pins V084: the item type and labels columns (work management part 1). */
@RequiresPostgres
class WorkLabelsSchemaTest {

    private DataSource ds;
    private long item;

    @BeforeEach
    void setUp() throws SQLException {
        ds = PostgresTestDb.createDataSource();
        PostgresTestDb.truncate( WorkAgentFixtures.TABLES );
        final long p = scalar( "INSERT INTO work_project (key, name) VALUES ('WIKI', 'Wiki') RETURNING id" );
        item = scalar( "INSERT INTO work_item (project_id, seq, level, kind, title, rank, reporter) VALUES ("
            + p + ",1,'task','software','t',1.0,'alice') RETURNING id" );
    }

    private void exec( final String sql ) throws SQLException {
        WorkAgentFixtures.exec( ds, sql );
    }

    private long scalar( final String sql ) throws SQLException {
        return WorkAgentFixtures.scalar( ds, sql );
    }

    private static String state( final SQLException e ) {
        return e.getSQLState();
    }

    private static String v084() throws Exception {
        return Files.readString( Path.of( "..", "bin", "db", "migrations",
            "V084__work_labels_and_type.sql" ) );
    }

    @Test
    void anItemInsertedWithoutEitherColumnIsAFeatureWithNoLabels() throws SQLException {
        assertEquals( 1, scalar( "SELECT count(*) FROM work_item WHERE id = " + item
            + " AND type = 'feature' AND cardinality(labels) = 0" ) );
    }

    @Test
    void anUnknownTypeIsRefused() {
        assertEquals( "23514", state( assertThrows( SQLException.class,
            () -> exec( "UPDATE work_item SET type = 'epic' WHERE id = " + item ) ) ) );
    }

    @Test
    void labelsAreNotNull() {
        assertEquals( "23502", state( assertThrows( SQLException.class,
            () -> exec( "UPDATE work_item SET labels = NULL WHERE id = " + item ) ) ) );
    }

    @Test
    void theLabelsIndexIsGin() throws SQLException {
        final String def = indexDef();
        assertTrue( def.contains( "USING gin (labels)" ), def );
    }

    private String indexDef() throws SQLException {
        try ( var c = ds.getConnection();
              var st = c.createStatement();
              var rs = st.executeQuery( "SELECT indexdef FROM pg_indexes WHERE indexname = 'idx_work_item_labels'" ) ) {
            return rs.next() ? rs.getString( 1 ) : "";
        }
    }

    @Test
    void v084IsANoOpWhenReapplied() throws Exception {
        final String sql = v084().replace( ":app_user", "CURRENT_USER" );
        exec( sql );
        exec( sql );
        exec( "INSERT INTO work_item (project_id, seq, level, kind, title, rank, reporter, type, labels) VALUES ("
            + scalar( "SELECT id FROM work_project WHERE key = 'WIKI'" )
            + ",2,'task','software','t2',2.0,'alice','bug','{ui}')" );
    }
}
