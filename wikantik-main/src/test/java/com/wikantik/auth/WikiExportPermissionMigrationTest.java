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
package com.wikantik.auth;

import com.wikantik.jdbc.testing.PostgresTestDb;
import com.wikantik.jdbc.testing.RequiresPostgres;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;

import javax.sql.DataSource;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.Statement;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

/**
 * Verifies the V060 migration that grants the new {@code export} wiki
 * permission (Obsidian vault export) to the default Authenticated wiki
 * grant, conservatively: only touched while the row still holds V003's
 * stock actions, so a customised row is never silently granted export.
 */
@RequiresPostgres
@TestInstance( TestInstance.Lifecycle.PER_CLASS )
class WikiExportPermissionMigrationTest
{
    private DataSource ds;
    private String migrationSql;

    @BeforeAll
    void setUp() throws Exception
    {
        ds = PostgresTestDb.createDataSource();
        migrationSql = Files.readString(
                repoRoot().resolve( "bin/db/migrations/V060__wiki_export_permission.sql" ) );
    }

    @BeforeEach
    void clean() throws Exception
    {
        exec( "DELETE FROM policy_grants" );
    }

    @Test
    void defaultAuthenticatedRowGainsExport() throws Exception
    {
        insert( "role", "Authenticated", "wiki", "*", "createPages,createGroups" );
        runMigration();
        assertEquals( "createPages,createGroups,export", authenticatedWikiActions() );
    }

    @Test
    void customisedRowIsLeftAlone() throws Exception
    {
        insert( "role", "Authenticated", "wiki", "*", "createPages" );
        runMigration();
        assertEquals( "createPages", authenticatedWikiActions() );
    }

    @Test
    void rerunIsNoOp() throws Exception
    {
        insert( "role", "Authenticated", "wiki", "*", "createPages,createGroups" );
        runMigration();
        runMigration();
        assertEquals( "createPages,createGroups,export", authenticatedWikiActions() );
    }

    @Test
    void missingRowIsNotCreated() throws Exception
    {
        runMigration();
        assertNull( authenticatedWikiActions() );
    }

    // ---- helpers ----

    private void runMigration() throws Exception
    {
        try( final Connection c = ds.getConnection(); final Statement s = c.createStatement() )
        {
            s.execute( migrationSql );
        }
    }

    private void insert( final String pt, final String pn, final String type,
                         final String target, final String actions ) throws Exception
    {
        exec( String.format(
                "INSERT INTO policy_grants (principal_type, principal_name, permission_type, target, actions) "
                        + "VALUES ('%s','%s','%s','%s','%s')", pt, pn, type, target, actions ) );
    }

    private void exec( final String sql ) throws Exception
    {
        try( final Connection c = ds.getConnection(); final Statement s = c.createStatement() )
        {
            s.executeUpdate( sql );
        }
    }

    private String authenticatedWikiActions() throws Exception
    {
        try ( Connection c = ds.getConnection(); Statement s = c.createStatement();
              ResultSet rs = s.executeQuery( "SELECT actions FROM policy_grants WHERE principal_type='role' "
                      + "AND principal_name='Authenticated' AND permission_type='wiki' AND target='*'" ) ) {
            return rs.next() ? rs.getString( 1 ) : null;
        }
    }

    private static Path repoRoot()
    {
        Path dir = Paths.get( System.getProperty( "user.dir" ) ).toAbsolutePath();
        while( dir != null && !Files.isDirectory( dir.resolve( "bin/db/migrations" ) ) )
        {
            dir = dir.getParent();
        }
        if( dir == null )
        {
            throw new IllegalStateException( "Could not locate repo root (bin/db/migrations)" );
        }
        return dir;
    }
}
