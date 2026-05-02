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
import com.google.gson.reflect.TypeToken;
import com.wikantik.api.knowledge.*;
import com.wikantik.kgpolicy.KgInclusionFilter;
import edu.umd.cs.findbugs.annotations.SuppressFBWarnings;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import javax.sql.DataSource;
import java.lang.reflect.Type;
import java.sql.*;
import java.time.Instant;
import java.util.*;

/**
 * JDBC-based data access layer for the knowledge graph tables.
 * Uses PostgreSQL with JSONB columns for property storage.
 *
 * <p>Uses plain JDBC with {@link DataSource} for connection management.
 * JSON properties are serialized/deserialized with Gson.</p>
 *
 * @since 1.0
 */
public class JdbcKnowledgeRepository {

    private static final Logger LOG = LogManager.getLogger( JdbcKnowledgeRepository.class );
    private static final Gson GSON = new Gson();
    private static final Type MAP_TYPE = new TypeToken< Map< String, Object > >() {}.getType();

    private final DataSource dataSource;

    public JdbcKnowledgeRepository( final DataSource dataSource ) {
        this.dataSource = dataSource;
    }

    // ---- Node operations ----

    /**
     * Creates or updates a knowledge graph node using INSERT ... ON CONFLICT.
     *
     * @param name       the unique node name
     * @param nodeType   the node type (e.g. "domain-model", "concept")
     * @param sourcePage the source wiki page, or null for stubs
     * @param provenance the provenance of the node
     * @param properties additional properties as a map
     * @return the upserted node
     */
    public KgNode upsertNode( final String name, final String nodeType, final String sourcePage,
                              final Provenance provenance, final Map< String, Object > properties ) {
        final String propsJson = GSON.toJson( properties != null ? properties : Map.of() );
        final String sql = "INSERT INTO kg_nodes ( name, node_type, source_page, provenance, properties, tier, provenance_proposal_id, modified ) "
                + "VALUES ( ?, ?, ?, ?, ?::jsonb, 'human', NULL, CURRENT_TIMESTAMP ) "
                + "ON CONFLICT ( name ) DO UPDATE SET node_type = EXCLUDED.node_type, "
                + "source_page = COALESCE(EXCLUDED.source_page, kg_nodes.source_page), provenance = EXCLUDED.provenance, "
                + "properties = EXCLUDED.properties, modified = CURRENT_TIMESTAMP";
        try( Connection conn = dataSource.getConnection();
             PreparedStatement ps = conn.prepareStatement( sql ) ) {
            ps.setString( 1, name );
            ps.setString( 2, nodeType );
            ps.setString( 3, sourcePage );
            ps.setString( 4, provenance.value() );
            ps.setString( 5, propsJson );
            ps.executeUpdate();
        } catch( final SQLException e ) {
            LOG.warn( "Failed to upsert node '{}': {}", name, e.getMessage(), e );
            throw new RuntimeException( "Failed to upsert node: " + e.getMessage(), e );
        }
        return getNodeByName( name );
    }

    /**
     * Retrieves a node by its UUID.
     *
     * @param id the node UUID
     * @return the node, or null if not found
     */
    public KgNode getNode( final UUID id ) {
        final String sql = "SELECT n.* FROM kg_nodes n"
                + KgInclusionFilter.NODE_FILTER_JOIN
                + "WHERE n.id = ?"
                + " AND" + KgInclusionFilter.NODE_FILTER_WHERE;
        try( Connection conn = dataSource.getConnection();
             PreparedStatement ps = conn.prepareStatement( sql ) ) {
            ps.setObject( 1, id );
            try( ResultSet rs = ps.executeQuery() ) {
                return rs.next() ? mapNode( rs ) : null;
            }
        } catch( final SQLException e ) {
            LOG.warn( "Failed to get node by id '{}': {}", id, e.getMessage(), e );
            throw new RuntimeException( e );
        }
    }

    /**
     * Retrieves a node by its unique name.
     *
     * @param name the node name
     * @return the node, or null if not found
     */
    public KgNode getNodeByName( final String name ) {
        final String sql = "SELECT n.* FROM kg_nodes n"
                + KgInclusionFilter.NODE_FILTER_JOIN
                + "WHERE n.name = ?"
                + " AND" + KgInclusionFilter.NODE_FILTER_WHERE;
        try( Connection conn = dataSource.getConnection();
             PreparedStatement ps = conn.prepareStatement( sql ) ) {
            ps.setString( 1, name );
            try( ResultSet rs = ps.executeQuery() ) {
                return rs.next() ? mapNode( rs ) : null;
            }
        } catch( final SQLException e ) {
            LOG.warn( "Failed to get node by name '{}': {}", name, e.getMessage(), e );
            throw new RuntimeException( e );
        }
    }

