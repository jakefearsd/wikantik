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
package com.wikantik;

import org.postgresql.ds.PGSimpleDataSource;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.utility.DockerImageName;

import javax.sql.DataSource;

/**
 * Lazy singleton PostgreSQL Testcontainer with pgvector, shared across all
 * test classes within a single JVM. Schema is initialized once via
 * {@code postgresql-test.sql}.
 *
 * <p>Use {@code @Testcontainers(disabledWithoutDocker = true)} on test classes
 * so tests are gracefully skipped when Docker is unavailable.</p>
 */
public final class PostgresTestContainer {

    private static volatile PostgreSQLContainer< ? > container;
    private static volatile DataSource cachedDataSource;

    private PostgresTestContainer() {
    }

    public static DataSource createDataSource() {
        ensureStarted();
        final PGSimpleDataSource ds = new PGSimpleDataSource();
        ds.setUrl( container.getJdbcUrl() );
        ds.setUser( container.getUsername() );
        ds.setPassword( container.getPassword() );
        return ds;
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

    private static void ensureStarted() {
        if( container != null ) {
            return;
        }
        synchronized( PostgresTestContainer.class ) {
            if( container != null ) {
                return;
            }
            container = new PostgreSQLContainer<>(
                DockerImageName.parse( "pgvector/pgvector:pg17" )
                    .asCompatibleSubstituteFor( "postgres" ) )
                .withDatabaseName( "wikantik_test" )
                .withUsername( "test" )
                .withPassword( "test" )
                .withInitScript( "postgresql-test.sql" );
            container.start();
        }
    }
}
