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
package com.wikantik.architecture;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.regex.Pattern;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;

/**
 * Schema single-source ratchet — see
 * docs/superpowers/plans/2026-08-22-one-way-to-touch-the-database.md.
 *
 * <p>Historically, tests hand-wrote their own {@code CREATE TABLE} DDL
 * (H2 or Postgres) instead of applying the real migrations under
 * {@code bin/db/migrations}, which is how test schema and production schema
 * drifted apart. The migration to {@code com.wikantik.jdbc.testing.PostgresTestDb}
 * (Task 0.3) applies the real migrations, so a test that still hand-rolls its
 * own DDL is a straggler, not a legitimate exception.</p>
 *
 * <p>This test walks every {@code wikantik-*} module's {@code src/test/java}
 * and {@code src/test/resources} trees (skipping {@code wikantik-jdbc/src/test}
 * — that module tests the {@code Jdbc} primitive itself and is allowed to keep
 * hand-written H2 DDL for that narrow purpose) for files containing
 * {@code CREATE TABLE}, and requires every one of them to be listed in
 * {@code test-ddl-baseline.txt}. The baseline is a burn-down list: entries
 * only ever come out (as files are migrated onto the shared fixture), never
 * added to paper over a new straggler. A baseline entry that no longer exists,
 * or no longer contains DDL, is also a failure — it means the baseline itself
 * has gone stale and must be trimmed.</p>
 */
class TestSchemaSingleSourceTest
{
    /**
     * A line that defines a table. {@code CREATE TABLE … PARTITION OF} is excluded on purpose:
     * attaching a partition to an already-migrated partitioned table adds no columns or
     * constraints — it is the runtime path {@code JdbcAuditRepository} takes in production, and
     * a rollback test must be able to exercise it.
     */
    private static final Pattern CREATE_TABLE = Pattern.compile(
            "^(?!.*PARTITION\\s+OF).*CREATE\\s+TABLE", Pattern.CASE_INSENSITIVE | Pattern.MULTILINE );

    private static final String BASELINE_RESOURCE = "test-ddl-baseline.txt";

    @Test
    void every_hand_rolled_ddl_file_is_in_the_baseline() throws IOException
    {
        Path repoRoot = repoRoot();
        Set<String> baseline = readBaseline( repoRoot );
        List<String> found = findDdlFiles( repoRoot );

        List<String> notBaselined = found.stream()
            .filter( path -> !baseline.contains( path ) )
            .sorted()
            .toList();

        if( !notBaselined.isEmpty() )
        {
            fail( "The following test files contain hand-rolled DDL (CREATE TABLE) but are not "
                + "listed in wikantik-war/src/test/resources/" + BASELINE_RESOURCE + ". Either "
                + "migrate the test onto com.wikantik.jdbc.testing.PostgresTestDb (preferred) or, "
                + "if that is genuinely out of scope for this change, add the path to the "
                + "baseline:\n  " + String.join( "\n  ", notBaselined ) );
        }
    }

    @Test
    void baseline_contains_no_stale_entries() throws IOException
    {
        Path repoRoot = repoRoot();
        Set<String> found = new LinkedHashSet<>( findDdlFiles( repoRoot ) );

        List<String> stale = new ArrayList<>();
        for( String entry : readBaseline( repoRoot ) )
        {
            Path resolved = repoRoot.resolve( entry );
            if( !Files.isRegularFile( resolved ) )
            {
                stale.add( entry + " — file no longer exists" );
            }
            else if( !found.contains( entry ) )
            {
                stale.add( entry + " — file no longer contains CREATE TABLE" );
            }
        }

        if( !stale.isEmpty() )
        {
            fail( "The following " + BASELINE_RESOURCE + " entries are stale — remove them "
                + "(the baseline can only shrink honestly):\n  " + String.join( "\n  ", stale ) );
        }
    }

    /** Every path collected here is repo-root-relative, using {@code /} separators. */
    private static List<String> findDdlFiles( Path repoRoot ) throws IOException
    {
        List<String> found = new ArrayList<>();
        for( Path moduleDir : listWikantikModuleDirs( repoRoot ) )
        {
            if( moduleDir.getFileName().toString().equals( "wikantik-jdbc" ) )
            {
                continue;
            }
            for( String sub : new String[] { "src/test/java", "src/test/resources" } )
            {
                Path dir = moduleDir.resolve( sub );
                if( !Files.isDirectory( dir ) )
                {
                    continue;
                }
                try( Stream<Path> paths = Files.walk( dir ) )
                {
                    paths
                        .filter( Files::isRegularFile )
                        .filter( TestSchemaSingleSourceTest::isJavaOrSql )
                        .filter( TestSchemaSingleSourceTest::isNotBuildOutputOrNodeModules )
                        .filter( TestSchemaSingleSourceTest::isNotThisScannersOwnPackage )
                        .filter( TestSchemaSingleSourceTest::containsCreateTable )
                        .map( path -> toRepoRelative( repoRoot, path ) )
                        .forEach( found::add );
                }
                catch( UncheckedIOException e )
                {
                    throw e.getCause();
                }
            }
        }
        return found;
    }

    private static List<Path> listWikantikModuleDirs( Path repoRoot ) throws IOException
    {
        try( Stream<Path> entries = Files.list( repoRoot ) )
        {
            return entries
                .filter( Files::isDirectory )
                .filter( p -> p.getFileName().toString().startsWith( "wikantik-" ) )
                .toList();
        }
    }

    private static boolean isJavaOrSql( Path path )
    {
        String name = path.getFileName().toString();
        return name.endsWith( ".java" ) || name.endsWith( ".sql" );
    }

    private static boolean isNotBuildOutputOrNodeModules( Path path )
    {
        for( Path segment : path )
        {
            String name = segment.toString();
            if( name.equals( "target" ) || name.equals( "node_modules" ) )
            {
                return false;
            }
        }
        return true;
    }

    /**
     * This scanner's own source (and its sibling {@code JdbcAccessArchTest}) lives under
     * {@code com/wikantik/architecture/} and necessarily discusses "CREATE TABLE" in prose —
     * exempt the package from the scan so the detector doesn't flag itself.
     */
    private static boolean isNotThisScannersOwnPackage( Path path )
    {
        return !path.toString().replace( '\\', '/' ).contains( "/com/wikantik/architecture/" );
    }

    private static boolean containsCreateTable( Path path )
    {
        try
        {
            String content = Files.readString( path );
            return CREATE_TABLE.matcher( content ).find();
        }
        catch( IOException e )
        {
            throw new UncheckedIOException( "Failed to read " + path, e );
        }
    }

    private static String toRepoRelative( Path repoRoot, Path path )
    {
        return repoRoot.relativize( path ).toString().replace( '\\', '/' );
    }

    private static Set<String> readBaseline( Path repoRoot ) throws IOException
    {
        Path baselinePath = repoRoot.resolve( "wikantik-war/src/test/resources/" + BASELINE_RESOURCE );
        assertTrue( Files.isRegularFile( baselinePath ), "Missing baseline file: " + baselinePath );

        Set<String> entries = new LinkedHashSet<>();
        for( String line : Files.readAllLines( baselinePath ) )
        {
            String trimmed = line.strip();
            if( trimmed.isEmpty() || trimmed.startsWith( "#" ) )
            {
                continue;
            }
            entries.add( trimmed );
        }
        return entries;
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
