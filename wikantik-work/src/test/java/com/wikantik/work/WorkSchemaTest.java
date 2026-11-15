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
import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.List;
import java.util.TreeSet;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Pins the V074 work-management schema: the tables, their constraints and the policy-column defaults. */
@RequiresPostgres
class WorkSchemaTest {

    private static final List< String > TABLES = List.of(
        "work_project", "work_member", "work_product", "work_item", "work_item_revision",
        "work_event", "work_comment", "work_mention", "work_link" );

    private DataSource ds;

    @BeforeEach
    void setUp() {
        ds = PostgresTestDb.createDataSource();
        // children first; truncate is CASCADE-safe either way but the order documents the dependency
        PostgresTestDb.truncate( "work_link", "work_mention", "work_comment", "work_event",
            "work_item_revision", "work_item", "work_member", "work_product", "work_project" );
    }

    private void exec( final String sql ) throws SQLException {
        try ( Connection c = ds.getConnection(); Statement s = c.createStatement() ) {
            s.execute( sql );
        }
    }

    private long project() throws SQLException {
        try ( Connection c = ds.getConnection(); Statement s = c.createStatement();
              ResultSet rs = s.executeQuery(
                  "INSERT INTO work_project (key, name) VALUES ('WIKI', 'Wiki') RETURNING id" ) ) {
            rs.next();
            return rs.getLong( 1 );
        }
    }

    private void item( final long projectId, final int seq, final String status ) throws SQLException {
        exec( "INSERT INTO work_item (project_id, seq, level, kind, title, status, rank, reporter) VALUES ("
            + projectId + "," + seq + ",'task','software','t','" + status + "',1.0,'alice')" );
    }

    @Test
    void allNineTablesExist() throws SQLException {
        final TreeSet< String > found = new TreeSet<>();
        try ( Connection c = ds.getConnection(); Statement s = c.createStatement();
              ResultSet rs = s.executeQuery( "SELECT table_name FROM information_schema.tables "
                  + "WHERE table_schema = 'public' AND table_name LIKE 'work\\_%'" ) ) {
            while ( rs.next() ) {
                found.add( rs.getString( 1 ) );
            }
        }
        assertTrue( found.containsAll( TABLES ), "missing: " + TABLES.stream().filter( t -> !found.contains( t ) ).toList() );
    }

    @Test
    void statusCheckRejectsUnknown() throws SQLException {
        final long p = project();
        assertThrows( SQLException.class, () -> item( p, 1, "bogus" ) );
    }

    @Test
    void itemKeyIsUniquePerProject() throws SQLException {
        final long p = project();
        item( p, 1, "draft" );
        assertThrows( SQLException.class, () -> item( p, 1, "draft" ) );
    }

    @Test
    void policyColumnsDefault() throws SQLException {
        final long p = project();
        exec( "INSERT INTO work_product (key, name) VALUES ('wiki', 'Wiki')" );
        item( p, 1, "draft" );
        try ( Connection c = ds.getConnection(); Statement s = c.createStatement() ) {
            try ( ResultSet rs = s.executeQuery( "SELECT default_autonomy FROM work_project" ) ) {
                assertTrue( rs.next() );
                assertEquals( "delegated", rs.getString( 1 ) );
            }
            try ( ResultSet rs = s.executeQuery( "SELECT autonomy_ceiling, deploy_gate FROM work_product" ) ) {
                assertTrue( rs.next() );
                assertEquals( "delegated", rs.getString( 1 ) );
                assertEquals( "human", rs.getString( 2 ) );
            }
            try ( ResultSet rs = s.executeQuery( "SELECT autonomy IS NULL, done_means IS NULL, "
                + "allow_direct IS NULL FROM work_item" ) ) {
                assertTrue( rs.next() );
                assertTrue( rs.getBoolean( 1 ) );
                assertTrue( rs.getBoolean( 2 ) );
                assertTrue( rs.getBoolean( 3 ) );
                assertFalse( rs.next() );
            }
        }
    }
}