    /**
     * Deletes a node by its UUID. Cascade deletes remove associated edges.
     *
     * @param id the node UUID
     */
    public void deleteNode( final UUID id ) {
        final String sql = "DELETE FROM kg_nodes WHERE id = ?";
        try( Connection conn = dataSource.getConnection();
             PreparedStatement ps = conn.prepareStatement( sql ) ) {
            ps.setObject( 1, id );
            ps.executeUpdate();
        } catch( final SQLException e ) {
            LOG.warn( "Failed to delete node '{}': {}", id, e.getMessage(), e );
            throw new RuntimeException( "Failed to delete node: " + e.getMessage(), e );
        }
    }

    /**
     * Queries nodes with optional filters.
     *
     * @param filters          map of field names to filter values; supports "node_type", "source_page", "name" (LIKE), "status" (JSON property match)
     * @param provenanceFilter set of acceptable provenance values, or empty for all
     * @param limit            maximum number of results
     * @param offset           number of results to skip
     * @return list of matching nodes
     */
    @SuppressFBWarnings( value = "SQL_PREPARED_STATEMENT_GENERATED_FROM_NONCONSTANT_STRING",
            justification = "SQL fragments are all string literals; only '?' placeholders are appended conditionally. All user values bound via PreparedStatement.setObject." )
    public List< KgNode > queryNodes( final Map< String, Object > filters,
                                      final Set< Provenance > provenanceFilter,
                                      final int limit, final int offset ) {
        final StringBuilder sql = new StringBuilder( "SELECT n.* FROM kg_nodes n" )
                .append( KgInclusionFilter.NODE_FILTER_JOIN )
                .append( "WHERE" ).append( KgInclusionFilter.NODE_FILTER_WHERE );
        final List< Object > params = new ArrayList<>();

        if( filters != null ) {
            if( filters.containsKey( "node_type" ) ) {
                sql.append( " AND n.node_type = ?" );
                params.add( filters.get( "node_type" ) );
            }
            if( filters.containsKey( "source_page" ) ) {
                sql.append( " AND n.source_page = ?" );
                params.add( filters.get( "source_page" ) );
            }
            if( filters.containsKey( "name" ) ) {
                sql.append( " AND LOWER( n.name ) LIKE ?" );
                params.add( "%" + filters.get( "name" ).toString().toLowerCase( Locale.ROOT ) + "%" );
            }
            if( filters.containsKey( "status" ) ) {
                sql.append( " AND n.properties->>'status' = ?" );
                params.add( filters.get( "status" ) );
            }
        }

        if( provenanceFilter != null && !provenanceFilter.isEmpty() ) {
            sql.append( " AND n.provenance IN (" );
            final StringJoiner sj = new StringJoiner( ", " );
            for( final Provenance p : provenanceFilter ) {
                sj.add( "?" );
                params.add( p.value() );
            }
            sql.append( sj );
            sql.append(')');
        }

        sql.append( " ORDER BY n.name LIMIT ? OFFSET ?" );
        params.add( limit );
        params.add( offset );

        final List< KgNode > results = new ArrayList<>();
        try( Connection conn = dataSource.getConnection();
             PreparedStatement ps = conn.prepareStatement( sql.toString() ) ) {
            for( int i = 0; i < params.size(); i++ ) {
                ps.setObject( i + 1, params.get( i ) );
            }
            try( ResultSet rs = ps.executeQuery() ) {
                while( rs.next() ) {
                    results.add( mapNode( rs ) );
                }
            }
        } catch( final SQLException e ) {
            LOG.warn( "Failed to query nodes: {}", e.getMessage(), e );
            throw new RuntimeException( e );
        }
        return results;
    }

    /**
     * Searches nodes by name or property content using case-insensitive LIKE.
     *
     * @param query            the search query
     * @param provenanceFilter set of acceptable provenance values, or empty for all
     * @param limit            maximum number of results
     * @return list of matching nodes
     */
    @SuppressFBWarnings( value = "SQL_PREPARED_STATEMENT_GENERATED_FROM_NONCONSTANT_STRING",
            justification = "SQL fragments are all string literals; only '?' placeholders are appended conditionally. All user values bound via PreparedStatement.setObject." )
    public List< KgNode > searchNodes( final String query, final Set< Provenance > provenanceFilter,
                                       final int limit ) {
        final StringBuilder sql = new StringBuilder( "SELECT n.* FROM kg_nodes n" )
                .append( KgInclusionFilter.NODE_FILTER_JOIN )
                .append( "WHERE" ).append( KgInclusionFilter.NODE_FILTER_WHERE )
                .append( " AND ( LOWER( n.name ) LIKE ? OR LOWER( n.properties::text ) LIKE ? )" );
        final List< Object > params = new ArrayList<>();
        final String pattern = "%" + query.toLowerCase( Locale.ROOT ) + "%";
        params.add( pattern );
        params.add( pattern );

        if( provenanceFilter != null && !provenanceFilter.isEmpty() ) {
            sql.append( " AND n.provenance IN (" );
            final StringJoiner sj = new StringJoiner( ", " );
            for( final Provenance p : provenanceFilter ) {
                sj.add( "?" );
                params.add( p.value() );
            }
            sql.append( sj );
            sql.append(')');
        }

        sql.append( " ORDER BY n.name LIMIT ?" );
        params.add( limit );

        final List< KgNode > results = new ArrayList<>();
        try( Connection conn = dataSource.getConnection();
             PreparedStatement ps = conn.prepareStatement( sql.toString() ) ) {
            for( int i = 0; i < params.size(); i++ ) {
                ps.setObject( i + 1, params.get( i ) );
            }
            try( ResultSet rs = ps.executeQuery() ) {
                while( rs.next() ) {
                    results.add( mapNode( rs ) );
                }
            }
        } catch( final SQLException e ) {
            LOG.warn( "Failed to search nodes: {}", e.getMessage(), e );
            throw new RuntimeException( e );
        }
        return results;
    }

