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
package com.wikantik.jdbc.testing;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Unit tests for {@link MigrationApplier}'s Docker-free pieces: sorting, filename parsing,
 * {@code :app_user} substitution, and psql meta-command detection. The DB-applying path
 * ({@link MigrationApplier#applyAll}) is exercised by {@link PostgresTestDb} itself via every
 * {@code @RequiresPostgres} test that starts the container.
 */
class MigrationApplierTest {

    @Test
    void listMigrationsSortsNumericallyNotLexicographically( @TempDir final Path dir ) throws IOException {
        // Lexicographic order would put V10 before V2; numeric order must not.
        Files.createFile( dir.resolve( "V2__second.sql" ) );
        Files.createFile( dir.resolve( "V10__tenth.sql" ) );
        Files.createFile( dir.resolve( "V1__first.sql" ) );
        Files.createFile( dir.resolve( "README.md" ) ); // must be ignored

        final List< String > names = MigrationApplier.listMigrations( dir ).stream()
            .map( p -> p.getFileName().toString() )
            .collect( Collectors.toList() );

        assertEquals( List.of( "V1__first.sql", "V2__second.sql", "V10__tenth.sql" ), names );
    }

    @Test
    void versionNumberExtractsTheIntegerAfterV() {
        assertEquals( 31, MigrationApplier.versionNumber( Path.of( "V031__monitoring_role.sql" ) ) );
        assertEquals( 1, MigrationApplier.versionNumber( Path.of( "V1__x.sql" ) ) );
        assertEquals( 100, MigrationApplier.versionNumber( Path.of( "V100__y.sql" ) ) );
    }

    @Test
    void versionIdDropsTheSqlExtension() {
        assertEquals( "V031__monitoring_role",
            MigrationApplier.versionId( Path.of( "V031__monitoring_role.sql" ) ) );
    }

    @Test
    void substituteAppUserReplacesEveryOccurrence() {
        final String sql = "GRANT SELECT ON t TO :app_user;\nGRANT USAGE ON SEQUENCE s TO :app_user;";
        final String out = MigrationApplier.substituteAppUser( sql, "wikantik" );
        assertEquals( "GRANT SELECT ON t TO wikantik;\nGRANT USAGE ON SEQUENCE s TO wikantik;", out );
        assertFalse( out.contains( ":app_user" ) );
    }

    @Test
    void substituteAppUserIsNoOpWhenVariableAbsent() {
        final String sql = "CREATE TABLE t (id INT)";
        assertEquals( sql, MigrationApplier.substituteAppUser( sql, "wikantik" ) );
    }

    @Test
    void containsPsqlMetaCommandDetectsALeadingBackslashLine() {
        final String meta = "SELECT 1 \\gset\n\\if :flag\nALTER ROLE x LOGIN;\n\\endif\n";
        assertTrue( MigrationApplier.containsPsqlMetaCommand( meta ) );
    }

    @Test
    void containsPsqlMetaCommandIsFalseForOrdinarySql() {
        final String sql = "CREATE TABLE IF NOT EXISTS t (\n    id UUID PRIMARY KEY\n);\n"
            + "DO $$\nBEGIN\n    NULL;\nEND\n$$;\n";
        assertFalse( MigrationApplier.containsPsqlMetaCommand( sql ) );
    }

    @Test
    void containsPsqlMetaCommandIgnoresLeadingWhitespaceBeforeTheBackslash() {
        assertTrue( MigrationApplier.containsPsqlMetaCommand( "    \\if :flag\nDO nothing;\n" ) );
    }

    @Test
    void findRepoRootLocatesTheDirectoryContainingBinDbMigrations() {
        final Path root = MigrationApplier.findRepoRoot();
        assertTrue( Files.isDirectory( root.resolve( "bin/db/migrations" ) ) );
    }

    @Test
    void realMigrationsDirectoryHasNoOtherPsqlMetaCommandFileThanV031() throws IOException {
        final Path migrationsDir = MigrationApplier.findRepoRoot().resolve( "bin/db/migrations" );
        final List< Path > flagged = MigrationApplier.listMigrations( migrationsDir ).stream()
            .filter( p -> {
                try {
                    return MigrationApplier.containsPsqlMetaCommand( Files.readString( p ) );
                } catch ( final IOException e ) {
                    throw new java.io.UncheckedIOException( e );
                }
            } )
            .collect( Collectors.toList() );

        assertEquals( List.of( "V031__monitoring_role.sql" ),
            flagged.stream().map( p -> p.getFileName().toString() ).collect( Collectors.toList() ) );
    }
}
