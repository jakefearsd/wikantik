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

import com.google.gson.Gson;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import javax.sql.DataSource;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Append-only audit trail for admin-UI driven kg_edges mutations.
 *
 * <p>See V028__kg_edge_audit.sql for the schema. Audit insert failures
 * log-and-continue per the design's fidelity-not-correctness rule
 * (the user-facing mutation must succeed even if the audit row cannot
 * be written).</p>
 *
 * @since 1.0
 */
public final class KgEdgeAuditRepository {

    private static final Logger LOG = LogManager.getLogger( KgEdgeAuditRepository.class );
    private static final Gson GSON = new Gson();

    private final DataSource dataSource;

    public KgEdgeAuditRepository( final DataSource dataSource ) {
        this.dataSource = dataSource;
    }

    public void insert( final UUID edgeId, final String action,
                        final Map< String, Object > before, final Map< String, Object > after,
                        final String actor, final String reason ) {
        final String sql = "INSERT INTO kg_edge_audit ( edge_id, action, before, after, actor, reason ) "
                         + "VALUES ( ?, ?, ?::jsonb, ?::jsonb, ?, ? )";
        try ( Connection conn = dataSource.getConnection();
              PreparedStatement ps = conn.prepareStatement( sql ) ) {
            ps.setObject( 1, edgeId );
            ps.setString( 2, action );
            ps.setString( 3, before == null ? null : GSON.toJson( before ) );
            ps.setString( 4, after  == null ? null : GSON.toJson( after ) );
            ps.setString( 5, actor );
            ps.setString( 6, reason );
            ps.executeUpdate();
        } catch ( final SQLException e ) {
            LOG.warn( "Failed to insert kg_edge_audit (edge={}, action={}): {}",
                    edgeId, action, e.getMessage(), e );
            // Audit is a fidelity surface, not correctness — never propagate.
        }
    }

    public List< Map< String, Object > > findByEdgeId( final UUID edgeId, final int limit ) {
        final String sql = "SELECT id, edge_id, action, before, after, actor, reason, created "
                         + "FROM kg_edge_audit WHERE edge_id = ? ORDER BY created DESC LIMIT ?";
        final List< Map< String, Object > > rows = new ArrayList<>();
        try ( Connection conn = dataSource.getConnection();
              PreparedStatement ps = conn.prepareStatement( sql ) ) {
            ps.setObject( 1, edgeId );
            ps.setInt( 2, limit );
            try ( ResultSet rs = ps.executeQuery() ) {
                while ( rs.next() ) {
                    final Map< String, Object > m = new LinkedHashMap<>();
                    m.put( "id", rs.getObject( "id", UUID.class ).toString() );
                    m.put( "edge_id", rs.getObject( "edge_id", UUID.class ).toString() );
                    m.put( "action", rs.getString( "action" ) );
                    m.put( "before", rs.getString( "before" ) );  // raw JSON string; caller decides
                    m.put( "after",  rs.getString( "after"  ) );
                    m.put( "actor", rs.getString( "actor" ) );
                    m.put( "reason", rs.getString( "reason" ) );
                    final Timestamp ts = rs.getTimestamp( "created" );
                    m.put( "created", ts != null ? ts.toInstant().toString() : null );
                    rows.add( m );
                }
            }
        } catch ( final SQLException e ) {
            LOG.warn( "findByEdgeId({}) failed: {}", edgeId, e.getMessage(), e );
            throw new RuntimeException( "findByEdgeId failed", e );
        }
        return rows;
    }
}