    // ---- Edge operations ----

    /**
     * Creates or updates an edge between two nodes using INSERT ... ON CONFLICT.
     *
     * @param sourceId         the source node UUID
     * @param targetId         the target node UUID
     * @param relationshipType the relationship type
     * @param provenance       the provenance of the edge
     * @param properties       additional properties as a map
     * @return the upserted edge
     */
    public KgEdge upsertEdge( final UUID sourceId, final UUID targetId, final String relationshipType,
                              final Provenance provenance, final Map< String, Object > properties ) {
        final String propsJson = GSON.toJson( properties != null ? properties : Map.of() );
        final String sql = "INSERT INTO kg_edges ( source_id, target_id, relationship_type, provenance, properties, tier, provenance_proposal_id, modified ) "
                + "VALUES ( ?, ?, ?, ?, ?::jsonb, 'human', NULL, CURRENT_TIMESTAMP ) "
                + "ON CONFLICT ( source_id, target_id, relationship_type ) DO UPDATE SET "
                + "provenance = EXCLUDED.provenance, properties = EXCLUDED.properties, "
                + "modified = CURRENT_TIMESTAMP";
        try( Connection conn = dataSource.getConnection();
             PreparedStatement ps = conn.prepareStatement( sql ) ) {
            ps.setObject( 1, sourceId );
            ps.setObject( 2, targetId );
            ps.setString( 3, relationshipType );
            ps.setString( 4, provenance.value() );
            ps.setString( 5, propsJson );
            ps.executeUpdate();
        } catch( final SQLException e ) {
            LOG.warn( "Failed to upsert edge {}->{} [{}]: {}", sourceId, targetId, relationshipType, e.getMessage(), e );
            throw new RuntimeException( "Failed to upsert edge: " + e.getMessage(), e );
        }

        // Re-query to return the upserted edge
        final String selectSql = "SELECT * FROM kg_edges WHERE source_id = ? AND target_id = ? AND relationship_type = ?";
        try( Connection conn = dataSource.getConnection();
             PreparedStatement ps = conn.prepareStatement( selectSql ) ) {
            ps.setObject( 1, sourceId );
            ps.setObject( 2, targetId );
            ps.setString( 3, relationshipType );
            try( ResultSet rs = ps.executeQuery() ) {
                return rs.next() ? mapEdge( rs ) : null;
            }
        } catch( final SQLException e ) {
            LOG.warn( "Failed to read back upserted edge: {}", e.getMessage(), e );
            throw new RuntimeException( e );
        }
    }

    /**
     * Deletes an edge by its UUID.
     *
     * @param id the edge UUID
     */
    public void deleteEdge( final UUID id ) {
        final String sql = "DELETE FROM kg_edges WHERE id = ?";
        try( Connection conn = dataSource.getConnection();
             PreparedStatement ps = conn.prepareStatement( sql ) ) {
            ps.setObject( 1, id );
            ps.executeUpdate();
        } catch( final SQLException e ) {
            LOG.warn( "Failed to delete edge '{}': {}", id, e.getMessage(), e );
            throw new RuntimeException( "Failed to delete edge: " + e.getMessage(), e );
        }
    }

    /**
     * Returns all nodes in the knowledge graph, ordered by id for determinism.
     *
     * @return list of all nodes
     */
    public List< KgNode > getAllNodes() {
        final String sql = "SELECT n.* FROM kg_nodes n"
                + KgInclusionFilter.NODE_FILTER_JOIN
                + "WHERE" + KgInclusionFilter.NODE_FILTER_WHERE
                + "ORDER BY n.id";
        final List< KgNode > results = new ArrayList<>();
        try( Connection conn = dataSource.getConnection();
             PreparedStatement ps = conn.prepareStatement( sql );
             ResultSet rs = ps.executeQuery() ) {
            while( rs.next() ) {
                results.add( mapNode( rs ) );
            }
        } catch( final SQLException e ) {
            LOG.warn( "Failed to get all nodes: {}", e.getMessage(), e );
            throw new RuntimeException( e );
        }
        return results;
    }

