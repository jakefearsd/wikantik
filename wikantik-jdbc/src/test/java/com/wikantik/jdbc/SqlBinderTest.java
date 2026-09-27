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
package com.wikantik.jdbc;

import org.junit.jupiter.api.Test;
import org.mockito.Mockito;

import java.sql.PreparedStatement;
import java.sql.SQLException;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.verify;

class SqlBinderTest {

    @Test
    void noneBinderDoesNothing() {
        final PreparedStatement ps = Mockito.mock( PreparedStatement.class );
        assertDoesNotThrow( () -> SqlBinder.NONE.bind( ps ) );
        Mockito.verifyNoInteractions( ps );
    }

    @Test
    void positionalBindsParamsInOrderStartingAtOne() throws SQLException {
        final PreparedStatement ps = Mockito.mock( PreparedStatement.class );
        final SqlBinder binder = SqlBinder.positional( List.of( "alice", 42, true ) );

        binder.bind( ps );

        verify( ps ).setObject( 1, "alice" );
        verify( ps ).setObject( 2, 42 );
        verify( ps ).setObject( 3, true );
    }

    @Test
    void positionalWithEmptyListBindsNothing() throws SQLException {
        final PreparedStatement ps = Mockito.mock( PreparedStatement.class );
        SqlBinder.positional( List.of() ).bind( ps );
        Mockito.verifyNoInteractions( ps );
    }

    @Test
    void positionalMatchesRealPreparedStatementViaH2() throws SQLException {
        final org.h2.jdbcx.JdbcDataSource h2 = new org.h2.jdbcx.JdbcDataSource();
        h2.setURL( "jdbc:h2:mem:sqlbindertest_" + System.nanoTime() + ";DB_CLOSE_DELAY=-1" );
        try ( java.sql.Connection conn = h2.getConnection() ) {
            try ( java.sql.Statement s = conn.createStatement() ) {
                s.executeUpdate( "CREATE TABLE t (id INT, name VARCHAR(50))" );
            }
            try ( PreparedStatement ps = conn.prepareStatement( "INSERT INTO t (id, name) VALUES (?, ?)" ) ) {
                SqlBinder.positional( List.of( 7, "bob" ) ).bind( ps );
                ps.executeUpdate();
            }
            try ( java.sql.Statement s = conn.createStatement();
                  java.sql.ResultSet rs = s.executeQuery( "SELECT id, name FROM t" ) ) {
                assertTrue( rs.next() );
                assertTrue( rs.getInt( 1 ) == 7 && "bob".equals( rs.getString( 2 ) ) );
            }
        }
    }
}
