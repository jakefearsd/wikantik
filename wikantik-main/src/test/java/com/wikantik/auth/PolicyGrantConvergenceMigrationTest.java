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

import com.wikantik.PostgresTestContainer;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.testcontainers.junit.jupiter.Testcontainers;

import javax.sql.DataSource;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * Verifies the V043 migration that converges the default Admin god-mode grants
 * (the seeded page and wiki wildcard rows) onto the single canonical
 * {@code all} (AllPermission) row, conservatively, so a deliberately
 * locked-down install is never re-granted.
 */
@Testcontainers( disabledWithoutDocker = true )
@TestInstance( TestInstance.Lifecycle.PER_CLASS )
class PolicyGrantConvergenceMigrationTest
{
    private DataSource ds;
    private String migrationSql;

    @BeforeAll
    void setUp() throws Exception
    {
        ds = PostgresTestContainer.createDataSource();
        migrationSql = Files.readString(
                repoRoot().resolve( "bin/db/migrations/V043__admin_allpermission_canonical.sql" ) );
    }

    @BeforeEach
    void clean() throws Exception
    {
        exec( "DELETE FROM policy_grants" );
    }

    @Test
    void convergesDefaultAdminGodModeToSingleAllRow() throws Exception
    {
        insert( "role", "Admin", "page", "*", "*" );
        insert( "role", "Admin", "wiki", "*", "*" );
        runMigration();
        assertEquals( List.of( "all" ), adminGodModeTypes(),
                "Default Admin page+wiki god-mode rows should converge to a single 'all' row" );
    }

    @Test
    void doesNotReGrantOnLockedDownInstall() throws Exception
    {
        // An operator who deliberately removed Admin's AllPermission must NOT have it restored.
        insert( "role", "All", "page", "*", "view" ); // unrelated grant; no Admin god-mode present
        runMigration();
        assertEquals( 0, countAdminAll(),
                "Migration must not re-grant AllPermission on an install with no default Admin god-mode" );
    }

    @Test
    void isIdempotent() throws Exception
    {
        insert( "role", "Admin", "page", "*", "*" );
        insert( "role", "Admin", "wiki", "*", "*" );
        runMigration();
        runMigration();
        assertEquals( 1, countAdminAll(), "Re-running the migration must remain a single 'all' row" );
    }

    @Test
    void collapsesPageWikiAndExistingAllToOne() throws Exception
    {
        // The production shape: page + wiki defaults plus a manually-added 'all' row.
        insert( "role", "Admin", "page", "*", "*" );
        insert( "role", "Admin", "wiki", "*", "*" );
        insert( "role", "Admin", "all", "*", "*" );
        runMigration();
        assertEquals( List.of( "all" ), adminGodModeTypes(),
                "page + wiki + existing all should collapse to a single 'all' row" );
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

    private int countAdminAll() throws Exception
    {
        try( final Connection c = ds.getConnection(); final Statement s = c.createStatement();
             final ResultSet rs = s.executeQuery(
                     "SELECT count(*) FROM policy_grants WHERE principal_name='Admin' AND permission_type='all'" ) )
        {
            rs.next();
            return rs.getInt( 1 );
        }
    }

    private List< String > adminGodModeTypes() throws Exception
    {
        final List< String > out = new ArrayList<>();
        try( final Connection c = ds.getConnection(); final Statement s = c.createStatement();
             final ResultSet rs = s.executeQuery(
                     "SELECT permission_type FROM policy_grants WHERE principal_name='Admin' "
                             + "AND target='*' AND actions='*' ORDER BY permission_type" ) )
        {
            while( rs.next() )
            {
                out.add( rs.getString( 1 ) );
            }
        }
        return out;
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