    /**
     * Returns all edges in the knowledge graph, ordered by id for determinism.
     *
     * @return list of all edges
     */
    public List< KgEdge > getAllEdges() {
        final String sql = "SELECT e.* FROM kg_edges e"
                + KgInclusionFilter.EDGE_FILTER_JOIN
                + "WHERE" + KgInclusionFilter.EDGE_FILTER_WHERE
                + "ORDER BY e.id";
        final List< KgEdge > results = new ArrayList<>();
        try( Connection conn = dataSource.getConnection();
             PreparedStatement ps = conn.prepareStatement( sql );
             ResultSet rs = ps.executeQuery() ) {
            while( rs.next() ) {
                results.add( mapEdge( rs ) );
            }
        } catch( final SQLException e ) {
            LOG.warn( "Failed to get all edges: {}", e.getMessage(), e );
            throw new RuntimeException( e );
        }
        return results;
    }

    /**
     * Returns nodes whose {@code tier} column is in the set implied by {@code minTier}.
     * {@link Tier#HUMAN} returns only human-vetted rows; {@link Tier#MACHINE} returns
     * both machine and human rows.
     *
     * @param minTier the minimum trust tier to include
     * @return list of matching nodes
     */
    public List< KgNode > getAllNodes( final Tier minTier ) {
        final String sql = "SELECT * FROM kg_nodes WHERE tier = ANY( ? )";
        try ( Connection conn = dataSource.getConnection();
              PreparedStatement ps = conn.prepareStatement( sql ) ) {
            ps.setArray( 1, conn.createArrayOf( "varchar", minTier.includedTiers().toArray() ) );
            try ( ResultSet rs = ps.executeQuery() ) {
                final List< KgNode > out = new ArrayList<>();
                while ( rs.next() ) out.add( mapNode( rs ) );
                return out;
            }
        } catch ( final SQLException e ) {
            LOG.warn( "getAllNodes({}) failed: {}", minTier.wireName(), e.getMessage(), e );
            throw new RuntimeException( "getAllNodes failed: " + e.getMessage(), e );
        }
    }

    /**
     * Returns edges whose {@code tier} column is in the set implied by {@code minTier}.
     * {@link Tier#HUMAN} returns only human-vetted rows; {@link Tier#MACHINE} returns
     * both machine and human rows.
     *
     * @param minTier the minimum trust tier to include
     * @return list of matching edges
     */
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

