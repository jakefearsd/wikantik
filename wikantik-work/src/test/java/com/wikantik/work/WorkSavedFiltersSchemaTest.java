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
import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.Arrays;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Pins V087: saved filters, private (cross-project) or shared and pinned to one project (work part 4). */
@RequiresPostgres
class WorkSavedFiltersSchemaTest {

    private DataSource ds;
    private long project;

    @BeforeEach
    void setUp() throws SQLException {
        ds = PostgresTestDb.createDataSource();
        PostgresTestDb.truncate( WorkAgentFixtures.TABLES );
        project = scalar( "INSERT INTO work_project (key, name) VALUES ('WIKI', 'Wiki') RETURNING id" );
    }

    private void exec( final String sql ) throws SQLException {
        WorkAgentFixtures.exec( ds, sql );
    }

    private long scalar( final String sql ) throws SQLException {
        return WorkAgentFixtures.scalar( ds, sql );
    }

    private static String v087() throws Exception {
        return Files.readString( Path.of( "..", "bin", "db", "migrations", "V087__work_saved_filters.sql" ) );
    }

    private static String insert( final String owner, final String name, final String project, final boolean shared ) {
        return "INSERT INTO work_saved_filter (owner, name, project_id, shared, filter) VALUES ('" + owner + "', '"
            + name + "', " + project + ", " + shared + ", '{\"q\":\"x\"}')";
    }

    @Test
    void aSharedFilterNeedsAProject() throws SQLException {
        assertEquals( "23514", assertThrows( SQLException.class, () -> exec( insert( "alice", "f", "NULL", true ) ) ).getSQLState() );
        exec( insert( "alice", "f", String.valueOf( project ), true ) );
        exec( insert( "alice", "private", "NULL", false ) );
        assertEquals( 2, scalar( "SELECT count(*) FROM work_saved_filter" ) );
    }

    @Test
    void aNameIsUniquePerOwner() throws SQLException {
        exec( insert( "alice", "f", "NULL", false ) );
        assertEquals( "23505", assertThrows( SQLException.class, () -> exec( insert( "alice", "f", "NULL", false ) ) ).getSQLState() );
    }

    @Test
    void twoOwnersMayUseOneName() throws SQLException {
        exec( insert( "alice", "f", "NULL", false ) );
        exec( insert( "bob", "f", "NULL", false ) );
        assertEquals( 2, scalar( "SELECT count(*) FROM work_saved_filter WHERE name = 'f'" ) );
    }

    @Test
    void theFilterIsJson() throws SQLException {
        exec( insert( "alice", "f", "NULL", false ) );
        assertEquals( 1, scalar( "SELECT count(*) FROM work_saved_filter WHERE filter->>'q' = 'x'" ) );
        assertEquals( "22P02", assertThrows( SQLException.class, () -> exec(
            "INSERT INTO work_saved_filter (owner, name, filter) VALUES ('alice', 'bad', 'not json')" ) ).getSQLState() );
    }

    @Test
    void theProjectMustExist() {
        assertEquals( "23503", assertThrows( SQLException.class, () -> exec( insert( "alice", "f", "999999", false ) ) ).getSQLState() );
    }

    /** The test container's role is a superuser, so the grant is pinned on the migration text. */
    @Test
    void theAppRoleMayReadWriteAndDelete() throws Exception {
        final List< String > grants = Arrays.stream( v087().replaceAll( "(?m)^--.*$", "" ).split( ";" ) )
            .filter( st -> st.contains( "GRANT" ) ).map( st -> st.replaceAll( "\\s+", " " ).trim() ).toList();
        assertEquals( List.of( "GRANT SELECT, INSERT, UPDATE, DELETE ON work_saved_filter TO :app_user" ), grants );
    }

    /** Columns, indexes, constraints and row count of work_saved_filter, as one comparable string. */
    private String schemaSnapshot() throws SQLException {
        final StringBuilder out = new StringBuilder();
        final String[] queries = {
            "SELECT column_name || ' ' || data_type || ' ' || is_nullable || ' ' || coalesce(column_default,'') "
                + "FROM information_schema.columns WHERE table_schema = current_schema() "
                + "AND table_name = 'work_saved_filter' ORDER BY 1",
            "SELECT indexdef FROM pg_indexes WHERE schemaname = current_schema() AND tablename = 'work_saved_filter' ORDER BY 1",
            "SELECT pg_get_constraintdef(oid) FROM pg_constraint WHERE conrelid = "
                + "'work_saved_filter'::regclass ORDER BY 1",
            "SELECT 'rows ' || count(*) || ' ' || coalesce(string_agg(id || ':' || filter::text, ',' ORDER BY id), '') "
                + "FROM work_saved_filter" };
        try ( Connection c = ds.getConnection(); Statement s = c.createStatement() ) {
            for ( final String q : queries ) {
                try ( ResultSet rs = s.executeQuery( q ) ) {
                    while ( rs.next() ) {
                        out.append( rs.getString( 1 ) ).append( '\n' );
                    }
                }
            }
        }
        return out.toString();
    }

    @Test
    void v087IsANoOpWhenReapplied() throws Exception {
        final String sql = v087().replace( ":app_user", "CURRENT_USER" );
        exec( sql );
        exec( insert( "alice", "f", String.valueOf( project ), true ) );
        final String before = schemaSnapshot();
        assertTrue( before.contains( "filter jsonb" ), "snapshot should list the columns" );
        assertTrue( before.contains( "rows 1 " ), "snapshot should count the row" );
        exec( sql );
        assertEquals( before, schemaSnapshot() );
    }
}
