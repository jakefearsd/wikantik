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
import com.wikantik.jdbc.SqlBinder;
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
     * {@link #countEdgesWithFilter}, {@link #bulkDeleteByFilter}, and
     * {@link #findDistinctSourceIdsByFilter} so the four SQL shapes cannot drift.
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
     * the one-shot deletion of 84 such edges left from earlier extractor flows.  The
     * Knowledge Graph stores both wiki-page nodes (e.g. node_type ∈ article/hub/…
     * — types NOT in {@link EntityTypeVocabulary#ENTITY_CLASS_SET}) and knowledge-entity
     * nodes (node_type ∈ the 9-class vocabulary: person/organization/place/event/product/
     * technology/concept/project/version).  Edges spanning the two categories had no
     * demonstrated downstream utility and were almost entirely generic {@code related_to}
     * noise from frontmatter resolvers picking the wrong node when both kinds shared a name.
     * This guard prevents recurrence at the write boundary.
     *
     * <p><b>Evolution note:</b> the original guard used {@code "concept".equals(type)} as
     * the entity-class test, which was correct when {@code concept} was the sole entity type.
     * After the vocabulary expanded to 9 types (2026-04 harmonisation) the test became stale:
     * it incorrectly treated {@code technology}, {@code person}, etc. as page-like, causing
     * conformant entity→entity edges to be rejected.  The predicate now delegates to
     * {@link EntityTypeVocabulary#ENTITY_CLASS_SET} so the guard stays aligned with the
     * canonical vocabulary without a code change each time the vocab evolves.</p>
     *
     * @return {@code true} iff the two endpoints straddle the page/entity boundary
     */
    private boolean isMixedEdgeEndpoints( final UUID sourceId, final UUID targetId ) {
        final String sql = "SELECT sn.node_type AS s_type, tn.node_type AS t_type "
                         + "FROM kg_nodes sn, kg_nodes tn WHERE sn.id = ? AND tn.id = ?";
        try {
            // Mixed iff exactly one side is a known entity type. NULL counts as page-like.
            // Empty Optional (one node missing) — FK will fail downstream — maps to false,
            // same as the original "!rs.next() -> return false" short-circuit.
            return queryOne( sql, ps -> {
                ps.setObject( 1, sourceId );
                ps.setObject( 2, targetId );
            }, rs -> {
                final String sType = rs.getString( "s_type" );
                final String tType = rs.getString( "t_type" );
                final boolean sIsEntity = sType != null
                        && EntityTypeVocabulary.ENTITY_CLASS_SET.contains( sType );
                final boolean tIsEntity = tType != null
                        && EntityTypeVocabulary.ENTITY_CLASS_SET.contains( tType );
                return sIsEntity != tIsEntity;
            } ).orElse( false );
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
    /**
     * Builds the agent-facing message for a {@code kg_edges_relationship_type_check}
     * CHECK-constraint violation. Includes the offending value, the top-3 nearest
     * matches by Levenshtein distance, and the full closed vocabulary so the agent
     * has both a quick hint and an authoritative list. Called from the SQLState 23514
     * catch blocks of {@link #upsertEdge} and {@link #upsertEdgeWithProvenance}.
     */
    private static String relationshipTypeViolationMessage( final String relationshipType ) {
        final java.util.List< String > suggestions =
            com.wikantik.api.knowledge.RelationshipTypeVocabulary.closestMatches( relationshipType, 3 );
        final String suggestionPart = suggestions.isEmpty()
            ? ""
            : " Did you mean: " + String.join( ", ", suggestions ) + "?";
        return "Relationship type '" + relationshipType + "' is not in the closed vocabulary."
            + suggestionPart
            + " Allowed: "
            + String.join( ", ", com.wikantik.api.knowledge.RelationshipTypeVocabulary.CLOSED_VOCAB ) + ".";
    }

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
        try {
            update( sql, ps -> {
                ps.setObject( 1, sourceId );
                ps.setObject( 2, targetId );
                ps.setString( 3, relationshipType );
                ps.setString( 4, provenance.value() );
                ps.setString( 5, propsJson );
            } );
        } catch ( final SQLException e ) {
            // PG SQLState 23503 == foreign_key_violation. When an agent (typically MCP
            // bulk curation) passes a non-existent source_id or target_id, the JDBC
            // exception message is verbose and the WARN stacktrace drowns operator
            // logs. Translate to a clean, actionable error and log at INFO instead.
            if ( "23503".equals( e.getSQLState() ) ) {
                final String msg = e.getMessage() == null ? "" : e.getMessage();
                final String detail;
                if ( msg.contains( "kg_edges_source_id_fkey" ) ) {
                    detail = "Source node not found: " + sourceId;
                } else if ( msg.contains( "kg_edges_target_id_fkey" ) ) {
                    detail = "Target node not found: " + targetId;
                } else {
                    detail = "Edge endpoint not found (source=" + sourceId + " target=" + targetId + ")";
                }
                LOG.info( "Edge upsert refused — {} [rel={}, actor=via DefaultKgCurationOps]",
                        detail, relationshipType );
                throw new RuntimeException( detail, e );
            }
            // SQLState 23514 == check_violation. The kg_edges_relationship_type_check CHECK
            // constraint enforces the closed vocabulary at the DB layer. We don't pre-validate
            // in Java because many code paths (especially tests against H2, where the V027
            // CHECK isn't part of the test schema) use synthetic rel types deliberately.
            // Translating the DB error here gives agents the same friendly message in
            // production without breaking any caller that doesn't hit the constraint.
            if ( "23514".equals( e.getSQLState() )
                    && e.getMessage() != null
                    && e.getMessage().contains( "kg_edges_relationship_type_check" ) ) {
                final String detail = relationshipTypeViolationMessage( relationshipType );
                LOG.info( "Edge upsert refused — {} [actor=via DefaultKgCurationOps]", detail );
                throw new RuntimeException( detail, e );
            }
            LOG.warn( "Failed to upsert edge {}->{} [{}]: {}", sourceId, targetId, relationshipType, e.getMessage(), e );
            throw new RuntimeException( "Failed to upsert edge: " + e.getMessage(), e );
        }

        // Re-query to return the upserted edge
        final String selectSql = "SELECT * FROM kg_edges WHERE source_id = ? AND target_id = ? AND relationship_type = ?";
        try {
            return queryOne( selectSql, ps -> {
                ps.setObject( 1, sourceId );
                ps.setObject( 2, targetId );
                ps.setString( 3, relationshipType );
            }, this::mapEdge ).orElse( null );
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
        try {
            update( sql, ps -> {
                ps.setObject( 1, sourceId );
                ps.setObject( 2, targetId );
                ps.setString( 3, relationshipType );
                ps.setString( 4, provenance.value() );
                ps.setString( 5, propsJson );
                ps.setString( 6, tier );
                if ( provenanceProposalId == null ) ps.setNull( 7, java.sql.Types.OTHER );
                else ps.setObject( 7, provenanceProposalId );
            } );
        } catch ( final SQLException e ) {
            // SQLState 23514 == check_violation. Mirror upsertEdge: translate the
            // kg_edges_relationship_type_check CHECK violation into the agent-friendly
            // message with suggestions + full vocabulary.
            if ( "23514".equals( e.getSQLState() )
                    && e.getMessage() != null
                    && e.getMessage().contains( "kg_edges_relationship_type_check" ) ) {
                final String detail = relationshipTypeViolationMessage( relationshipType );
                LOG.info( "Edge upsert refused — {} [actor=via DefaultKgCurationOps]", detail );
                throw new RuntimeException( detail, e );
            }
            LOG.warn( "Failed to upsertEdgeWithProvenance ({},{},{}): {}",
                sourceId, targetId, relationshipType, e.getMessage(), e );
            throw new RuntimeException( "Failed to upsertEdgeWithProvenance: " + e.getMessage(), e );
        }
    }

    public void deleteEdge( final UUID id ) {
        final String sql = "DELETE FROM kg_edges WHERE id = ?";
        try {
            update( sql, ps -> ps.setObject( 1, id ) );
        } catch ( final SQLException e ) {
            LOG.warn( "Failed to delete edge '{}': {}", id, e.getMessage(), e );
            throw new RuntimeException( "Failed to delete edge: " + e.getMessage(), e );
        }
    }

    public int deleteEdgesByProvenance( final UUID proposalId ) {
        return executeDelete( "DELETE FROM kg_edges WHERE provenance_proposal_id = ?", proposalId );
    }

    /** All edges materialized from the given proposal — read side of {@link #deleteEdgesByProvenance}. */
    public List< KgEdge > findEdgesByProvenance( final UUID proposalId ) {
        final String sql = "SELECT * FROM kg_edges WHERE provenance_proposal_id = ?";
        try {
            return query( sql, ps -> ps.setObject( 1, proposalId ), this::mapEdge );
        } catch ( final SQLException e ) {
            LOG.warn( "findEdgesByProvenance({}) failed: {}", proposalId, e.getMessage(), e );
            throw new RuntimeException( "findEdgesByProvenance failed: " + e.getMessage(), e );
        }
    }

    public List< KgEdge > getAllEdges() {
        final String sql = "SELECT e.* FROM kg_edges e"
                + KgInclusionFilter.EDGE_FILTER_JOIN
                + "WHERE" + KgInclusionFilter.EDGE_FILTER_WHERE
                + "ORDER BY e.id";
        try {
            return query( sql, SqlBinder.NONE, this::mapEdge );
        } catch ( final SQLException e ) {
            LOG.warn( "Failed to get all edges: {}", e.getMessage(), e );
            throw new RuntimeException( e );
        }
    }

    public List< KgEdge > getAllEdges( final Tier minTier ) {
        final String sql = "SELECT * FROM kg_edges WHERE tier = ANY( ? )";
        try {
            return query( sql,
                ps -> ps.setArray( 1, ps.getConnection().createArrayOf( "varchar", minTier.includedTiers().toArray() ) ),
                this::mapEdge );
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

        try {
            return query( sql, ps -> {
                ps.setObject( 1, nodeId );
                if ( "both".equals( direction ) ) ps.setObject( 2, nodeId );
            }, this::mapEdge );
        } catch ( final SQLException e ) {
            LOG.warn( "Failed to get edges for node '{}': {}", nodeId, e.getMessage(), e );
            throw new RuntimeException( e );
        }
    }

    public void diffAndRemoveStaleEdges( final UUID sourceId,
                                         final Set< Map.Entry< String, String > > currentEdges ) {
        final String sql = "SELECT e.*, n.name AS target_name "
                         + "FROM kg_edges e JOIN kg_nodes n ON e.target_id = n.id "
                         + "WHERE e.source_id = ?";
        final List< UUID > toDelete;
        try {
            final List< Optional< UUID > > rows = query( sql, ps -> ps.setObject( 1, sourceId ), rs -> {
                final Provenance prov = Provenance.fromValue( rs.getString( "provenance" ) );
                if ( prov != Provenance.HUMAN_AUTHORED ) return Optional.empty();
                final String targetName = rs.getString( "target_name" );
                final String relType = rs.getString( "relationship_type" );
                if ( !currentEdges.contains( Map.entry( targetName, relType ) ) ) {
                    return Optional.of( rs.getObject( "id", UUID.class ) );
                }
                return Optional.empty();
            } );
            toDelete = rows.stream().flatMap( Optional::stream ).toList();
        } catch ( final SQLException e ) {
            LOG.warn( "Failed to diff and remove stale edges for node '{}': {}", sourceId, e.getMessage(), e );
            throw new RuntimeException( e );
        }
        for ( final UUID edgeId : toDelete ) deleteEdge( edgeId );
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

        try {
            return query( sql.toString(), SqlBinder.positional( params ), rs -> {
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
                return map;
            } );
        } catch ( final SQLException e ) {
            LOG.warn( "Failed to query edges with names: {}", e.getMessage(), e );
            throw new RuntimeException( e );
        }
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
        try {
            final int rows = update( sql, ps -> {
                ps.setString( 1, Provenance.HUMAN_CURATED.value() );
                ps.setObject( 2, id );
            } );
            if ( rows == 0 ) return null;
            return findById( id );
        } catch ( final SQLException e ) {
            LOG.warn( "elevateToHumanCurated({}) failed: {}", id, e.getMessage(), e );
            throw new RuntimeException( "elevateToHumanCurated failed", e );
        }
    }

    public KgEdge findById( final UUID id ) {
        final String sql = "SELECT * FROM kg_edges WHERE id = ?";
        try {
            return queryOne( sql, ps -> ps.setObject( 1, id ), this::mapEdge ).orElse( null );
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

        try {
            return queryOne( sql.toString(), SqlBinder.positional( params ), rs -> rs.getLong( 1 ) ).orElse( 0L );
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

        try {
            return update( sql.toString(), SqlBinder.positional( params ) );
        } catch ( final SQLException e ) {
            LOG.warn( "bulkDeleteByFilter failed: {}", e.getMessage(), e );
            throw new RuntimeException( "bulkDeleteByFilter failed: " + e.getMessage(), e );
        }
    }

    /**
     * Distinct source-node ids for edges matching the same filter {@link #bulkDeleteByFilter}
     * deletes. Read BEFORE the delete so the caller (see
     * {@code DefaultKnowledgeGraphService#bulkDeleteEdges}) can fire a {@code KgChangeEvent}
     * for the affected sources — otherwise the deleted edges' stale triples would sit in the
     * ontology until the nightly rebuild reconciles them.
     */
    public List< UUID > findDistinctSourceIdsByFilter( final String relationshipType,
                                                        final String searchName,
                                                        final String endpointKind ) {
        final StringBuilder sql = new StringBuilder(
                "SELECT DISTINCT e.source_id FROM kg_edges e "
              + "JOIN kg_nodes sn ON e.source_id = sn.id "
              + "JOIN kg_nodes tn ON e.target_id = tn.id"
              + KgInclusionFilter.EDGE_FILTER_JOIN
              + "WHERE" + KgInclusionFilter.EDGE_FILTER_WHERE );
        final List< Object > params = new ArrayList<>();
        appendEdgeFilter( sql, params, relationshipType, searchName, endpointKind );

        try {
            return query( sql.toString(), SqlBinder.positional( params ), rs -> rs.getObject( "source_id", UUID.class ) );
        } catch ( final SQLException e ) {
            LOG.warn( "findDistinctSourceIdsByFilter failed: {}", e.getMessage(), e );
            throw new RuntimeException( "findDistinctSourceIdsByFilter failed: " + e.getMessage(), e );
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

        // IllegalArgumentException("Edge not found") thrown from inside the transaction body
        // propagates unwrapped — inTransaction() rolls back on RuntimeException same as
        // SQLException, matching the original's explicit "rollback then throw IAE" branch.
        try {
            inTransaction( conn -> {
                final Optional< Object[] > row = queryOne( conn, lookupSql, ps -> ps.setObject( 1, edgeId ),
                        rs -> new Object[] { rs.getString( "s_name" ), rs.getString( "t_name" ), rs.getString( "relationship_type" ) } );
                if ( row.isEmpty() ) {
                    throw new IllegalArgumentException( "Edge not found: " + edgeId );
                }
                final String sourceName = ( String ) row.get()[ 0 ];
                final String targetName = ( String ) row.get()[ 1 ];
                final String relType    = ( String ) row.get()[ 2 ];

                update( conn, deleteSql, ps -> ps.setObject( 1, edgeId ) );
                update( conn, rejectSql, ps -> {
                    ps.setString( 1, sourceName );
                    ps.setString( 2, targetName );
                    ps.setString( 3, relType );
                    ps.setString( 4, actor );
                    ps.setString( 5, reason );
                } );
                return null;
            } );
        } catch ( final SQLException e ) {
            LOG.warn( "deleteEdgeAndRecordRejection({}) rolled back: {}", edgeId, e.getMessage(), e );
            throw new RuntimeException( "deleteEdgeAndRecordRejection failed", e );
        }
    }
}
