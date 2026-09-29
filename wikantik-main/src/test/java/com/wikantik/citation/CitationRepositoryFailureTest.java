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
package com.wikantik.citation;

import com.wikantik.api.citation.CitationStatus;
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

import static org.junit.jupiter.api.Assertions.*;

/**
 * Every read/write method on {@link CitationRepository} wraps its JDBC call in
 * {@code try { ... } catch (SQLException e) { LOG.warn(...); throw new IllegalStateException(...) }}.
 * {@link CitationRepositoryTest} only exercises the happy paths against a real Postgres instance
 * (plus {@link CitationRepositoryRollbackTest} for the {@code replaceForSource} rollback). A
 * {@link DataSource} whose {@code getConnection()} always throws a genuine {@link SQLException}
 * exercises every remaining catch branch — including {@code findAll}, {@code findByStatus},
 * {@code updateStatus}, {@code touchChecked} and {@code countsByStatus}, none of which any other
 * test in this class touches at all — without a live database.
 */
class CitationRepositoryFailureTest {

    /** Always fails {@code getConnection()} with a real (checked) {@link SQLException}. */
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

    private CitationRepository repo;

    @BeforeEach
    void setUp() {
        repo = new CitationRepository( new AlwaysFailingDataSource() );
    }

    private static CitationRow row() {
        return new CitationRow( 0, "SrcId", "TargetId", "Heading", "span text", "hash1",
                "claim text", 0, null, CitationStatus.CURRENT, null, null, null );
    }

    @Test
    void replaceForSource_wrapsSqlExceptionInIllegalStateException() {
        final IllegalStateException ex = assertThrows( IllegalStateException.class,
                () -> repo.replaceForSource( "SrcId", List.of( row() ) ) );
        assertTrue( ex.getCause() instanceof SQLException );
    }

    @Test
    void findAll_wrapsSqlExceptionInIllegalStateException() {
        final IllegalStateException ex = assertThrows( IllegalStateException.class, repo::findAll );
        assertTrue( ex.getCause() instanceof SQLException );
    }

    @Test
    void findBySource_wrapsSqlExceptionInIllegalStateException() {
        assertThrows( IllegalStateException.class, () -> repo.findBySource( "SrcId" ) );
    }

    @Test
    void findByTarget_wrapsSqlExceptionInIllegalStateException() {
        assertThrows( IllegalStateException.class, () -> repo.findByTarget( "TargetId" ) );
    }

    @Test
    void findByStatus_wrapsSqlExceptionInIllegalStateException() {
        assertThrows( IllegalStateException.class, () -> repo.findByStatus( CitationStatus.STALE ) );
    }

    @Test
    void updateStatus_wrapsSqlExceptionInIllegalStateException() {
        final IllegalStateException ex = assertThrows( IllegalStateException.class,
                () -> repo.updateStatus( 1L, CitationStatus.STALE, Instant.now() ) );
        assertTrue( ex.getCause() instanceof SQLException );
    }

    @Test
    void touchChecked_wrapsSqlExceptionInIllegalStateException() {
        final IllegalStateException ex = assertThrows( IllegalStateException.class,
                () -> repo.touchChecked( 1L, Instant.now() ) );
        assertTrue( ex.getCause() instanceof SQLException );
    }

    @Test
    void countsByStatus_wrapsSqlExceptionInIllegalStateException() {
        final IllegalStateException ex = assertThrows( IllegalStateException.class, repo::countsByStatus );
        assertTrue( ex.getCause() instanceof SQLException );
    }
}
