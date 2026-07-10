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
package com.wikantik.knowledge.eval;

import org.junit.jupiter.api.Test;

import javax.sql.DataSource;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.SQLException;

import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class BundleEvalRunDaoTest {

    private static BundleEvalRun run() {
        return new BundleEvalRun( "2.3.6", 0.74, 0.10, 0.70, 0.80, 0.72, 42, false );
    }

    @Test
    void insert_bindsAllColumnsAndExecutes() throws Exception {
        final PreparedStatement ps = mock( PreparedStatement.class );
        final Connection conn = mock( Connection.class );
        final DataSource ds = mock( DataSource.class );
        when( ds.getConnection() ).thenReturn( conn );
        when( conn.prepareStatement( anyString() ) ).thenReturn( ps );

        new BundleEvalRunDao( ds ).insert( run() );

        verify( ps ).setString( 1, "2.3.6" );
        verify( ps ).setDouble( 2, 0.74 );   // overall_recall
        verify( ps ).setDouble( 3, 0.10 );   // overall_precision
        verify( ps ).setDouble( 4, 0.70 );   // recall_similarity
        verify( ps ).setDouble( 5, 0.80 );   // recall_relational
        verify( ps ).setDouble( 6, 0.72 );   // recall_boundary
        verify( ps ).setInt( 7, 42 );
        verify( ps ).setBoolean( 8, false );
        verify( ps ).executeUpdate();
    }

    @Test
    void insert_swallowsSqlException_neverThrows() throws Exception {
        final DataSource ds = mock( DataSource.class );
        when( ds.getConnection() ).thenThrow( new SQLException( "db down" ) );
        // must not throw
        new BundleEvalRunDao( ds ).insert( run() );
    }

    @Test
    void insert_nullRun_isNoOp() throws Exception {
        final DataSource ds = mock( DataSource.class );
        new BundleEvalRunDao( ds ).insert( null );
        verify( ds, never() ).getConnection();
    }
}
