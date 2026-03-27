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
package com.wikantik.observability.health;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import javax.sql.DataSource;
import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

@ExtendWith( MockitoExtension.class )
class DatabaseHealthCheckTest {

    @Mock private DataSource dataSource;
    @Mock private Connection connection;
    @Mock private Statement statement;
    @Mock private ResultSet resultSet;

    @Test
    void reportsUpWhenQuerySucceeds() throws Exception {
        when( dataSource.getConnection() ).thenReturn( connection );
        when( connection.createStatement() ).thenReturn( statement );
        when( statement.executeQuery( "SELECT 1" ) ).thenReturn( resultSet );
        when( resultSet.next() ).thenReturn( true );

        final DatabaseHealthCheck check = new DatabaseHealthCheck( dataSource );
        final HealthResult result = check.check();

        assertEquals( HealthStatus.UP, result.status() );
        assertTrue( result.responseTimeMs() >= 0 );
    }

    @Test
    void reportsDownWhenConnectionFails() throws Exception {
        when( dataSource.getConnection() ).thenThrow( new SQLException( "Connection refused" ) );

        final DatabaseHealthCheck check = new DatabaseHealthCheck( dataSource );
        final HealthResult result = check.check();

        assertEquals( HealthStatus.DOWN, result.status() );
        assertEquals( "Connection refused", result.detail().get( "error" ) );
        assertTrue( result.responseTimeMs() >= 0 );
    }

    @Test
    void reportsDownWhenQueryFails() throws Exception {
        when( dataSource.getConnection() ).thenReturn( connection );
        when( connection.createStatement() ).thenReturn( statement );
        when( statement.executeQuery( "SELECT 1" ) ).thenThrow( new SQLException( "Query failed" ) );

        final DatabaseHealthCheck check = new DatabaseHealthCheck( dataSource );
        final HealthResult result = check.check();

        assertEquals( HealthStatus.DOWN, result.status() );
        assertEquals( "Query failed", result.detail().get( "error" ) );
    }

    @Test
    void reportsDownWhenJndiLookupFails() {
        // JNDI constructor — no JNDI context available in unit test, so lookup fails
        final DatabaseHealthCheck check = new DatabaseHealthCheck( "jdbc/NonExistent" );
        final HealthResult result = check.check();

        assertEquals( HealthStatus.DOWN, result.status() );
        assertTrue( result.detail().containsKey( "error" ) );
        assertTrue( result.responseTimeMs() >= 0 );
    }

    @Test
    void closesResourcesAfterCheck() throws Exception {
        when( dataSource.getConnection() ).thenReturn( connection );
        when( connection.createStatement() ).thenReturn( statement );
        when( statement.executeQuery( "SELECT 1" ) ).thenReturn( resultSet );
        when( resultSet.next() ).thenReturn( true );

        final DatabaseHealthCheck check = new DatabaseHealthCheck( dataSource );
        check.check();

        verify( resultSet ).close();
        verify( statement ).close();
        verify( connection ).close();
    }

    @Test
    void nameIsDatabase() {
        final DatabaseHealthCheck check = new DatabaseHealthCheck( dataSource );
        assertEquals( "database", check.name() );
    }

}
