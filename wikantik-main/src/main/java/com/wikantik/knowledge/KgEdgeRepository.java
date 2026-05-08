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

import com.wikantik.api.knowledge.*;
import com.wikantik.kgpolicy.KgInclusionFilter;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import javax.sql.DataSource;
import java.sql.*;
import java.util.*;

/**
 * JDBC repository for {@code kg_edges} table operations.
 *
 * <p>Extracted from the former JdbcKnowledgeRepository facade in Phase 3 Checkpoint 3 of the
 * wikantik-main subsystem decomposition. SQL strings are verbatim copies; no behaviour
 * change is introduced.</p>
 *
 * @since 1.0
 */
public final class KgEdgeRepository extends KgJdbcSupport {

    private static final Logger LOG = LogManager.getLogger( KgEdgeRepository.class );

    public KgEdgeRepository( final DataSource dataSource ) {
        super( dataSource );
    }

    @Override
    protected Logger log() { return LOG; }

    private KgEdge mapEdge( final ResultSet rs ) throws SQLException {
        return new KgEdge(
            rs.getObject( "id", UUID.class ),
            rs.getObject( "source_id", UUID.class ),
            rs.getObject( "target_id", UUID.class ),
            rs.getString( "relationship_type" ),
            Provenance.fromValue( rs.getString( "provenance" ) ),
            parseJson( rs.getString( "properties" ) ),
            toInstant( rs.getTimestamp( "created" ) ),
            toInstant( rs.getTimestamp( "modified" ) ),
            rs.getString( "tier" ),
            rs.getObject( "provenance_proposal_id", UUID.class )
        );
    }

    // ---- Edge operations ----

    public KgEdge upsertEdge( final UUID sourceId, final UUID targetId, final String relationshipType,
                              final Provenance provenance, final Map< String, Object > properties ) {
        final String propsJson = GSON.toJson( properties != null ? properties : Map.of() );
        final String sql = "INSERT INTO kg_edges ( source_id, target_id, relationship_type, provenance, properties, tier, provenance_proposal_id, modified ) "
                + "VALUES ( ?, ?, ?, ?, ?::jsonb, 'human', NULL, CURRENT_TIMESTAMP ) "
                + "ON CONFLICT ( source_id, target_id, relationship_type ) DO UPDATE SET "
                + "provenance = EXCLUDED.provenance, properties = EXCLUDED.properties, "
                + "modified = CURRENT_TIMESTAMP";
        try ( Connection conn = dataSource.getConnection();
              PreparedStatement ps = conn.prepareStatement( sql ) ) {
            ps.setObject( 1, sourceId );
            ps.setObject( 2, targetId );
            ps.setString( 3, relationshipType );
            ps.setString( 4, provenance.value() );
            ps.setString( 5, propsJson );
            ps.executeUpdate();
        } catch ( final SQLException e ) {
            LOG.warn( "Failed to upsert edge {}->{} [{}]: {}", sourceId, targetId, relationshipType, e.getMessage(), e );
            throw new RuntimeException( "Failed to upsert edge: " + e.getMessage(), e );
        }

        // Re-query to return the upserted edge
        final String selectSql = "SELECT * FROM kg_edges WHERE source_id = ? AND target_id = ? AND relationship_type = ?";
        try ( Connection conn = dataSource.getConnection();
              PreparedStatement ps = conn.prepareStatement( selectSql ) ) {
            ps.setObject( 1, sourceId );
            ps.setObject( 2, targetId );
            ps.setString( 3, relationshipType );
            try ( ResultSet rs = ps.executeQuery() ) {
                return rs.next() ? mapEdge( rs ) : null;
            }
        } catch ( final SQLException e ) {
            LOG.warn( "Failed to read back upserted edge: {}", e.getMessage(), e );
            throw new RuntimeException( e );
        }
    }

    public void upsertEdgeWithProvenance( final UUID sourceId, final UUID targetId,
                                          final String relationshipType, final Provenance provenance,
                                          final Map< String, Object > properties,
                                          final String tier, final UUID provenanceProposalId ) {
        final String propsJson = GSON.toJson( properties != null ? properties : Map.of() );
        final String sql = "INSERT INTO kg_edges ( source_id, target_id, relationship_type, provenance, " +
            "properties, tier, provenance_proposal_id, modified ) " +
            "VALUES ( ?, ?, ?, ?, ?::jsonb, ?, ?, CURRENT_TIMESTAMP ) " +
            "ON CONFLICT ( source_id, target_id, relationship_type ) DO UPDATE SET " +
            "provenance = EXCLUDED.provenance, properties = EXCLUDED.properties, " +
            "modified = CURRENT_TIMESTAMP";
        try ( Connection conn = dataSource.getConnection();
              PreparedStatement ps = conn.prepareStatement( sql ) ) {
            ps.setObject( 1, sourceId );
            ps.setObject( 2, targetId );
            ps.setString( 3, relationshipType );
            ps.setString( 4, provenance.value() );
            ps.setString( 5, propsJson );
            ps.setString( 6, tier );
            if ( provenanceProposalId == null ) ps.setNull( 7, java.sql.Types.OTHER );
            else ps.setObject( 7, provenanceProposalId );
            ps.executeUpdate();
        } catch ( final SQLException e ) {
            LOG.warn( "Failed to upsertEdgeWithProvenance ({},{},{}): {}",
                sourceId, targetId, relationshipType, e.getMessage(), e );
            throw new RuntimeException( "Failed to upsertEdgeWithProvenance: " + e.getMessage(), e );
        }
    }

