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
package com.wikantik.audit;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import javax.sql.DataSource;
import java.io.PrintWriter;
import java.sql.Connection;
import java.sql.SQLException;
import java.sql.SQLFeatureNotSupportedException;
import java.time.Instant;
import java.util.List;
import java.util.logging.Logger;

import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * {@link JdbcAuditRepository#chainHead()}, {@code append}, {@code query} and {@code verifyChain}
 * each wrap their JDBC call in {@code try { ... } catch (SQLException e) { throw new
 * IllegalStateException(...) }}. Neither {@link JdbcAuditRepositoryTest} (least-privilege-role
 * happy path) nor {@link JdbcAuditRepositoryRollbackTest} (unchecked mid-transaction failure,
 * which propagates the RuntimeException directly and never reaches these catch blocks) nor
 * {@link JdbcAuditRepositoryQueryTest} (happy-path reads) exercises any of these four catch
 * branches. A {@link DataSource} whose {@code getConnection()} always throws a genuine
 * {@link SQLException} does, without a live database.
 */
class JdbcAuditRepositoryFailureTest {

    private static final class AlwaysFailingDataSource implements DataSource {
        @Override public Connection getConnection() throws SQLException {
            throw new SQLException( "boom: connection refused" );
        }
        @Override public Connection getConnection( final String u, final String p ) throws SQLException {
            throw new SQLException( "boom: connection refused" );
        }
        @Override public PrintWriter getLogWriter() { return null; }
        @Override public void setLogWriter( final PrintWriter out ) { }
        @Override public void setLoginTimeout( final int seconds ) { }
        @Override public int getLoginTimeout() { return 0; }
        @Override public Logger getParentLogger() throws SQLFeatureNotSupportedException {
            throw new SQLFeatureNotSupportedException();
        }
        @Override public < T > T unwrap( final Class< T > iface ) { return null; }
        @Override public boolean isWrapperFor( final Class< ? > iface ) { return false; }
    }

    private JdbcAuditRepository repo;

    @BeforeEach
    void setUp() {
        repo = new JdbcAuditRepository( new AlwaysFailingDataSource() );
    }

    @Test
    void chainHead_wrapsSqlExceptionInIllegalStateException() {
        final IllegalStateException ex = assertThrows( IllegalStateException.class, repo::chainHead );
        assertTrue( ex.getCause() instanceof SQLException );
    }

    @Test
    void append_wrapsSqlExceptionInIllegalStateException() {
        final AuditEntry entry = AuditEntry.builder()
                .eventTime( Instant.now() )
                .category( AuditCategory.ADMIN )
                .eventType( "failure.test" )
                .actorType( "TEST" )
                .actorPrincipal( "tester" )
                .outcome( AuditOutcome.SUCCESS )
                .detail( "{}" )
                .build();
        final IllegalStateException ex = assertThrows( IllegalStateException.class,
                () -> repo.append( List.of( entry ) ) );
        assertTrue( ex.getCause() instanceof SQLException );
    }

    @Test
    void query_wrapsSqlExceptionInIllegalStateException() {
        final IllegalStateException ex = assertThrows( IllegalStateException.class,
                () -> repo.query( AuditQuery.all() ) );
        assertTrue( ex.getCause() instanceof SQLException );
    }

    @Test
    void verifyChain_wrapsSqlExceptionInIllegalStateException() {
        final IllegalStateException ex = assertThrows( IllegalStateException.class,
                () -> repo.verifyChain( 1L, 100L ) );
        assertTrue( ex.getCause() instanceof SQLException );
    }
}