    /**
     * Returns edges for a node in the given direction.
     *
     * @param nodeId    the node UUID
     * @param direction "outbound", "inbound", or "both"
     * @return list of matching edges
     */
    public List< KgEdge > getEdgesForNode( final UUID nodeId, final String direction ) {
        final String filterJoin = KgInclusionFilter.EDGE_FILTER_JOIN;
        final String filterWhere = KgInclusionFilter.EDGE_FILTER_WHERE;
        final String sql = switch( direction ) {
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
        try( Connection conn = dataSource.getConnection();
             PreparedStatement ps = conn.prepareStatement( sql ) ) {
            ps.setObject( 1, nodeId );
            if( "both".equals( direction ) ) {
                ps.setObject( 2, nodeId );
            }
            try( ResultSet rs = ps.executeQuery() ) {
                while( rs.next() ) {
                    results.add( mapEdge( rs ) );
                }
            }
        } catch( final SQLException e ) {
            LOG.warn( "Failed to get edges for node '{}': {}", nodeId, e.getMessage(), e );
            throw new RuntimeException( e );
        }
        return results;
    }

    /**
     * Removes human-authored outbound edges from sourceId whose (targetName, relationshipType) pair
     * is NOT in the given set of current edges. Never touches ai-inferred or ai-reviewed edges.
     *
     * @param sourceId     the source node UUID
     * @param currentEdges set of (targetName, relationshipType) pairs that should be preserved
     */
    public void diffAndRemoveStaleEdges( final UUID sourceId,
                                         final Set< Map.Entry< String, String > > currentEdges ) {
        // Get all outbound edges with their target node names
        final String sql = "SELECT e.*, n.name AS target_name "
                         + "FROM kg_edges e JOIN kg_nodes n ON e.target_id = n.id "
                         + "WHERE e.source_id = ?";
        try( Connection conn = dataSource.getConnection();
             PreparedStatement ps = conn.prepareStatement( sql ) ) {
            ps.setObject( 1, sourceId );
            final List< UUID > toDelete = new ArrayList<>();
            try( ResultSet rs = ps.executeQuery() ) {
                while( rs.next() ) {
                    final String provValue = rs.getString( "provenance" );
                    final Provenance prov = Provenance.fromValue( provValue );
                    // Only remove human-authored edges
                    if( prov != Provenance.HUMAN_AUTHORED ) {
                        continue;
                    }
                    final String targetName = rs.getString( "target_name" );
                    final String relType = rs.getString( "relationship_type" );
                    if( !currentEdges.contains( Map.entry( targetName, relType ) ) ) {
                        toDelete.add( rs.getObject( "id", UUID.class ) );
                    }
                }
            }
            for( final UUID edgeId : toDelete ) {
                deleteEdge( edgeId );
            }
        } catch( final SQLException e ) {
            LOG.warn( "Failed to diff and remove stale edges for node '{}': {}", sourceId, e.getMessage(), e );
            throw new RuntimeException( e );
        }
    }

    // ---- Proposal operations ----

    /**
     * Inserts a new proposal with 'pending' status.
     *
     * @param proposalType the proposal type (e.g. "new-edge", "new-node")
     * @param sourcePage   the source wiki page
     * @param proposedData the proposed data as a map
     * @param confidence   the confidence score (0.0 to 1.0)
     * @param reasoning    human-readable reasoning
     * @return the created proposal
     */
    public KgProposal insertProposal( final String proposalType, final String sourcePage,
                                      final Map< String, Object > proposedData,
                                      final double confidence, final String reasoning ) {
        final String sql = "INSERT INTO kg_proposals ( proposal_type, source_page, proposed_data, confidence, reasoning ) "
                + "VALUES ( ?, ?, ?::jsonb, ?, ? )";
        try( Connection conn = dataSource.getConnection();
             PreparedStatement ps = conn.prepareStatement( sql, Statement.RETURN_GENERATED_KEYS ) ) {
            ps.setString( 1, proposalType );
            ps.setString( 2, sourcePage );
            ps.setString( 3, GSON.toJson( proposedData != null ? proposedData : Map.of() ) );
            ps.setDouble( 4, confidence );
            ps.setString( 5, reasoning );
            ps.executeUpdate();
            try( ResultSet keys = ps.getGeneratedKeys() ) {
                if( keys.next() ) {
                    final UUID id = keys.getObject( 1, UUID.class );
                    return getProposal( id );
                }
            }
        } catch( final SQLException e ) {
            LOG.warn( "Failed to insert proposal: {}", e.getMessage(), e );
            throw new RuntimeException( "Failed to insert proposal: " + e.getMessage(), e );
        }
        return null;
    }

    /**
     * Retrieves a proposal by its UUID.
     *
     * @param id the proposal UUID
     * @return the proposal, or null if not found
     */
    public KgProposal getProposal( final UUID id ) {
        final String sql = "SELECT * FROM kg_proposals WHERE id = ?";
        try( Connection conn = dataSource.getConnection();
             PreparedStatement ps = conn.prepareStatement( sql ) ) {
            ps.setObject( 1, id );
            try( ResultSet rs = ps.executeQuery() ) {
                return rs.next() ? mapProposal( rs ) : null;
            }
        } catch( final SQLException e ) {
            LOG.warn( "Failed to get proposal '{}': {}", id, e.getMessage(), e );
            throw new RuntimeException( e );
        }
    }

    /**
     * Lists proposals with optional filters.
     *
     * @param status     optional status filter (e.g. "pending", "approved", "rejected")
     * @param sourcePage optional source page filter
     * @param limit      maximum number of results
     * @param offset     number of results to skip
     * @return list of matching proposals
     */
    public List< KgProposal > listProposals( final String status, final String sourcePage,
                                             final int limit, final int offset ) {
        final StringBuilder sql = new StringBuilder( "SELECT * FROM kg_proposals WHERE 1=1" );
        final List< Object > params = new ArrayList<>();

        if( status != null ) {
            sql.append( " AND status = ?" );
            params.add( status );
        }
        if( sourcePage != null ) {
            sql.append( " AND source_page = ?" );
            params.add( sourcePage );
        }

        sql.append( " ORDER BY created DESC LIMIT ? OFFSET ?" );
        params.add( limit );
        params.add( offset );

        final List< KgProposal > results = new ArrayList<>();
        try( Connection conn = dataSource.getConnection();
             PreparedStatement ps = conn.prepareStatement( sql.toString() ) ) {
            for( int i = 0; i < params.size(); i++ ) {
                ps.setObject( i + 1, params.get( i ) );
            }
            try( ResultSet rs = ps.executeQuery() ) {
                while( rs.next() ) {
                    results.add( mapProposal( rs ) );
                }
            }
        } catch( final SQLException e ) {
            LOG.warn( "Failed to list proposals: {}", e.getMessage(), e );
            throw new RuntimeException( e );
        }
        return results;
    }

    /**
     * Updates the status of a proposal and records the reviewer.
     *
     * @param id         the proposal UUID
     * @param status     the new status
     * @param reviewedBy the reviewer identity
     */
    public void updateProposalStatus( final UUID id, final String status, final String reviewedBy ) {
        final String sql = "UPDATE kg_proposals SET status = ?, reviewed_by = ?, reviewed_at = CURRENT_TIMESTAMP WHERE id = ?";
        try( Connection conn = dataSource.getConnection();
             PreparedStatement ps = conn.prepareStatement( sql ) ) {
            ps.setString( 1, status );
            ps.setString( 2, reviewedBy );
            ps.setObject( 3, id );
            ps.executeUpdate();
        } catch( final SQLException e ) {
            LOG.warn( "Failed to update proposal status '{}': {}", id, e.getMessage(), e );
            throw new RuntimeException( "Failed to update proposal status: " + e.getMessage(), e );
        }
    }

    // ---- Rejection operations ----

    /**
     * Inserts a rejection record to prevent re-proposals. Uses INSERT ... ON CONFLICT
     * to update existing rejections.
     *
     * @param proposedSource       the proposed source node name
     * @param proposedTarget       the proposed target node name
     * @param proposedRelationship the proposed relationship type
     * @param rejectedBy           the reviewer identity
     * @param reason               the rejection reason
     */
    public void insertRejection( final String proposedSource, final String proposedTarget,
                                 final String proposedRelationship,
                                 final String rejectedBy, final String reason ) {
        final String sql = "INSERT INTO kg_rejections ( proposed_source, proposed_target, proposed_relationship, rejected_by, reason ) "
                + "VALUES ( ?, ?, ?, ?, ? ) "
                + "ON CONFLICT ( proposed_source, proposed_target, proposed_relationship ) DO UPDATE SET "
                + "rejected_by = EXCLUDED.rejected_by, reason = EXCLUDED.reason";
        try( Connection conn = dataSource.getConnection();
             PreparedStatement ps = conn.prepareStatement( sql ) ) {
            ps.setString( 1, proposedSource );
            ps.setString( 2, proposedTarget );
            ps.setString( 3, proposedRelationship );
            ps.setString( 4, rejectedBy );
            ps.setString( 5, reason );
            ps.executeUpdate();
        } catch( final SQLException e ) {
            LOG.warn( "Failed to insert rejection: {}", e.getMessage(), e );
            throw new RuntimeException( "Failed to insert rejection: " + e.getMessage(), e );
        }
    }

    /**
     * Checks whether a proposed relationship has been rejected.
     *
     * @param sourceName       the source node name
     * @param targetName       the target node name
     * @param relationshipType the relationship type
     * @return true if the relationship has been rejected
     */
    public boolean isRejected( final String sourceName, final String targetName,
                               final String relationshipType ) {
        final String sql = "SELECT COUNT(*) FROM kg_rejections "
                         + "WHERE proposed_source = ? AND proposed_target = ? AND proposed_relationship = ?";
        try( Connection conn = dataSource.getConnection();
             PreparedStatement ps = conn.prepareStatement( sql ) ) {
            ps.setString( 1, sourceName );
            ps.setString( 2, targetName );
            ps.setString( 3, relationshipType );
            try( ResultSet rs = ps.executeQuery() ) {
                return rs.next() && rs.getInt( 1 ) > 0;
            }
        } catch( final SQLException e ) {
            LOG.warn( "Failed to check rejection: {}", e.getMessage(), e );
            throw new RuntimeException( e );
        }
    }

    /**
     * Lists rejections with optional filters.
     *
     * @param sourceName       optional source name filter
     * @param targetName       optional target name filter
     * @param relationshipType optional relationship type filter
     * @return list of matching rejections
     */
    public List< KgRejection > listRejections( final String sourceName, final String targetName,
                                               final String relationshipType ) {
        final StringBuilder sql = new StringBuilder( "SELECT * FROM kg_rejections WHERE 1=1" );
        final List< Object > params = new ArrayList<>();

        if( sourceName != null ) {
            sql.append( " AND proposed_source = ?" );
            params.add( sourceName );
        }
        if( targetName != null ) {
            sql.append( " AND proposed_target = ?" );
            params.add( targetName );
        }
        if( relationshipType != null ) {
            sql.append( " AND proposed_relationship = ?" );
            params.add( relationshipType );
        }

        sql.append( " ORDER BY created DESC" );

        final List< KgRejection > results = new ArrayList<>();
        try( Connection conn = dataSource.getConnection();
             PreparedStatement ps = conn.prepareStatement( sql.toString() ) ) {
            for( int i = 0; i < params.size(); i++ ) {
                ps.setObject( i + 1, params.get( i ) );
            }
            try( ResultSet rs = ps.executeQuery() ) {
                while( rs.next() ) {
                    results.add( mapRejection( rs ) );
                }
            }
        } catch( final SQLException e ) {
            LOG.warn( "Failed to list rejections: {}", e.getMessage(), e );
            throw new RuntimeException( e );
        }
        return results;
    }

    // ---- Bulk name resolution ----

    /**
     * Resolves a collection of node UUIDs to their names in a single query.
     *
     * @param ids the UUIDs to resolve
     * @return map of UUID to node name
     */
    public Map< UUID, String > getNodeNames( final Collection< UUID > ids ) {
        if ( ids == null || ids.isEmpty() ) {
            return Map.of();
        }
        final String placeholders = String.join( ", ", Collections.nCopies( ids.size(), "?" ) );
        final String sql = "SELECT n.id, n.name FROM kg_nodes n"
                + KgInclusionFilter.NODE_FILTER_JOIN
                + "WHERE n.id IN ( " + placeholders + " )"
                + " AND" + KgInclusionFilter.NODE_FILTER_WHERE;
        final Map< UUID, String > result = new HashMap<>();
        try ( Connection conn = dataSource.getConnection();
              PreparedStatement ps = conn.prepareStatement( sql ) ) {
            int idx = 1;
            for ( final UUID id : ids ) {
                ps.setObject( idx++, id );
            }
            try ( ResultSet rs = ps.executeQuery() ) {
                while ( rs.next() ) {
                    result.put( rs.getObject( "id", UUID.class ), rs.getString( "name" ) );
                }
            }
        } catch ( final SQLException e ) {
            LOG.warn( "Failed to resolve node names: {}", e.getMessage(), e );
        }
        return result;
    }

    /**
     * Queries edges with JOINed source and target node names. Supports optional
     * filtering by relationship type and name search, with pagination.
     *
     * @param relationshipType optional filter
     * @param searchName       optional search term (matches source or target name, case-insensitive)
     * @param limit            max results
     * @param offset           pagination offset
     * @return list of edge maps including source_name and target_name
     */
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
            for ( int i = 0; i < params.size(); i++ ) {
                ps.setObject( i + 1, params.get( i ) );
            }
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

    // ---- Aggregate queries ----

    /**
     * Returns all distinct node types in the graph.
     *
     * @return set of distinct node type strings
     */
    public List< String > getDistinctNodeTypes() {
        return queryDistinct( "SELECT DISTINCT node_type FROM kg_nodes WHERE node_type IS NOT NULL ORDER BY node_type" );
    }

    /**
     * Returns all distinct relationship types in the graph.
     *
     * @return set of distinct relationship type strings
     */
    public List< String > getDistinctRelationshipTypes() {
        return queryDistinct( "SELECT DISTINCT relationship_type FROM kg_edges ORDER BY relationship_type" );
    }

    /**
     * Counts the total number of nodes.
     *
     * @return node count
     */
    public long countNodes() {
        return queryCount( "SELECT COUNT(*) FROM kg_nodes" );
    }

    /**
     * Counts the total number of edges.
     *
     * @return edge count
     */
    public long countEdges() {
        return queryCount( "SELECT COUNT(*) FROM kg_edges" );
    }

    /**
     * Counts the number of pending proposals.
     *
     * @return pending proposal count
     */
    public long countPendingProposals() {
        return queryCount( "SELECT COUNT(*) FROM kg_proposals WHERE status = 'pending'" );
    }

    // ---- Private helpers ----

    private KgNode mapNode( final ResultSet rs ) throws SQLException {
        return new KgNode(
            rs.getObject( "id", UUID.class ),
            rs.getString( "name" ),
            rs.getString( "node_type" ),
            rs.getString( "source_page" ),
            Provenance.fromValue( rs.getString( "provenance" ) ),
            parseJson( rs.getString( "properties" ) ),
            toInstant( rs.getTimestamp( "created" ) ),
            toInstant( rs.getTimestamp( "modified" ) ),
            rs.getString( "tier" ),
            rs.getObject( "provenance_proposal_id", UUID.class )
        );
    }

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

    private KgProposal mapProposal( final ResultSet rs ) throws SQLException {
        return new KgProposal(
            rs.getObject( "id", UUID.class ),
            rs.getString( "proposal_type" ),
            rs.getString( "source_page" ),
            parseJson( rs.getString( "proposed_data" ) ),
            rs.getDouble( "confidence" ),
            rs.getString( "reasoning" ),
            rs.getString( "status" ),
            rs.getString( "reviewed_by" ),
            toInstant( rs.getTimestamp( "created" ) ),
            toInstant( rs.getTimestamp( "reviewed_at" ) ),
            rs.getString( "tier" ),
            rs.getString( "machine_status" ),
            rs.getObject( "machine_confidence", Double.class ),
            toInstant( rs.getTimestamp( "machine_judged_at" ) ),
            rs.getString( "machine_model" )
        );
    }

    private KgRejection mapRejection( final ResultSet rs ) throws SQLException {
        return new KgRejection(
            rs.getObject( "id", UUID.class ),
            rs.getString( "proposed_source" ),
            rs.getString( "proposed_target" ),
            rs.getString( "proposed_relationship" ),
            rs.getString( "rejected_by" ),
            rs.getString( "reason" ),
            toInstant( rs.getTimestamp( "created" ) )
        );
    }

    private Map< String, Object > parseJson( final String json ) {
        if( json == null || json.isBlank() ) {
            return Map.of();
        }
        final Map< String, Object > result = GSON.fromJson( json, MAP_TYPE );
        return result != null ? result : Map.of();
    }

    private Instant toInstant( final Timestamp ts ) {
        return ts != null ? ts.toInstant() : null;
    }

    private List< String > queryDistinct( final String sql ) {
        final List< String > results = new ArrayList<>();
        try( Connection conn = dataSource.getConnection();
             PreparedStatement ps = conn.prepareStatement( sql );
             ResultSet rs = ps.executeQuery() ) {
            while( rs.next() ) {
                results.add( rs.getString( 1 ) );
            }
        } catch( final SQLException e ) {
            LOG.warn( "Failed to execute distinct query: {}", e.getMessage(), e );
            throw new RuntimeException( e );
        }
        return results;
    }

    private long queryCount( final String sql ) {
        try( Connection conn = dataSource.getConnection();
             PreparedStatement ps = conn.prepareStatement( sql );
             ResultSet rs = ps.executeQuery() ) {
            return rs.next() ? rs.getLong( 1 ) : 0;
        } catch( final SQLException e ) {
            LOG.warn( "Failed to execute count query: {}", e.getMessage(), e );
            throw new RuntimeException( e );
        }
    }

    /**
     * Upserts a {@link ConsolidatedProposal} into {@code kg_proposals}, merging
     * support evidence by source page on collision. The unique partial index on
     * {@code (signature) WHERE status='pending'} drives the conflict target;
     * approved/rejected history is allowed to repeat.
     *
     * <p>Idempotency: re-running extraction against the same source page does
     * not double the support entry — {@code DISTINCT ON (sourcePage)} keeps the
     * highest-confidence quote per page.</p>
     *
     * @param cp the consolidated proposal to upsert (node or edge)
     * @return inserted/merged flag plus the resulting support_count
     */
    public com.wikantik.knowledge.extraction.ProposalUpserter.Result upsertConsolidatedProposal( final ConsolidatedProposal cp ) {
        final String proposedJson = GSON.toJson( buildProposedData( cp ) );
        final String supportJson  = GSON.toJson( cp.support() );
        final String sql = """
            WITH merged AS (
                SELECT (
                    SELECT jsonb_agg(s ORDER BY (s->>'sourcePage'))
                    FROM (
                        SELECT DISTINCT ON (s->>'sourcePage') s
                        FROM jsonb_array_elements(
                            COALESCE(kp.support, '[]'::jsonb) || ?::jsonb
                        ) s
                        ORDER BY (s->>'sourcePage'), (s->>'confidence')::numeric DESC
                    ) deduped
                ) AS support_merged
                FROM kg_proposals kp
                WHERE kp.signature = ? AND kp.status = 'pending'
            )
            INSERT INTO kg_proposals
                (proposal_type, source_page, proposed_data, confidence, reasoning,
                 signature, support, support_count, first_seen_at, last_seen_at)
            VALUES (?, ?, ?::jsonb, ?, ?, ?, ?::jsonb,
                    jsonb_array_length(?::jsonb), NOW(), NOW())
            ON CONFLICT (signature) WHERE status = 'pending' DO UPDATE
            SET support       = COALESCE((SELECT support_merged FROM merged), kg_proposals.support),
                support_count = jsonb_array_length(COALESCE((SELECT support_merged FROM merged), kg_proposals.support)),
                confidence    = GREATEST(kg_proposals.confidence, EXCLUDED.confidence),
                last_seen_at  = NOW()
            RETURNING (xmax = 0) AS inserted, support_count
            """;
        try( Connection conn = dataSource.getConnection();
             PreparedStatement ps = conn.prepareStatement( sql ) ) {
            ps.setString( 1, supportJson );
            ps.setString( 2, cp.signature() );
            ps.setString( 3, cp.kind() == ConsolidatedProposal.Kind.NEW_NODE ? "new-node" : "new-edge" );
            ps.setString( 4, cp.support().isEmpty() ? null : cp.support().get( 0 ).sourcePage() );
            ps.setString( 5, proposedJson );
            ps.setDouble( 6, cp.aggregateConfidence() );
            ps.setString( 7, "consolidated by " + cp.support().size() + " support(s)" );
            ps.setString( 8, cp.signature() );
            ps.setString( 9, supportJson );
            // Phase 2 wrote a hardcoded 1 here, so a first-run insert with
            // multi-page support always reported support_count=1; the new
            // per-page indexer feeds a multi-element array on the very first
            // upsert, so we now compute the count from the array length.
            ps.setString( 10, supportJson );
            try( ResultSet rs = ps.executeQuery() ) {
                if( rs.next() ) {
                    return new com.wikantik.knowledge.extraction.ProposalUpserter.Result( rs.getBoolean( 1 ), rs.getInt( 2 ) );
                }
                throw new IllegalStateException( "upsert returned no rows for signature " + cp.signature() );
            }
        } catch( final SQLException e ) {
            LOG.warn( "upsertConsolidatedProposal failed for signature {}: {}", cp.signature(), e.getMessage() );
            throw new RuntimeException( "upsert failed: " + e.getMessage(), e );
        }
    }

    private static Map< String, Object > buildProposedData( final ConsolidatedProposal cp ) {
        if( cp.kind() == ConsolidatedProposal.Kind.NEW_NODE ) {
            return Map.of( "name", cp.displayName(), "nodeType", cp.type() );
        }
        return Map.of( "source", cp.source(), "target", cp.target(),
                       "relationship", cp.predicate() );
    }

    /**
     * Deletes all knowledge graph data: edges, proposals, rejections, and nodes.
     * Tables are cleared in FK-safe order.
     */
    public void clearAll() {
        final String[] tables = {
            "kg_edges", "kg_proposals", "kg_rejections", "kg_nodes"
        };
        try( Connection conn = dataSource.getConnection();
             var stmt = conn.createStatement() ) {
            for( final String table : tables ) {
                stmt.execute( "DELETE FROM " + table );
            }
            LOG.info( "Cleared all knowledge graph data ({} tables)", tables.length );
        } catch( final SQLException e ) {
            LOG.warn( "Failed to clear knowledge graph data: {}", e.getMessage(), e );
            throw new RuntimeException( e );
        }
    }
}