    public void deleteEdge( final UUID id ) {
        final String sql = "DELETE FROM kg_edges WHERE id = ?";
        try ( Connection conn = dataSource.getConnection();
              PreparedStatement ps = conn.prepareStatement( sql ) ) {
            ps.setObject( 1, id );
            ps.executeUpdate();
        } catch ( final SQLException e ) {
            LOG.warn( "Failed to delete edge '{}': {}", id, e.getMessage(), e );
            throw new RuntimeException( "Failed to delete edge: " + e.getMessage(), e );
        }
    }

    public int deleteEdgesByProvenance( final UUID proposalId ) {
        return executeDelete( "DELETE FROM kg_edges WHERE provenance_proposal_id = ?", proposalId );
    }

    public List< KgEdge > getAllEdges() {
        final String sql = "SELECT e.* FROM kg_edges e"
                + KgInclusionFilter.EDGE_FILTER_JOIN
                + "WHERE" + KgInclusionFilter.EDGE_FILTER_WHERE
                + "ORDER BY e.id";
        final List< KgEdge > results = new ArrayList<>();
        try ( Connection conn = dataSource.getConnection();
              PreparedStatement ps = conn.prepareStatement( sql );
              ResultSet rs = ps.executeQuery() ) {
            while ( rs.next() ) results.add( mapEdge( rs ) );
        } catch ( final SQLException e ) {
            LOG.warn( "Failed to get all edges: {}", e.getMessage(), e );
            throw new RuntimeException( e );
        }
        return results;
    }

    public List< KgEdge > getAllEdges( final Tier minTier ) {
        final String sql = "SELECT * FROM kg_edges WHERE tier = ANY( ? )";
        try ( Connection conn = dataSource.getConnection();
              PreparedStatement ps = conn.prepareStatement( sql ) ) {
            ps.setArray( 1, conn.createArrayOf( "varchar", minTier.includedTiers().toArray() ) );
            try ( ResultSet rs = ps.executeQuery() ) {
                final List< KgEdge > out = new ArrayList<>();
                while ( rs.next() ) out.add( mapEdge( rs ) );
                return out;
            }
        } catch ( final SQLException e ) {
            LOG.warn( "getAllEdges({}) failed: {}", minTier.wireName(), e.getMessage(), e );
            throw new RuntimeException( "getAllEdges failed: " + e.getMessage(), e );
        }
    }

    public List< KgEdge > getEdgesForNode( final UUID nodeId, final String direction ) {
        final String filterJoin = KgInclusionFilter.EDGE_FILTER_JOIN;
        final String filterWhere = KgInclusionFilter.EDGE_FILTER_WHERE;
        final String sql = switch ( direction ) {
            case "outbound" ->
                "SELECT e.* FROM kg_edges e" + filterJoin
                + "WHERE e.source_id = ? AND" + filterWhere;
            case "inbound" ->
                "SELECT e.* FROM kg_edges e" + filterJoin
                + "WHERE e.target_id = ? AND" + filterWhere;
            case "both" ->
                "SELECT e.* FROM kg_edges e" + filterJoin
                + "WHERE ( e.source_id = ? OR e.target_id = ? ) AND" + filterWhere;
            default -> throw new IllegalArgumentException( "Invalid direction: " + direction );
        };

        final List< KgEdge > results = new ArrayList<>();
        try ( Connection conn = dataSource.getConnection();
              PreparedStatement ps = conn.prepareStatement( sql ) ) {
            ps.setObject( 1, nodeId );
            if ( "both".equals( direction ) ) ps.setObject( 2, nodeId );
            try ( ResultSet rs = ps.executeQuery() ) {
                while ( rs.next() ) results.add( mapEdge( rs ) );
            }
        } catch ( final SQLException e ) {
            LOG.warn( "Failed to get edges for node '{}': {}", nodeId, e.getMessage(), e );
            throw new RuntimeException( e );
        }
        return results;
    }

