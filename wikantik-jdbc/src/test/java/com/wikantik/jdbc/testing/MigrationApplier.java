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

import com.wikantik.jdbc.Transactions;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import javax.sql.DataSource;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.sql.Connection;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.Comparator;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;
import java.util.stream.Stream;

/**
 * Applies {@code bin/db/migrations/V*.sql} to a JDBC {@link DataSource}, in order, exactly as
 * {@code bin/db/migrate.sh} does against a real database — so tests run against the same schema
 * definition production deployments do, instead of a hand-written parallel copy.
 *
 * <p>Each file is executed as a single statement (the PostgreSQL wire protocol's simple-query
 * mode happily accepts a whole file's worth of {@code ;}-separated statements, including
 * dollar-quoted {@code DO $$ … $$} blocks, in one call) inside its own transaction, then recorded
 * in {@code schema_migrations} — mirroring {@code migrate.sh}'s {@code --single-transaction} +
 * separate tracking insert. Files containing psql meta-commands (a line starting with
 * {@code \}, e.g. {@code \if}/{@code \gset}) are not valid plain SQL and are skipped with a
 * logged warning, matching {@code migrate.sh}'s manual-only handling of {@code V031}.
 */
public final class MigrationApplier {

    private static final Logger LOG = LogManager.getLogger( MigrationApplier.class );

    private static final Pattern VERSION_PATTERN = Pattern.compile( "V(\\d+)__.*\\.sql" );

    private MigrationApplier() {
    }

    /**
     * Locates the repository root by walking up from {@code user.dir} until a directory
     * containing {@code bin/db/migrations} is found.
     */
    public static Path findRepoRoot() {
        Path dir = Paths.get( System.getProperty( "user.dir" ) ).toAbsolutePath();
        while ( dir != null && !Files.isDirectory( dir.resolve( "bin/db/migrations" ) ) ) {
            dir = dir.getParent();
        }
        if ( dir == null ) {
            throw new IllegalStateException( "Could not locate repo root (bin/db/migrations) from "
                + System.getProperty( "user.dir" ) );
        }
        return dir;
    }

    /**
     * Lists {@code V*.sql} files in {@code migrationsDir}, sorted numerically by the version
     * number embedded in the filename (not lexicographically — robust past 3-digit versions).
     */
    public static List< Path > listMigrations( final Path migrationsDir ) {
        try ( Stream< Path > files = Files.list( migrationsDir ) ) {
            return files
                .filter( p -> VERSION_PATTERN.matcher( p.getFileName().toString() ).matches() )
                .sorted( Comparator.comparingInt( MigrationApplier::versionNumber ) )
                .collect( Collectors.toList() );
        } catch ( final IOException e ) {
            throw new UncheckedIOException( "Failed to list migrations in " + migrationsDir, e );
        }
    }

    /** Extracts the numeric version from a filename like {@code V031__monitoring_role.sql} -> 31. */
    public static int versionNumber( final Path migrationFile ) {
        final Matcher m = VERSION_PATTERN.matcher( migrationFile.getFileName().toString() );
        if ( !m.matches() ) {
            throw new IllegalArgumentException( "Not a migration filename: " + migrationFile );
        }
        return Integer.parseInt( m.group( 1 ) );
    }

    /** The tracked version identifier {@code migrate.sh} records: the filename without {@code .sql}. */
    public static String versionId( final Path migrationFile ) {
        final String name = migrationFile.getFileName().toString();
        return name.substring( 0, name.length() - ".sql".length() );
    }

    /**
     * True if {@code sql} contains a psql meta-command — a line whose first non-whitespace
     * character is {@code \} (e.g. {@code \if}, {@code \endif}, {@code \gset}). Such files are
     * not valid standalone SQL and cannot be sent to the backend via JDBC.
     */
    public static boolean containsPsqlMetaCommand( final String sql ) {
        for ( final String line : sql.split( "\n", -1 ) ) {
            if ( line.stripLeading().startsWith( "\\" ) ) {
                return true;
            }
        }
        return false;
    }

    /**
     * Substitutes the psql variable {@code :app_user} — the only variable form used across the
     * migrations (always in an unquoted identifier position, e.g. {@code GRANT … TO :app_user}) —
     * with the literal {@code appUser}.
     */
    public static String substituteAppUser( final String sql, final String appUser ) {
        return sql.replace( ":app_user", appUser );
    }

    /**
     * Applies every {@code V*.sql} migration under {@code <repoRoot>/bin/db/migrations} to
     * {@code dataSource}, substituting {@code :app_user} with {@code appUser}, skipping (and
     * logging) any file with a psql meta-command, and recording each applied version in
     * {@code schema_migrations}. A migration that fails is rolled back and rethrown with its
     * file name.
     */
    public static void applyAll( final DataSource dataSource, final String appUser ) throws SQLException {
        final Path migrationsDir = findRepoRoot().resolve( "bin/db/migrations" );
        applyAll( dataSource, appUser, migrationsDir );
    }

    /** {@link #applyAll(DataSource, String)}, with the migrations directory given explicitly (for tests). */
    public static void applyAll( final DataSource dataSource, final String appUser, final Path migrationsDir )
            throws SQLException {
        final List< Path > migrations = listMigrations( migrationsDir );
        try ( Connection conn = dataSource.getConnection() ) {
            for ( final Path file : migrations ) {
                applyOne( conn, file, appUser );
            }
        }
    }

    private static void applyOne( final Connection conn, final Path file, final String appUser )
            throws SQLException {
        final String name = file.getFileName().toString();
        final String rawSql;
        try {
            rawSql = Files.readString( file );
        } catch ( final IOException e ) {
            throw new UncheckedIOException( "Failed to read migration " + name, e );
        }

        if ( containsPsqlMetaCommand( rawSql ) ) {
            LOG.warn( "Skipping migration with psql meta-commands (not valid standalone SQL): {}", name );
            return;
        }

        final String sql = substituteAppUser( rawSql, appUser );
        final boolean prevAutoCommit = conn.getAutoCommit();
        conn.setAutoCommit( false );
        try {
            try ( Statement st = conn.createStatement() ) {
                st.execute( sql );
            }
            conn.commit();
        } catch ( final SQLException e ) {
            Transactions.rollbackQuietly( conn, e, LOG, "migration " + name );
            throw new SQLException( "Migration failed: " + name + ": " + e.getMessage(), e );
        } finally {
            conn.setAutoCommit( prevAutoCommit );
        }

        try ( Statement st = conn.createStatement() ) {
            st.execute( "INSERT INTO schema_migrations (version) VALUES ('" + versionId( file )
                + "') ON CONFLICT DO NOTHING" );
        }
    }
}
