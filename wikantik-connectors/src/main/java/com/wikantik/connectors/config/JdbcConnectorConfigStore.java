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

import javax.sql.DataSource;
import java.sql.*;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

/** PostgreSQL/H2 JDBC store for admin-managed connector configurations. */
public final class JdbcConnectorConfigStore {

    private final DataSource ds;

    public JdbcConnectorConfigStore( final DataSource ds ) {
        this.ds = ds;
    }

    public List< ConnectorConfigRow > list() {
        final List< ConnectorConfigRow > out = new ArrayList<>();
        try ( var c = ds.getConnection(); var ps = c.prepareStatement(
                "SELECT connector_id, connector_type, enabled, sync_interval_hours, config, cluster,"
                + " default_tags, page_prefix FROM connector_configs ORDER BY connector_id" ) ) {
            try ( var rs = ps.executeQuery() ) {
                while ( rs.next() ) out.add( rowFrom( rs ) );
            }
        } catch ( final SQLException e ) {
            throw new RuntimeException( "connector_configs list failed: " + e.getMessage(), e );
        }
        return out;
    }

    public Optional< ConnectorConfigRow > get( final String id ) {
        try ( var c = ds.getConnection(); var ps = c.prepareStatement(
                "SELECT connector_id, connector_type, enabled, sync_interval_hours, config, cluster,"
                + " default_tags, page_prefix FROM connector_configs WHERE connector_id=?" ) ) {
            ps.setString( 1, id );
            try ( var rs = ps.executeQuery() ) {
                if ( rs.next() ) return Optional.of( rowFrom( rs ) );
            }
        } catch ( final SQLException e ) {
            throw new RuntimeException( "connector_configs get failed for '" + id + "': " + e.getMessage(), e );
        }
        return Optional.empty();
    }

    public void upsert( final ConnectorConfigRow row ) {
        // Portable upsert: UPDATE first, INSERT only if no row existed. H2 does not support ON CONFLICT ... DO UPDATE.
        try ( var c = ds.getConnection() ) {
            int updated;
            try ( var ps = c.prepareStatement(
                    "UPDATE connector_configs SET connector_type=?, enabled=?, sync_interval_hours=?,"
                    + " config=?, cluster=?, default_tags=?, page_prefix=?, modified=now() WHERE connector_id=?" ) ) {
                ps.setString( 1, row.connectorType() );
                ps.setBoolean( 2, row.enabled() );
                ps.setInt( 3, row.syncIntervalHours() );
                ps.setString( 4, row.configJson() );
                ps.setString( 5, row.cluster() );
                ps.setString( 6, row.defaultTags() );
                ps.setString( 7, row.pagePrefix() );
                ps.setString( 8, row.connectorId() );
                updated = ps.executeUpdate();
            }
            if ( updated == 0 ) {
                try ( var ps = c.prepareStatement(
                        "INSERT INTO connector_configs (connector_id, connector_type, enabled, sync_interval_hours,"
                        + " config, cluster, default_tags, page_prefix) VALUES (?,?,?,?,?,?,?,?)" ) ) {
                    ps.setString( 1, row.connectorId() );
                    ps.setString( 2, row.connectorType() );
                    ps.setBoolean( 3, row.enabled() );
                    ps.setInt( 4, row.syncIntervalHours() );
                    ps.setString( 5, row.configJson() );
                    ps.setString( 6, row.cluster() );
                    ps.setString( 7, row.defaultTags() );
                    ps.setString( 8, row.pagePrefix() );
                    ps.executeUpdate();
                }
            }
        } catch ( final SQLException e ) {
            throw new RuntimeException( "connector_configs upsert failed for '" + row.connectorId() + "': " + e.getMessage(), e );
        }
    }

    public void delete( final String id ) {
        try ( var c = ds.getConnection(); var ps = c.prepareStatement(
                "DELETE FROM connector_configs WHERE connector_id=?" ) ) {
            ps.setString( 1, id );
            ps.executeUpdate();
        } catch ( final SQLException e ) {
            throw new RuntimeException( "connector_configs delete failed for '" + id + "': " + e.getMessage(), e );
        }
    }

    private static ConnectorConfigRow rowFrom( final ResultSet rs ) throws SQLException {
        return new ConnectorConfigRow( rs.getString( 1 ), rs.getString( 2 ), rs.getBoolean( 3 ),
            rs.getInt( 4 ), rs.getString( 5 ), rs.getString( 6 ), rs.getString( 7 ), rs.getString( 8 ) );
    }
}