    public void diffAndRemoveStaleEdges( final UUID sourceId,
                                         final Set< Map.Entry< String, String > > currentEdges ) {
        final String sql = "SELECT e.*, n.name AS target_name "
                         + "FROM kg_edges e JOIN kg_nodes n ON e.target_id = n.id "
                         + "WHERE e.source_id = ?";
        try ( Connection conn = dataSource.getConnection();
              PreparedStatement ps = conn.prepareStatement( sql ) ) {
            ps.setObject( 1, sourceId );
            final List< UUID > toDelete = new ArrayList<>();
            try ( ResultSet rs = ps.executeQuery() ) {
                while ( rs.next() ) {
                    final String provValue = rs.getString( "provenance" );
                    final Provenance prov = Provenance.fromValue( provValue );
                    if ( prov != Provenance.HUMAN_AUTHORED ) continue;
                    final String targetName = rs.getString( "target_name" );
                    final String relType = rs.getString( "relationship_type" );
                    if ( !currentEdges.contains( Map.entry( targetName, relType ) ) ) {
                        toDelete.add( rs.getObject( "id", UUID.class ) );
                    }
                }
            }
            for ( final UUID edgeId : toDelete ) deleteEdge( edgeId );
        } catch ( final SQLException e ) {
            LOG.warn( "Failed to diff and remove stale edges for node '{}': {}", sourceId, e.getMessage(), e );
            throw new RuntimeException( e );
        }
    }

    public List< Map< String, Object > > queryEdgesWithNames( final String relationshipType,
                                                               final String searchName,
                                                               final int limit, final int offset ) {
        final StringBuilder sql = new StringBuilder(
                "SELECT e.*, sn.name AS source_name, tn.name AS target_name "
              + "FROM kg_edges e "
              + "JOIN kg_nodes sn ON e.source_id = sn.id "
              + "JOIN kg_nodes tn ON e.target_id = tn.id"
              + KgInclusionFilter.EDGE_FILTER_JOIN
              + "WHERE" + KgInclusionFilter.EDGE_FILTER_WHERE );
        final List< Object > params = new ArrayList<>();

        if ( relationshipType != null && !relationshipType.isBlank() ) {
            sql.append( " AND e.relationship_type = ?" );
            params.add( relationshipType );
        }
        if ( searchName != null && !searchName.isBlank() ) {
            sql.append( " AND ( LOWER( sn.name ) LIKE ? OR LOWER( tn.name ) LIKE ? )" );
            final String pattern = "%" + searchName.toLowerCase( Locale.ROOT ) + "%";
            params.add( pattern );
            params.add( pattern );
        }
        sql.append( " ORDER BY sn.name, e.relationship_type LIMIT ? OFFSET ?" );
        params.add( limit );
        params.add( offset );

        final List< Map< String, Object > > results = new ArrayList<>();
        try ( Connection conn = dataSource.getConnection();
              PreparedStatement ps = conn.prepareStatement( sql.toString() ) ) {
            for ( int i = 0; i < params.size(); i++ ) ps.setObject( i + 1, params.get( i ) );
            try ( ResultSet rs = ps.executeQuery() ) {
                while ( rs.next() ) {
                    final Map< String, Object > map = new LinkedHashMap<>();
                    map.put( "id", rs.getObject( "id", UUID.class ).toString() );
                    map.put( "source_id", rs.getObject( "source_id", UUID.class ).toString() );
                    map.put( "target_id", rs.getObject( "target_id", UUID.class ).toString() );
                    map.put( "source_name", rs.getString( "source_name" ) );
                    map.put( "target_name", rs.getString( "target_name" ) );
                    map.put( "relationship_type", rs.getString( "relationship_type" ) );
                    map.put( "provenance", rs.getString( "provenance" ) );
                    map.put( "properties", parseJson( rs.getString( "properties" ) ) );
                    final Timestamp created = rs.getTimestamp( "created" );
                    map.put( "created", created != null ? created.toInstant().toString() : null );
                    final Timestamp modified = rs.getTimestamp( "modified" );
                    map.put( "modified", modified != null ? modified.toInstant().toString() : null );
                    results.add( map );
                }
            }
        } catch ( final SQLException e ) {
            LOG.warn( "Failed to query edges with names: {}", e.getMessage(), e );
            throw new RuntimeException( e );
        }
        return results;
    }

    public List< String > getDistinctRelationshipTypes() {
        return queryDistinct( "SELECT DISTINCT relationship_type FROM kg_edges ORDER BY relationship_type" );
    }

    public long countEdges() {
        return queryCount( "SELECT COUNT(*) FROM kg_edges" );
    }
}
