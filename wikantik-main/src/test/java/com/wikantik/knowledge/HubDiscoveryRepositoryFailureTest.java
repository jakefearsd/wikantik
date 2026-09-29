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
package com.wikantik.knowledge;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import javax.sql.DataSource;
import java.io.PrintWriter;
import java.sql.Connection;
import java.sql.SQLException;
import java.sql.SQLFeatureNotSupportedException;
import java.util.List;
import java.util.logging.Logger;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Every {@link HubDiscoveryRepository} read/write method wraps its call in
 * {@code try { ... } catch (SQLException e) { LOG.warn(...); <default> }}. None of those
 * catch branches are reachable through {@link PostgresTestDb} happy-path tests
 * ({@link HubDiscoveryRepositoryTest}); a {@link DataSource} whose {@code getConnection()}
 * always throws a genuine {@link SQLException} exercises every one of them without a
 * live database at all.
 */
class HubDiscoveryRepositoryFailureTest {

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

    private HubDiscoveryRepository repo;

    @BeforeEach
    void setUp() {
        repo = new HubDiscoveryRepository( new AlwaysFailingDataSource() );
    }

    @Test
    void insert_wrapsSqlExceptionInRuntimeException() {
        final RuntimeException ex = assertThrows( RuntimeException.class,
                () -> repo.insert( "H", "A", List.of( "A", "B" ), 0.5 ) );
        assertTrue( ex.getCause() instanceof SQLException );
    }

    @Test
    void findById_returnsNullOnFailure() {
        assertNull( repo.findById( 1 ) );
    }

    @Test
    void list_returnsEmptyListOnFailure() {
        assertEquals( List.of(), repo.list( 50, 0 ) );
    }

    @Test
    void count_returnsZeroOnFailure() {
        assertEquals( 0, repo.count() );
    }

    @Test
    void delete_returnsFalseOnFailure() {
        assertFalse( repo.delete( 1 ) );
    }

    @Test
    void markDismissed_returnsFalseOnFailure() {
        assertFalse( repo.markDismissed( 1, "alice" ) );
    }

    @Test
    void listDismissed_returnsEmptyListOnFailure() {
        assertEquals( List.of(), repo.listDismissed( 50, 0 ) );
    }

    @Test
    void countDismissed_returnsZeroOnFailure() {
        assertEquals( 0, repo.countDismissed() );
    }

    @Test
    void deleteDismissed_returnsFalseOnFailure() {
        assertFalse( repo.deleteDismissed( 1 ) );
    }

    @Test
    void deleteDismissedBulk_returnsZeroOnFailure() {
        assertEquals( 0, repo.deleteDismissedBulk( List.of( 1, 2 ) ) );
    }

    @Test
    void findDismissedMatchingMembers_returnsFalseOnFailure() {
        assertFalse( repo.findDismissedMatchingMembers( List.of( "A", "B" ) ) );
    }
}
