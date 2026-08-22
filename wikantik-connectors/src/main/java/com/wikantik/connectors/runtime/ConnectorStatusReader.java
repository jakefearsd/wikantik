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
package com.wikantik.connectors.runtime;

import com.wikantik.jdbc.Jdbc;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import javax.sql.DataSource;
import java.sql.SQLException;
import java.sql.Timestamp;

/** Read-only status from the V046 sync-state tables. No schema change; no Phase-1 SPI change. */
public final class ConnectorStatusReader {

    private static final Logger LOG = LogManager.getLogger( ConnectorStatusReader.class );
    private final Jdbc jdbc;

    public ConnectorStatusReader( final DataSource ds ) { this.jdbc = new Jdbc( ds ); }

    public ConnectorStatus read( final String connectorId, final String connectorType ) {
        String lastRun = null, status = null;
        int count = 0;
        try {
            final var stateRow = jdbc.queryOne(
                "SELECT last_run, status FROM connector_sync_state WHERE connector_id=?",
                ps -> ps.setString( 1, connectorId ),
                rs -> {
                    final Timestamp ts = rs.getTimestamp( 1 );
                    return new String[] { ts == null ? null : ts.toInstant().toString(), rs.getString( 2 ) };
                } );
            if ( stateRow.isPresent() ) {
                lastRun = stateRow.get()[ 0 ];
                status = stateRow.get()[ 1 ];
            }
            count = jdbc.queryOne(
                "SELECT count(*) FROM connector_synced_item WHERE connector_id=?",
                ps -> ps.setString( 1, connectorId ), rs -> rs.getInt( 1 ) ).orElse( 0 );
        } catch ( final SQLException e ) {
            LOG.warn( "ConnectorStatusReader failed for '{}': {}", connectorId, e.getMessage() );
        }
        return new ConnectorStatus( connectorId, connectorType, lastRun, status, count );
    }
}
