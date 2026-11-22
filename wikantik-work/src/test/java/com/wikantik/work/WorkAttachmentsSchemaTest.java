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
import java.util.Arrays;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

/** Pins the attachments migration: metadata, bytes, and what the app role may delete (work management part 7). */
@RequiresPostgres
class WorkAttachmentsSchemaTest {

    private static final String SHA = "0".repeat( 64 );

    private DataSource ds;
    private long item;

    @BeforeEach
    void setUp() throws SQLException {
        ds = PostgresTestDb.createDataSource();
        PostgresTestDb.truncate( WorkAgentFixtures.TABLES );
        final long p = scalar( "INSERT INTO work_project (key, name) VALUES ('PLAT', 'Platform') RETURNING id" );
        item = scalar( "INSERT INTO work_item (project_id, seq, level, kind, title, rank, reporter) VALUES ("
            + p + ",1,'task','software','t',1.0,'alice') RETURNING id" );
    }

    private void exec( final String sql ) throws SQLException {
        WorkAgentFixtures.exec( ds, sql );
    }

    private long scalar( final String sql ) throws SQLException {
        return WorkAgentFixtures.scalar( ds, sql );
    }

    private long attachment() throws SQLException {
        return scalar( "INSERT INTO work_attachment (item_id, filename, content_type, size_bytes, sha256, uploaded_by) VALUES ("
            + item + ", 'a.png', 'image/png', 3, '" + SHA + "', 'alice') RETURNING id" );
    }

    private static String migration() throws Exception {
        try ( var files = Files.list( Path.of( "..", "bin", "db", "migrations" ) ) ) {
            return Files.readString( files.filter( f -> f.getFileName().toString().endsWith( "__work_attachments.sql" ) )
                .findFirst().orElseThrow() );
        }
    }

    @Test
    void anAttachmentNeedsARealItem() {
        assertEquals( "23503", assertThrows( SQLException.class, () -> exec(
            "INSERT INTO work_attachment (item_id, filename, content_type, size_bytes, sha256, uploaded_by) VALUES "
                + "(999999, 'a.png', 'image/png', 3, '" + SHA + "', 'alice')" ) ).getSQLState() );
    }

    @Test
    void aCommentThatDoesNotExistIsRefused() {
        assertEquals( "23503", assertThrows( SQLException.class, () -> exec(
            "INSERT INTO work_attachment (item_id, comment_id, filename, content_type, size_bytes, sha256, uploaded_by) "
                + "VALUES (" + item + ", 999999, 'a.png', 'image/png', 3, '" + SHA + "', 'alice')" ) ).getSQLState() );
    }

    @Test
    void anAttachmentHasOneBlob() throws SQLException {
        final long a = attachment();
        exec( "INSERT INTO work_attachment_blob (attachment_id, content) VALUES (" + a + ", '\\x010203')" );
        assertEquals( "23505", assertThrows( SQLException.class, () -> exec(
            "INSERT INTO work_attachment_blob (attachment_id, content) VALUES (" + a + ", '\\x04')" ) ).getSQLState() );
    }

    @Test
    void aBlobNeedsItsAttachment() {
        assertEquals( "23503", assertThrows( SQLException.class, () -> exec(
            "INSERT INTO work_attachment_blob (attachment_id, content) VALUES (999999, '\\x01')" ) ).getSQLState() );
    }

    /** The test container's role is a superuser, so the grants are pinned on the migration text. */
    @Test
    void theAppRoleMayDeleteOnlyBlobs() throws Exception {
        final List< String > grants = Arrays.stream( migration().replaceAll( "(?m)^--.*$", "" ).split( ";" ) )
            .filter( st -> st.contains( "GRANT" ) ).map( st -> st.replaceAll( "\\s+", " " ).trim() ).toList();
        assertEquals( List.of( "GRANT SELECT, INSERT, UPDATE ON work_attachment TO :app_user",
            "GRANT SELECT, INSERT, DELETE ON work_attachment_blob TO :app_user" ), grants );
    }

    private void insertWith( final String column, final String value ) throws SQLException {
        final String[] cols = { "filename", "content_type", "size_bytes", "sha256", "uploaded_by" };
        final String[] vals = { "'a.png'", "'image/png'", "3", "'" + SHA + "'", "'alice'" };
        for ( int i = 0; i < cols.length; i++ ) {
            if ( cols[ i ].equals( column ) ) {
                vals[ i ] = value;
            }
        }
        exec( "INSERT INTO work_attachment (item_id, " + String.join( ", ", cols ) + ") VALUES (" + item + ", "
            + String.join( ", ", vals ) + ")" );
    }

    @Test
    void theColumnsRefuseWhatTheServiceNeverWrites() {
        final String[][] bad = { { "size_bytes", "-1" }, { "filename", "''" }, { "filename", "'" + "x".repeat( 201 ) + "'" },
            { "content_type", "''" }, { "content_type", "'" + "x".repeat( 128 ) + "'" }, { "sha256", "'00'" },
            { "sha256", "'" + "0".repeat( 65 ) + "'" }, { "uploaded_by", "''" } };
        for ( final String[] b : bad ) {
            assertEquals( "23514", assertThrows( SQLException.class, () -> insertWith( b[ 0 ], b[ 1 ] ),
                b[ 0 ] + " = " + b[ 1 ] ).getSQLState(), b[ 0 ] + " = " + b[ 1 ] );
        }
    }

    @Test
    void theColumnsAcceptTheServicesBounds() throws SQLException {
        insertWith( "size_bytes", "0" );
        insertWith( "filename", "'" + "x".repeat( 200 ) + "'" );
        insertWith( "content_type", "'" + "x".repeat( 127 ) + "'" );
    }

    /** Every column, constraint and index of the two tables, as the catalog shows them. */
    private List< String > schema() throws SQLException {
        final List< String > rows = new java.util.ArrayList<>();
        try ( var c = ds.getConnection(); var st = c.createStatement() ) {
            for ( final String sql : List.of(
                "SELECT table_name || '.' || column_name || ' ' || data_type || ' ' || is_nullable FROM information_schema.columns "
                    + "WHERE table_name IN ('work_attachment','work_attachment_blob')",
                "SELECT conrelid::regclass || ' ' || conname || ' ' || pg_get_constraintdef(oid) FROM pg_constraint "
                    + "WHERE conrelid IN ('work_attachment'::regclass,'work_attachment_blob'::regclass)",
                "SELECT indexdef FROM pg_indexes WHERE tablename IN ('work_attachment','work_attachment_blob')" ) ) {
                try ( var rs = st.executeQuery( sql ) ) {
                    while ( rs.next() ) {
                        rows.add( rs.getString( 1 ) );
                    }
                }
            }
        }
        java.util.Collections.sort( rows );
        return rows;
    }

    @Test
    void theMigrationIsANoOpWhenReapplied() throws Exception {
        final String sql = migration().replace( ":app_user", "CURRENT_USER" );
        exec( sql );
        final List< String > before = schema();
        exec( sql );
        assertEquals( before, schema() );
        assertEquals( true, before.stream().anyMatch( r -> r.contains( "CHECK" ) && r.contains( "size_bytes >= 0" ) ) );
        attachment();
    }

    @Test
    void theLicenceHeaderIsWhole() throws Exception {
        assertEquals( true, migration().startsWith( "-- Licensed to the Apache Software Foundation" ) );
        assertEquals( true, migration().contains( "-- under the License.\n" ) );
    }
}
