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

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.opentest4j.TestAbortedException;
import org.postgresql.ds.PGSimpleDataSource;
import org.testcontainers.DockerClientFactory;
import org.testcontainers.postgresql.PostgreSQLContainer;
import org.testcontainers.utility.DockerImageName;

import javax.sql.DataSource;
import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.sql.Connection;
import java.sql.SQLException;
import java.sql.Statement;

/**
 * Lazy singleton PostgreSQL Testcontainer with pgvector, shared across all test classes within a
 * single JVM — the replacement for {@code com.wikantik.PostgresTestContainer}. Schema comes from
 * the real {@code bin/db/migrations} ({@link MigrationApplier}), not a hand-written copy, so tests
 * run against exactly what production deploys apply.
 *
 * <p>After migrations, the optional classpath resource {@code postgresql-test-seed.sql} is
 * applied if present (wikantik-main ships one; modules with no seed data simply don't).
 *
 * <p>Use {@link RequiresPostgres} on test classes so tests are gracefully skipped (or, with
 * {@code -Dtests.requireDocker=true}, fail loudly) when Docker is unavailable.
 */
public final class PostgresTestDb {

    private static final Logger LOG = LogManager.getLogger( PostgresTestDb.class );

    private static final String REQUIRE_DOCKER_PROPERTY = "tests.requireDocker";
    private static final String SEED_RESOURCE = "postgresql-test-seed.sql";
    private static final String APP_USER = "test";

    private static volatile PostgreSQLContainer container;
    private static volatile DataSource cachedDataSource;

    private PostgresTestDb() {
    }

    public static DataSource createDataSource() {
        ensureStarted();
        return buildDataSource();
    }

    public static String getJdbcUrl() {
        ensureStarted();
        return container.getJdbcUrl();
    }

    public static String getUsername() {
        ensureStarted();
        return container.getUsername();
    }

    public static String getPassword() {
        ensureStarted();
        return container.getPassword();
    }

    /** {@code TRUNCATE <tables> RESTART IDENTITY CASCADE} — the standard per-test isolation call. */
    public static void truncate( final String... tables ) {
        ensureStarted();
        final String sql = "TRUNCATE " + String.join( ", ", tables ) + " RESTART IDENTITY CASCADE";
        try ( Connection conn = buildDataSource().getConnection(); Statement st = conn.createStatement() ) {
            st.execute( sql );
        } catch ( final SQLException e ) {
            throw new IllegalStateException( "Failed to truncate tables: " + String.join( ", ", tables ), e );
        }
    }

    /**
     * Result of a Docker-availability probe: whether Docker is reachable, and — when it is
     * not — why, for the message surfaced to the caller (a skip reason locally, or the message
     * of a hard failure in CI).
     */
    record DockerAvailability( boolean available, String reason ) {
    }

    static DockerAvailability checkDockerAvailability() {
        try {
            final boolean available = DockerClientFactory.instance().isDockerAvailable();
            return new DockerAvailability( available, available ? "" : "Docker daemon not reachable" );
        } catch ( final RuntimeException e ) {
            LOG.warn( "Docker availability probe failed: {}", e.getMessage(), e );
            return new DockerAvailability( false, e.getMessage() == null ? e.toString() : e.getMessage() );
        }
    }

    /**
     * Raises the standard "Docker unavailable" response used by both this class and
     * {@link RequiresPostgres}: an {@link IllegalStateException} when the caller opted into
     * {@code -Dtests.requireDocker=true} (CI must fail, not silently skip), otherwise a
     * {@link TestAbortedException} so JUnit reports a skip with a visible reason.
     */
    static void failOrAbort( final String reason ) {
        final String message = "Docker not available: " + reason;
        if ( Boolean.getBoolean( REQUIRE_DOCKER_PROPERTY ) ) {
            throw new IllegalStateException( message
                + " (-D" + REQUIRE_DOCKER_PROPERTY + "=true requires Docker to be present)" );
        }
        throw new TestAbortedException( message );
    }

    private static DataSource buildDataSource() {
        final PGSimpleDataSource ds = new PGSimpleDataSource();
        ds.setUrl( container.getJdbcUrl() );
        ds.setUser( container.getUsername() );
        ds.setPassword( container.getPassword() );
        return ds;
    }

    private static void ensureStarted() {
        if ( container != null ) {
            return;
        }
        synchronized ( PostgresTestDb.class ) {
            if ( container != null ) {
                return;
            }
            final DockerAvailability availability = checkDockerAvailability();
            if ( !availability.available() ) {
                failOrAbort( availability.reason() );
            }

            final PostgreSQLContainer started = new PostgreSQLContainer(
                DockerImageName.parse( "pgvector/pgvector:pg17" )
                    .asCompatibleSubstituteFor( "postgres" ) )
                .withDatabaseName( "wikantik_test" )
                .withUsername( APP_USER )
                .withPassword( APP_USER );
            started.start();

            try {
                final PGSimpleDataSource ds = new PGSimpleDataSource();
                ds.setUrl( started.getJdbcUrl() );
                ds.setUser( started.getUsername() );
                ds.setPassword( started.getPassword() );
                MigrationApplier.applyAll( ds, APP_USER );
                applySeedIfPresent( ds );
            } catch ( final SQLException e ) {
                started.stop();
                throw new IllegalStateException( "Failed to initialize PostgresTestDb schema/seed", e );
            }

            container = started;
        }
    }

    private static void applySeedIfPresent( final DataSource dataSource ) throws SQLException {
        try ( InputStream in = PostgresTestDb.class.getClassLoader().getResourceAsStream( SEED_RESOURCE ) ) {
            if ( in == null ) {
                return;
            }
            final String sql;
            try ( BufferedReader reader = new BufferedReader( new InputStreamReader( in, StandardCharsets.UTF_8 ) ) ) {
                final StringBuilder sb = new StringBuilder();
                String line;
                while ( ( line = reader.readLine() ) != null ) {
                    sb.append( line ).append( '\n' );
                }
                sql = sb.toString();
            }
            try ( Connection conn = dataSource.getConnection(); Statement st = conn.createStatement() ) {
                st.execute( sql );
            }
        } catch ( final IOException e ) {
            throw new UncheckedIOException( "Failed to read " + SEED_RESOURCE, e );
        }
    }
}
