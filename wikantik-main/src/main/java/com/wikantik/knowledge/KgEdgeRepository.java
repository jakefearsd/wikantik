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

    /**
     * Endpoint-kind filter for Edge Explorer queries. The Knowledge Graph stores both
     * wiki-page nodes (node_type ∈ article/hub/implementation-plan/…) and LLM-extracted
     * concept nodes (node_type = 'concept'). The admin UI lets operators filter the
     * edge list to view only one category or the union (default).
     *
     * <p>{@code "page"}   — both endpoints are wiki-page-like (node_type != 'concept').<br>
     * {@code "entity"} — both endpoints are LLM-extracted concepts (node_type = 'concept').<br>
     * any other / null — no filter; the union is returned.</p>
     *
     * <p>Mixed-endpoint edges (one page, one concept) only appear in the no-filter
     * view; "page" and "entity" filters require <em>both</em> endpoints to match.
     * Nodes with NULL {@code node_type} (legacy/bugged data) are excluded from both
     * the "page" and "entity" filters by the SQL semantics of {@code != 'concept'}.</p>
     */
    /**
     * Appends the {@code AND ...} filter clauses for {@code kg_edges} to {@code sql}
     * (relationship_type, name LIKE, endpointKind) and binds the corresponding params
     * to {@code params}. Shared between {@link #queryEdgesWithNames},
     * {@link #countEdgesWithFilter}, and {@link #bulkDeleteByFilter} so the three SQL
     * shapes cannot drift.
     */
    private static void appendEdgeFilter( final StringBuilder sql,
                                           final List< Object > params,
                                           final String relationshipType,
                                           final String searchName,
                                           final String endpointKind ) {
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
        sql.append( endpointKindClause( endpointKind ) );
    }

    private static String endpointKindClause( final String endpointKind ) {
        if ( endpointKind == null || endpointKind.isBlank() ) return "";
        return switch ( endpointKind ) {
            case "page"   -> " AND sn.node_type IS NOT NULL AND sn.node_type != 'concept'"
                           + " AND tn.node_type IS NOT NULL AND tn.node_type != 'concept'";
            case "entity" -> " AND sn.node_type = 'concept' AND tn.node_type = 'concept'";
            default       -> "";
        };
    }

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

    /**
     * Code-level guard against mixed page/entity edges, established 2026-05-11 after
     * the one-shot deletion of 84 such edges left from earlier extractor flows. The
     * Knowledge Graph stores both wiki-page nodes (node_type != 'concept') and
     * LLM-extracted concept nodes (node_type = 'concept'); edges spanning the two
     * categories had no demonstrated downstream utility and were almost entirely
     * generic {@code related_to} noise from frontmatter resolvers picking the wrong
     * node when both kinds shared a name. This guard prevents recurrence at the
     * write boundary.
     *
     * @return {@code true} iff the two endpoints straddle the page/entity boundary
     */
    private boolean isMixedEdgeEndpoints( final UUID sourceId, final UUID targetId ) {
        final String sql = "SELECT sn.node_type AS s_type, tn.node_type AS t_type "
                         + "FROM kg_nodes sn, kg_nodes tn WHERE sn.id = ? AND tn.id = ?";
        try ( Connection conn = dataSource.getConnection();
              PreparedStatement ps = conn.prepareStatement( sql ) ) {
            ps.setObject( 1, sourceId );
            ps.setObject( 2, targetId );
            try ( ResultSet rs = ps.executeQuery() ) {
                if ( !rs.next() ) return false; // one node missing — FK will fail downstream
                final String sType = rs.getString( "s_type" );
                final String tType = rs.getString( "t_type" );
                // Mixed iff exactly one side is 'concept'. NULL node_types count as page-like.
                return ( "concept".equals( sType ) ) != ( "concept".equals( tType ) );
            }
        } catch ( final SQLException e ) {
            LOG.warn( "isMixedEdgeEndpoints lookup failed for {}->{}: {}",
                sourceId, targetId, e.getMessage(), e );
            return false; // fail-open: let the insert proceed and surface real errors downstream
        }
    }

    /**
     * Logs a stack-tracing WARN when the guard rejects a mixed edge. The {@code Throwable}
     * is not thrown — it exists solely to capture the call stack so operators can
     * trace the offending flow in the catalina logs.
     */
    private void warnMixedEdgeRejected( final UUID sourceId, final UUID targetId,
                                         final String relationshipType ) {
        LOG.warn( "Rejected mixed page/entity edge {}->{} [{}]: writes that cross the "
                + "page/entity boundary are disallowed since 2026-05-11. Fix the calling "
                + "flow to use homogeneous endpoints. Stack trace follows.",
            sourceId, targetId, relationshipType,
            new Throwable( "mixed-edge guard fired" ) );
    }

    public KgEdge upsertEdge( final UUID sourceId, final UUID targetId, final String relationshipType,
                              final Provenance provenance, final Map< String, Object > properties ) {
        if ( isMixedEdgeEndpoints( sourceId, targetId ) ) {
            warnMixedEdgeRejected( sourceId, targetId, relationshipType );
            return null;
        }
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
        if ( isMixedEdgeEndpoints( sourceId, targetId ) ) {
            warnMixedEdgeRejected( sourceId, targetId, relationshipType );
            return;
        }
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
        return queryEdgesWithNames( relationshipType, searchName, null, limit, offset );
    }

    public List< Map< String, Object > > queryEdgesWithNames( final String relationshipType,
                                                               final String searchName,
                                                               final String endpointKind,
                                                               final int limit, final int offset ) {
        final StringBuilder sql = new StringBuilder(
                "SELECT e.*, sn.name AS source_name, tn.name AS target_name "
              + "FROM kg_edges e "
              + "JOIN kg_nodes sn ON e.source_id = sn.id "
              + "JOIN kg_nodes tn ON e.target_id = tn.id"
              + KgInclusionFilter.EDGE_FILTER_JOIN
              + "WHERE" + KgInclusionFilter.EDGE_FILTER_WHERE );
        final List< Object > params = new ArrayList<>();
        appendEdgeFilter( sql, params, relationshipType, searchName, endpointKind );
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

    /**
     * In-place elevation of an existing edge to human-curated status: sets
     * {@code tier = 'human'} and {@code provenance = 'human-curated'}. Used by
     * the admin Edge Explorer's "Confirm" action — a curator endorsing a
     * machine-proposed edge without otherwise editing it. Idempotent: if the
     * edge is already at this state, the UPDATE is still issued but no-op
     * (the row's modified timestamp does refresh).
     *
     * @param id edge id
     * @return the updated edge, or {@code null} if {@code id} doesn't exist
     */
    public KgEdge elevateToHumanCurated( final UUID id ) {
        if ( id == null ) return null;
        final String sql = "UPDATE kg_edges SET tier = 'human', provenance = ?, modified = NOW() "
                         + "WHERE id = ?";
        try ( Connection conn = dataSource.getConnection();
              PreparedStatement ps = conn.prepareStatement( sql ) ) {
            ps.setString( 1, Provenance.HUMAN_CURATED.value() );
            ps.setObject( 2, id );
            final int rows = ps.executeUpdate();
            if ( rows == 0 ) return null;
            return findById( id );
        } catch ( final SQLException e ) {
            LOG.warn( "elevateToHumanCurated({}) failed: {}", id, e.getMessage(), e );
            throw new RuntimeException( "elevateToHumanCurated failed", e );
        }
    }

    public KgEdge findById( final UUID id ) {
        final String sql = "SELECT * FROM kg_edges WHERE id = ?";
        try ( Connection conn = dataSource.getConnection();
              PreparedStatement ps = conn.prepareStatement( sql ) ) {
            ps.setObject( 1, id );
            try ( ResultSet rs = ps.executeQuery() ) {
                return rs.next() ? mapEdge( rs ) : null;
            }
        } catch ( final SQLException e ) {
            LOG.warn( "findById({}) failed: {}", id, e.getMessage(), e );
            throw new RuntimeException( "findById failed", e );
        }
    }

    public long countEdgesWithFilter( final String relationshipType, final String searchName ) {
        return countEdgesWithFilter( relationshipType, searchName, null );
    }

    public long countEdgesWithFilter( final String relationshipType, final String searchName,
                                       final String endpointKind ) {
        final StringBuilder sql = new StringBuilder(
                "SELECT COUNT(*) FROM kg_edges e "
              + "JOIN kg_nodes sn ON e.source_id = sn.id "
              + "JOIN kg_nodes tn ON e.target_id = tn.id"
              + KgInclusionFilter.EDGE_FILTER_JOIN
              + "WHERE" + KgInclusionFilter.EDGE_FILTER_WHERE );
        final List< Object > params = new ArrayList<>();
        appendEdgeFilter( sql, params, relationshipType, searchName, endpointKind );

        try ( Connection conn = dataSource.getConnection();
              PreparedStatement ps = conn.prepareStatement( sql.toString() ) ) {
            for ( int i = 0; i < params.size(); i++ ) ps.setObject( i + 1, params.get( i ) );
            try ( ResultSet rs = ps.executeQuery() ) {
                return rs.next() ? rs.getLong( 1 ) : 0L;
            }
        } catch ( final SQLException e ) {
            LOG.warn( "countEdgesWithFilter failed: {}", e.getMessage(), e );
            throw new RuntimeException( e );
        }
    }

    /**
     * Deletes all edges matching the same filter that {@link #queryEdgesWithNames}
     * applies (relationship_type, name search). Runs in a single statement; the
     * inner SELECT is wrapped to share the JOIN/WHERE machinery without an INNER
     * subquery alias that PostgreSQL would otherwise need.
     *
     * @return number of rows deleted
     */
    public int bulkDeleteByFilter( final String relationshipType, final String searchName ) {
        return bulkDeleteByFilter( relationshipType, searchName, null );
    }

    public int bulkDeleteByFilter( final String relationshipType, final String searchName,
                                    final String endpointKind ) {
        final StringBuilder sql = new StringBuilder(
                "DELETE FROM kg_edges WHERE id IN ("
              + " SELECT e.id FROM kg_edges e "
              + " JOIN kg_nodes sn ON e.source_id = sn.id "
              + " JOIN kg_nodes tn ON e.target_id = tn.id"
              + KgInclusionFilter.EDGE_FILTER_JOIN
              + " WHERE" + KgInclusionFilter.EDGE_FILTER_WHERE );
        final List< Object > params = new ArrayList<>();
        appendEdgeFilter( sql, params, relationshipType, searchName, endpointKind );
        sql.append( " )" );

        try ( Connection conn = dataSource.getConnection();
              PreparedStatement ps = conn.prepareStatement( sql.toString() ) ) {
            for ( int i = 0; i < params.size(); i++ ) ps.setObject( i + 1, params.get( i ) );
            return ps.executeUpdate();
        } catch ( final SQLException e ) {
            LOG.warn( "bulkDeleteByFilter failed: {}", e.getMessage(), e );
            throw new RuntimeException( "bulkDeleteByFilter failed: " + e.getMessage(), e );
        }
    }

    /**
     * In one transaction: looks up source/target node names from the edge, deletes
     * the edge, and inserts a kg_rejections row with the same triple. The rejection
     * insert uses ON CONFLICT DO NOTHING so re-rejection is a safe no-op.
     */
    public void deleteEdgeAndRecordRejection( final UUID edgeId, final String actor, final String reason ) {
        final String lookupSql = "SELECT sn.name AS s_name, tn.name AS t_name, e.relationship_type "
                               + "FROM kg_edges e "
                               + "JOIN kg_nodes sn ON e.source_id = sn.id "
                               + "JOIN kg_nodes tn ON e.target_id = tn.id "
                               + "WHERE e.id = ?";
        final String deleteSql = "DELETE FROM kg_edges WHERE id = ?";
        final String rejectSql = "INSERT INTO kg_rejections "
                               + "( proposed_source, proposed_target, proposed_relationship, rejected_by, reason ) "
                               + "VALUES ( ?, ?, ?, ?, ? ) "
                               + "ON CONFLICT ( proposed_source, proposed_target, proposed_relationship ) DO NOTHING";

        try ( Connection conn = dataSource.getConnection() ) {
            conn.setAutoCommit( false );
            try {
                final String sourceName, targetName, relType;
                try ( PreparedStatement ps = conn.prepareStatement( lookupSql ) ) {
                    ps.setObject( 1, edgeId );
                    try ( ResultSet rs = ps.executeQuery() ) {
                        if ( !rs.next() ) {
                            conn.rollback();
                            throw new IllegalArgumentException( "Edge not found: " + edgeId );
                        }
                        sourceName = rs.getString( "s_name" );
                        targetName = rs.getString( "t_name" );
                        relType    = rs.getString( "relationship_type" );
                    }
                }
                try ( PreparedStatement ps = conn.prepareStatement( deleteSql ) ) {
                    ps.setObject( 1, edgeId );
                    ps.executeUpdate();
                }
                try ( PreparedStatement ps = conn.prepareStatement( rejectSql ) ) {
                    ps.setString( 1, sourceName );
                    ps.setString( 2, targetName );
                    ps.setString( 3, relType );
                    ps.setString( 4, actor );
                    ps.setString( 5, reason );
                    ps.executeUpdate();
                }
                conn.commit();
            } catch ( final SQLException inner ) {
                conn.rollback();
                LOG.warn( "deleteEdgeAndRecordRejection({}) rolled back: {}",
                        edgeId, inner.getMessage(), inner );
                throw new RuntimeException( "deleteEdgeAndRecordRejection failed", inner );
            } finally {
                conn.setAutoCommit( true );
            }
        } catch ( final SQLException e ) {
            LOG.warn( "deleteEdgeAndRecordRejection({}) failed: {}", edgeId, e.getMessage(), e );
            throw new RuntimeException( "deleteEdgeAndRecordRejection failed", e );
        }
    }
}
