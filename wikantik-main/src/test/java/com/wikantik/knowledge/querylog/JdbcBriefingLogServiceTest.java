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
package com.wikantik.knowledge.querylog;

import com.wikantik.api.briefing.BriefingLogEntry;
import org.junit.jupiter.api.Test;

import javax.sql.DataSource;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.SQLException;
import java.util.concurrent.Executor;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

class JdbcBriefingLogServiceTest {

    /** Same-thread executor so the fire-and-forget write is deterministic in tests. */
    private static final Executor INLINE = Runnable::run;

    @Test
    void log_insertsRowWithAllNineFields_whenEnabled() throws Exception {
        final DataSource ds = mock( DataSource.class );
        final Connection conn = mock( Connection.class );
        final PreparedStatement ps = mock( PreparedStatement.class );
        when( ds.getConnection() ).thenReturn( conn );
        when( conn.prepareStatement( anyString() ) ).thenReturn( ps );

        final BriefingLogEntry entry = new BriefingLogEntry(
            "PageA,PageB", "clusterX", true, 6000, 4200, 5, 2, 1, "api_briefing" );

        new JdbcBriefingLogService( ds, true, INLINE ).log( entry );

        verify( ps ).setString( 1, "PageA,PageB" );
        verify( ps ).setString( 2, "clusterX" );
        verify( ps ).setBoolean( 3, true );
        verify( ps ).setInt( 4, 6000 );
        verify( ps ).setInt( 5, 4200 );
        verify( ps ).setInt( 6, 5 );
        verify( ps ).setInt( 7, 2 );
        verify( ps ).setInt( 8, 1 );
        verify( ps ).setString( 9, "api_briefing" );
        verify( ps ).executeUpdate();
    }

    @Test
    void log_truncatesOversizedPinsAndClustersTo2000Chars() throws Exception {
        final DataSource ds = mock( DataSource.class );
        final Connection conn = mock( Connection.class );
        final PreparedStatement ps = mock( PreparedStatement.class );
        when( ds.getConnection() ).thenReturn( conn );
        when( conn.prepareStatement( anyString() ) ).thenReturn( ps );

        final String hugePins = "p".repeat( 5000 );
        final String hugeClusters = "c".repeat( 3000 );
        final BriefingLogEntry entry = new BriefingLogEntry(
            hugePins, hugeClusters, true, 6000, 4200, 5, 2, 1, "api_briefing" );

        new JdbcBriefingLogService( ds, true, INLINE ).log( entry );

        verify( ps ).setString( 1, "p".repeat( 2000 ) );
        verify( ps ).setString( 2, "c".repeat( 2000 ) );
    }

    @Test
    void log_isNoOp_whenDisabled() {
        final DataSource ds = mock( DataSource.class );
        final BriefingLogEntry entry = new BriefingLogEntry(
            null, null, false, 6000, 0, 0, 0, 0, "api_briefing" );

        new JdbcBriefingLogService( ds, false, INLINE ).log( entry );

        verifyNoInteractions( ds );
    }

    @Test
    void log_failsOpen_onSqlError() throws Exception {
        final DataSource broken = mock( DataSource.class );
        when( broken.getConnection() ).thenThrow( new SQLException( "no connection" ) );
        final BriefingLogEntry entry = new BriefingLogEntry(
            null, null, false, 6000, 0, 0, 0, 0, "api_briefing" );

        assertDoesNotThrow( () -> new JdbcBriefingLogService( broken, true, INLINE ).log( entry ),
            "a write failure must never propagate to the caller" );
    }

    @Test
    void log_isNoOp_whenEntryIsNull() {
        final DataSource ds = mock( DataSource.class );

        new JdbcBriefingLogService( ds, true, INLINE ).log( null );

        verifyNoInteractions( ds );
    }
}
