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
package com.wikantik.connectors.config;

import com.wikantik.jdbc.Jdbc;
import com.wikantik.jdbc.SqlBinder;

import javax.sql.DataSource;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.List;
import java.util.Optional;

/** PostgreSQL JDBC store for admin-managed connector configurations. */
public final class JdbcConnectorConfigStore {

    private final Jdbc jdbc;

    public JdbcConnectorConfigStore( final DataSource ds ) {
        this.jdbc = new Jdbc( ds );
    }

    public List< ConnectorConfigRow > list() {
        try {
            return jdbc.query(
                "SELECT connector_id, connector_type, enabled, sync_interval_hours, config, cluster,"
                + " default_tags, page_prefix FROM connector_configs ORDER BY connector_id",
                SqlBinder.NONE, JdbcConnectorConfigStore::rowFrom );
        } catch ( final SQLException e ) {
            throw new RuntimeException( "connector_configs list failed: " + e.getMessage(), e );
        }
    }

    public Optional< ConnectorConfigRow > get( final String id ) {
        try {
            return jdbc.queryOne(
                "SELECT connector_id, connector_type, enabled, sync_interval_hours, config, cluster,"
                + " default_tags, page_prefix FROM connector_configs WHERE connector_id=?",
                ps -> ps.setString( 1, id ), JdbcConnectorConfigStore::rowFrom );
        } catch ( final SQLException e ) {
            throw new RuntimeException( "connector_configs get failed for '" + id + "': " + e.getMessage(), e );
        }
    }

    public void upsert( final ConnectorConfigRow row ) {
        // Portable upsert: UPDATE first, INSERT only if no row existed. H2 does not support ON CONFLICT ... DO UPDATE.
        try {
            final int updated = jdbc.update(
                "UPDATE connector_configs SET connector_type=?, enabled=?, sync_interval_hours=?,"
                + " config=?, cluster=?, default_tags=?, page_prefix=?, modified=now() WHERE connector_id=?",
                ps -> {
                    ps.setString( 1, row.connectorType() );
                    ps.setBoolean( 2, row.enabled() );
                    ps.setInt( 3, row.syncIntervalHours() );
                    ps.setString( 4, row.configJson() );
                    ps.setString( 5, row.cluster() );
                    ps.setString( 6, row.defaultTags() );
                    ps.setString( 7, row.pagePrefix() );
                    ps.setString( 8, row.connectorId() );
                } );
            if ( updated == 0 ) {
                jdbc.update(
                    "INSERT INTO connector_configs (connector_id, connector_type, enabled, sync_interval_hours,"
                    + " config, cluster, default_tags, page_prefix) VALUES (?,?,?,?,?,?,?,?)",
                    ps -> {
                        ps.setString( 1, row.connectorId() );
                        ps.setString( 2, row.connectorType() );
                        ps.setBoolean( 3, row.enabled() );
                        ps.setInt( 4, row.syncIntervalHours() );
                        ps.setString( 5, row.configJson() );
                        ps.setString( 6, row.cluster() );
                        ps.setString( 7, row.defaultTags() );
                        ps.setString( 8, row.pagePrefix() );
                    } );
            }
        } catch ( final SQLException e ) {
            throw new RuntimeException( "connector_configs upsert failed for '" + row.connectorId() + "': " + e.getMessage(), e );
        }
    }

    public void delete( final String id ) {
        try {
            jdbc.update( "DELETE FROM connector_configs WHERE connector_id=?", ps -> ps.setString( 1, id ) );
        } catch ( final SQLException e ) {
            throw new RuntimeException( "connector_configs delete failed for '" + id + "': " + e.getMessage(), e );
        }
    }

    private static ConnectorConfigRow rowFrom( final ResultSet rs ) throws SQLException {
        return new ConnectorConfigRow( rs.getString( 1 ), rs.getString( 2 ), rs.getBoolean( 3 ),
            rs.getInt( 4 ), rs.getString( 5 ), rs.getString( 6 ), rs.getString( 7 ), rs.getString( 8 ) );
    }
}
