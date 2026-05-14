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
import edu.umd.cs.findbugs.annotations.SuppressFBWarnings;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import javax.sql.DataSource;
import java.sql.*;
import java.util.*;

/**
 * JDBC repository for {@code kg_nodes} table operations.
 *
 * <p>Extracted from the former JdbcKnowledgeRepository facade in Phase 3 Checkpoint 3 of the
 * wikantik-main subsystem decomposition. SQL strings are verbatim copies; no behaviour
 * change is introduced.</p>
 *
 * @since 1.0
 */
public final class KgNodeRepository extends KgJdbcSupport {

    private static final Logger LOG = LogManager.getLogger( KgNodeRepository.class );

    public KgNodeRepository( final DataSource dataSource ) {
        super( dataSource );
    }

    @Override
    protected Logger log() { return LOG; }

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

    // ---- Package-private SQL builders (used by bypass overloads + test introspection) ----

    /**
     * Package-private SQL builder for {@link #queryNodes}. Allows test introspection of
     * the generated SQL without standing up a database connection.
     */
    @SuppressFBWarnings( value = "SQL_PREPARED_STATEMENT_GENERATED_FROM_NONCONSTANT_STRING",
            justification = "SQL fragments are all string literals; only '?' placeholders are appended conditionally. All user values bound via PreparedStatement.setObject." )
    static String buildQueryNodesSql( final Map< String, Object > filters,
                                      final Set< Provenance > provenanceFilter,
                                      final boolean adminBypass ) {
        final StringBuilder sql = new StringBuilder( "SELECT n.* FROM kg_nodes n" )
                .append( KgInclusionFilter.nodeFilterJoin( adminBypass ) )
                .append( " WHERE" ).append( KgInclusionFilter.nodeFilterWhere( adminBypass ) );
        if ( filters != null ) {
            if ( filters.containsKey( "node_type" ) ) sql.append( " AND n.node_type = ?" );
            if ( filters.containsKey( "source_page" ) ) sql.append( " AND n.source_page = ?" );
            if ( filters.containsKey( "name" ) ) sql.append( " AND LOWER( n.name ) LIKE ?" );
            if ( filters.containsKey( "status" ) ) sql.append( " AND n.properties->>'status' = ?" );
        }
        if ( provenanceFilter != null && !provenanceFilter.isEmpty() ) {
            sql.append( " AND n.provenance IN (" );
            final StringJoiner sj = new StringJoiner( ", " );
            for ( int i = 0; i < provenanceFilter.size(); i++ ) sj.add( "?" );
            sql.append( sj ).append( ')' );
        }
        sql.append( " ORDER BY n.name LIMIT ? OFFSET ?" );
        return sql.toString();
    }

    /**
     * Package-private SQL builder for {@link #searchNodes}. Allows test introspection of
     * the generated SQL without standing up a database connection.
     */
    @SuppressFBWarnings( value = "SQL_PREPARED_STATEMENT_GENERATED_FROM_NONCONSTANT_STRING",
            justification = "SQL fragments are all string literals; only '?' placeholders are appended conditionally. All user values bound via PreparedStatement.setObject." )
    static String buildSearchNodesSql( final Set< Provenance > provenanceFilter,
                                       final boolean adminBypass ) {
        final StringBuilder sql = new StringBuilder( "SELECT n.* FROM kg_nodes n" )
                .append( KgInclusionFilter.nodeFilterJoin( adminBypass ) )
                .append( " WHERE" ).append( KgInclusionFilter.nodeFilterWhere( adminBypass ) )
                .append( " AND ( LOWER( n.name ) LIKE ? OR LOWER( n.properties::text ) LIKE ? )" );
        if ( provenanceFilter != null && !provenanceFilter.isEmpty() ) {
            sql.append( " AND n.provenance IN (" );
            final StringJoiner sj = new StringJoiner( ", " );
            for ( int i = 0; i < provenanceFilter.size(); i++ ) sj.add( "?" );
            sql.append( sj ).append( ')' );
        }
        sql.append( " ORDER BY n.name LIMIT ?" );
        return sql.toString();
    }

    // ---- Node operations ----

    public KgNode upsertNode( final String name, final String nodeType, final String sourcePage,
                              final Provenance provenance, final Map< String, Object > properties ) {
        final String propsJson = GSON.toJson( properties != null ? properties : Map.of() );
        final String sql = "INSERT INTO kg_nodes ( name, node_type, source_page, provenance, properties, tier, provenance_proposal_id, modified ) "
                + "VALUES ( ?, ?, ?, ?, ?::jsonb, 'human', NULL, CURRENT_TIMESTAMP ) "
                + "ON CONFLICT ( name ) DO UPDATE SET node_type = EXCLUDED.node_type, "
                + "source_page = COALESCE(EXCLUDED.source_page, kg_nodes.source_page), provenance = EXCLUDED.provenance, "
                + "properties = EXCLUDED.properties, modified = CURRENT_TIMESTAMP";
        try ( Connection conn = dataSource.getConnection();
              PreparedStatement ps = conn.prepareStatement( sql ) ) {
            ps.setString( 1, name );
            ps.setString( 2, nodeType );
            ps.setString( 3, sourcePage );
            ps.setString( 4, provenance.value() );
            ps.setString( 5, propsJson );
            ps.executeUpdate();
        } catch ( final SQLException e ) {
            LOG.warn( "Failed to upsert node '{}': {}", name, e.getMessage(), e );
            throw new RuntimeException( "Failed to upsert node: " + e.getMessage(), e );
        }
        return getNodeByName( name );
    }

    public KgNode getNode( final UUID id ) {
        return getNode( id, false );
    }

    public KgNode getNode( final UUID id, final boolean adminBypass ) {
        final String sql = "SELECT n.* FROM kg_nodes n"
                + KgInclusionFilter.nodeFilterJoin( adminBypass )
                + " WHERE" + KgInclusionFilter.nodeFilterWhere( adminBypass )
                + " AND n.id = ?";
        try ( Connection conn = dataSource.getConnection();
              PreparedStatement ps = conn.prepareStatement( sql ) ) {
            ps.setObject( 1, id );
            try ( ResultSet rs = ps.executeQuery() ) {
                return rs.next() ? mapNode( rs ) : null;
            }
        } catch ( final SQLException e ) {
            LOG.warn( "Failed to get node by id '{}': {}", id, e.getMessage(), e );
            throw new RuntimeException( e );
        }
    }

    public KgNode getNodeByName( final String name ) {
        return getNodeByName( name, false );
    }

    public KgNode getNodeByName( final String name, final boolean adminBypass ) {
        final String sql = "SELECT n.* FROM kg_nodes n"
                + KgInclusionFilter.nodeFilterJoin( adminBypass )
                + " WHERE" + KgInclusionFilter.nodeFilterWhere( adminBypass )
                + " AND n.name = ?";
        try ( Connection conn = dataSource.getConnection();
              PreparedStatement ps = conn.prepareStatement( sql ) ) {
            ps.setString( 1, name );
            try ( ResultSet rs = ps.executeQuery() ) {
                return rs.next() ? mapNode( rs ) : null;
            }
        } catch ( final SQLException e ) {
            LOG.warn( "Failed to get node by name '{}': {}", name, e.getMessage(), e );
            throw new RuntimeException( e );
        }
    }

    public void deleteNode( final UUID id ) {
        final String sql = "DELETE FROM kg_nodes WHERE id = ?";
        try ( Connection conn = dataSource.getConnection();
              PreparedStatement ps = conn.prepareStatement( sql ) ) {
            ps.setObject( 1, id );
            ps.executeUpdate();
        } catch ( final SQLException e ) {
            LOG.warn( "Failed to delete node '{}': {}", id, e.getMessage(), e );
            throw new RuntimeException( "Failed to delete node: " + e.getMessage(), e );
        }
    }

    public List< KgNode > queryNodes( final Map< String, Object > filters,
                                      final Set< Provenance > provenanceFilter,
                                      final int limit, final int offset ) {
        return queryNodes( filters, provenanceFilter, limit, offset, false );
    }

    @SuppressFBWarnings( value = "SQL_PREPARED_STATEMENT_GENERATED_FROM_NONCONSTANT_STRING",
            justification = "SQL fragments are all string literals; only '?' placeholders are appended conditionally. All user values bound via PreparedStatement.setObject." )
    public List< KgNode > queryNodes( final Map< String, Object > filters,
                                      final Set< Provenance > provenanceFilter,
                                      final int limit, final int offset,
                                      final boolean adminBypass ) {
        final String sql = buildQueryNodesSql( filters, provenanceFilter, adminBypass );
        final List< Object > params = new ArrayList<>();

        if ( filters != null ) {
            if ( filters.containsKey( "node_type" ) ) params.add( filters.get( "node_type" ) );
            if ( filters.containsKey( "source_page" ) ) params.add( filters.get( "source_page" ) );
            if ( filters.containsKey( "name" ) ) {
                params.add( "%" + filters.get( "name" ).toString().toLowerCase( Locale.ROOT ) + "%" );
            }
            if ( filters.containsKey( "status" ) ) params.add( filters.get( "status" ) );
        }

        if ( provenanceFilter != null && !provenanceFilter.isEmpty() ) {
            for ( final Provenance p : provenanceFilter ) params.add( p.value() );
        }

        params.add( limit );
        params.add( offset );

        final List< KgNode > results = new ArrayList<>();
        try ( Connection conn = dataSource.getConnection();
              PreparedStatement ps = conn.prepareStatement( sql ) ) {
            for ( int i = 0; i < params.size(); i++ ) {
                ps.setObject( i + 1, params.get( i ) );
            }
            try ( ResultSet rs = ps.executeQuery() ) {
                while ( rs.next() ) results.add( mapNode( rs ) );
            }
        } catch ( final SQLException e ) {
            LOG.warn( "Failed to query nodes: {}", e.getMessage(), e );
            throw new RuntimeException( e );
        }
        return results;
    }

    public List< KgNode > searchNodes( final String query, final Set< Provenance > provenanceFilter,
                                       final int limit ) {
        return searchNodes( query, provenanceFilter, limit, false );
    }

    @SuppressFBWarnings( value = "SQL_PREPARED_STATEMENT_GENERATED_FROM_NONCONSTANT_STRING",
            justification = "SQL fragments are all string literals; only '?' placeholders are appended conditionally. All user values bound via PreparedStatement.setObject." )
    public List< KgNode > searchNodes( final String query, final Set< Provenance > provenanceFilter,
                                       final int limit, final boolean adminBypass ) {
        final String sql = buildSearchNodesSql( provenanceFilter, adminBypass );
        final List< Object > params = new ArrayList<>();
        final String pattern = "%" + query.toLowerCase( Locale.ROOT ) + "%";
        params.add( pattern );
        params.add( pattern );

        if ( provenanceFilter != null && !provenanceFilter.isEmpty() ) {
            for ( final Provenance p : provenanceFilter ) params.add( p.value() );
        }

        params.add( limit );

        final List< KgNode > results = new ArrayList<>();
        try ( Connection conn = dataSource.getConnection();
              PreparedStatement ps = conn.prepareStatement( sql ) ) {
            for ( int i = 0; i < params.size(); i++ ) {
                ps.setObject( i + 1, params.get( i ) );
            }
            try ( ResultSet rs = ps.executeQuery() ) {
                while ( rs.next() ) results.add( mapNode( rs ) );
            }
        } catch ( final SQLException e ) {
            LOG.warn( "Failed to search nodes: {}", e.getMessage(), e );
            throw new RuntimeException( e );
        }
        return results;
    }

    @SuppressFBWarnings( value = "SQL_PREPARED_STATEMENT_GENERATED_FROM_NONCONSTANT_STRING",
            justification = "SQL fragments are all string literals; only '?' placeholders are appended conditionally. All user values bound via PreparedStatement.setObject." )
    public List< KgNode > searchNodes( final String query, final Set< Provenance > provenanceFilter,
                                       final int limit, final Tier minTier ) {
        final StringBuilder sql = new StringBuilder( "SELECT n.* FROM kg_nodes n" )
                .append( KgInclusionFilter.NODE_FILTER_JOIN )
                .append( "WHERE" ).append( KgInclusionFilter.NODE_FILTER_WHERE )
                .append( " AND ( LOWER( n.name ) LIKE ? OR LOWER( n.properties::text ) LIKE ? )" )
                .append( " AND n.tier = ANY( ? )" );
        final List< Object > params = new ArrayList<>();
        final String pattern = "%" + query.toLowerCase( Locale.ROOT ) + "%";
        params.add( pattern );
        params.add( pattern );
        params.add( minTier ); // sentinel — replaced by setArray below

        if ( provenanceFilter != null && !provenanceFilter.isEmpty() ) {
            sql.append( " AND n.provenance IN (" );
            final StringJoiner sj = new StringJoiner( ", " );
            for ( final Provenance p : provenanceFilter ) {
                sj.add( "?" );
                params.add( p.value() );
            }
            sql.append( sj );
            sql.append( ')' );
        }

        sql.append( " ORDER BY n.name LIMIT ?" );
        params.add( limit );

        final List< KgNode > results = new ArrayList<>();
        try ( Connection conn = dataSource.getConnection();
              PreparedStatement ps = conn.prepareStatement( sql.toString() ) ) {
            for ( int i = 0; i < params.size(); i++ ) {
                final Object v = params.get( i );
                if ( v instanceof Tier t ) {
                    ps.setArray( i + 1, conn.createArrayOf( "varchar", t.includedTiers().toArray() ) );
                } else {
                    ps.setObject( i + 1, v );
                }
            }
            try ( ResultSet rs = ps.executeQuery() ) {
                while ( rs.next() ) results.add( mapNode( rs ) );
            }
        } catch ( final SQLException e ) {
            LOG.warn( "Failed to search nodes (tier={}): {}", minTier.wireName(), e.getMessage(), e );
            throw new RuntimeException( e );
        }
        return results;
    }

    public List< KgNode > getAllNodes() {
        final String sql = "SELECT n.* FROM kg_nodes n"
                + KgInclusionFilter.NODE_FILTER_JOIN
                + "WHERE" + KgInclusionFilter.NODE_FILTER_WHERE
                + "ORDER BY n.id";
        final List< KgNode > results = new ArrayList<>();
        try ( Connection conn = dataSource.getConnection();
              PreparedStatement ps = conn.prepareStatement( sql );
              ResultSet rs = ps.executeQuery() ) {
            while ( rs.next() ) results.add( mapNode( rs ) );
        } catch ( final SQLException e ) {
            LOG.warn( "Failed to get all nodes: {}", e.getMessage(), e );
            throw new RuntimeException( e );
        }
        return results;
    }

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

    public KgNode upsertNodeWithProvenance( final String name, final String nodeType,
                                            final String sourcePage, final Provenance provenance,
                                            final Map< String, Object > properties,
                                            final String tier, final UUID provenanceProposalId ) {
        final String propsJson = GSON.toJson( properties != null ? properties : Map.of() );
        final String sql = "INSERT INTO kg_nodes ( name, node_type, source_page, provenance, properties, " +
            "tier, provenance_proposal_id, modified ) " +
            "VALUES ( ?, ?, ?, ?, ?::jsonb, ?, ?, CURRENT_TIMESTAMP ) " +
            "ON CONFLICT ( name ) DO UPDATE SET node_type = EXCLUDED.node_type, " +
            "source_page = COALESCE(EXCLUDED.source_page, kg_nodes.source_page), " +
            "provenance = EXCLUDED.provenance, properties = EXCLUDED.properties, " +
            "modified = CURRENT_TIMESTAMP";
        try ( Connection conn = dataSource.getConnection();
              PreparedStatement ps = conn.prepareStatement( sql ) ) {
            ps.setString( 1, name );
            ps.setString( 2, nodeType );
            ps.setString( 3, sourcePage );
            ps.setString( 4, provenance.value() );
            ps.setString( 5, propsJson );
            ps.setString( 6, tier );
            if ( provenanceProposalId == null ) ps.setNull( 7, java.sql.Types.OTHER );
            else ps.setObject( 7, provenanceProposalId );
            ps.executeUpdate();
        } catch ( final SQLException e ) {
            LOG.warn( "Failed to upsertNodeWithProvenance '{}': {}", name, e.getMessage(), e );
            throw new RuntimeException( "Failed to upsertNodeWithProvenance: " + e.getMessage(), e );
        }
        return getNodeByName( name );
    }

    public int deleteNodesByProvenance( final UUID proposalId ) {
        return executeDelete( "DELETE FROM kg_nodes WHERE provenance_proposal_id = ?", proposalId );
    }

    public List< String > getDistinctNodeTypes() {
        return queryDistinct( "SELECT DISTINCT node_type FROM kg_nodes WHERE node_type IS NOT NULL ORDER BY node_type" );
    }

    public long countNodes() {
        return queryCount( "SELECT COUNT(*) FROM kg_nodes" );
    }

    /**
     * Counts nodes that would be returned by {@link #queryNodes(Map, Set, int, int)}
     * with the same filter arguments. Used by the admin UI for the table footer
     * total alongside the paginated list. Mirrors queryNodes's filter machinery byte
     * for byte so the count cannot drift from the listing.
     */
    public long countNodesWithFilter( final Map< String, Object > filters,
                                       final Set< Provenance > provenanceFilter ) {
        final StringBuilder sql = new StringBuilder( "SELECT COUNT(*) FROM kg_nodes n" )
                .append( KgInclusionFilter.NODE_FILTER_JOIN )
                .append( "WHERE" ).append( KgInclusionFilter.NODE_FILTER_WHERE );
        final List< Object > params = new ArrayList<>();

        if ( filters != null ) {
            if ( filters.containsKey( "node_type" ) ) {
                sql.append( " AND n.node_type = ?" );
                params.add( filters.get( "node_type" ) );
            }
            if ( filters.containsKey( "source_page" ) ) {
                sql.append( " AND n.source_page = ?" );
                params.add( filters.get( "source_page" ) );
            }
            if ( filters.containsKey( "name" ) ) {
                sql.append( " AND LOWER( n.name ) LIKE ?" );
                params.add( "%" + filters.get( "name" ).toString().toLowerCase( Locale.ROOT ) + "%" );
            }
            if ( filters.containsKey( "status" ) ) {
                sql.append( " AND n.properties->>'status' = ?" );
                params.add( filters.get( "status" ) );
            }
        }

        if ( provenanceFilter != null && !provenanceFilter.isEmpty() ) {
            sql.append( " AND n.provenance IN (" );
            final StringJoiner sj = new StringJoiner( ", " );
            for ( final Provenance p : provenanceFilter ) {
                sj.add( "?" );
                params.add( p.value() );
            }
            sql.append( sj.toString() ).append( ")" );
        }

        try ( Connection conn = dataSource.getConnection();
              PreparedStatement ps = conn.prepareStatement( sql.toString() ) ) {
            for ( int i = 0; i < params.size(); i++ ) ps.setObject( i + 1, params.get( i ) );
            try ( ResultSet rs = ps.executeQuery() ) {
                return rs.next() ? rs.getLong( 1 ) : 0L;
            }
        } catch ( final SQLException e ) {
            LOG.warn( "countNodesWithFilter failed: {}", e.getMessage(), e );
            throw new RuntimeException( e );
        }
    }

    /**
     * Lists orphaned nodes — nodes that have no incident edges in {@code kg_edges}
     * (neither incoming nor outgoing). Always runs with the {@code kg_excluded_pages}
     * filter bypassed (admin tool). Optional filters:
     * <ul>
     *   <li>{@code node_type} — exact match</li>
     *   <li>{@code source_page} — exact match</li>
     *   <li>{@code source_page_excluded} — three-state {@link Boolean}; see
     *       {@link com.wikantik.api.knowledge.KnowledgeGraphService#listOrphanedNodes}
     *       for the full spec.</li>
     * </ul>
     */
    @SuppressFBWarnings( value = "SQL_PREPARED_STATEMENT_GENERATED_FROM_NONCONSTANT_STRING",
            justification = "SQL fragments are all string literals; only '?' placeholders are appended conditionally. All user values bound via PreparedStatement.setObject." )
    public List< KgNode > listOrphanedNodes( final Map< String, Object > filters,
                                              final int limit, final int offset ) {
        final List< Object > params = new ArrayList<>();
        final String sql = buildOrphanedNodesSql( filters, false, params )
                + " ORDER BY n.name LIMIT ? OFFSET ?";
        params.add( limit );
        params.add( offset );

        final List< KgNode > results = new ArrayList<>();
        try ( Connection conn = dataSource.getConnection();
              PreparedStatement ps = conn.prepareStatement( sql ) ) {
            for ( int i = 0; i < params.size(); i++ ) ps.setObject( i + 1, params.get( i ) );
            try ( ResultSet rs = ps.executeQuery() ) {
                while ( rs.next() ) results.add( mapNode( rs ) );
            }
        } catch ( final SQLException e ) {
            LOG.warn( "listOrphanedNodes failed: {}", e.getMessage(), e );
            throw new RuntimeException( e );
        }
        return results;
    }

    /** Counts orphaned nodes matching the same filters as {@link #listOrphanedNodes}. */
    @SuppressFBWarnings( value = "SQL_PREPARED_STATEMENT_GENERATED_FROM_NONCONSTANT_STRING",
            justification = "SQL fragments are all string literals; only '?' placeholders are appended conditionally. All user values bound via PreparedStatement.setObject." )
    public long countOrphanedNodes( final Map< String, Object > filters ) {
        final List< Object > params = new ArrayList<>();
        final String sql = buildOrphanedNodesSql( filters, true, params );
        try ( Connection conn = dataSource.getConnection();
              PreparedStatement ps = conn.prepareStatement( sql ) ) {
            for ( int i = 0; i < params.size(); i++ ) ps.setObject( i + 1, params.get( i ) );
            try ( ResultSet rs = ps.executeQuery() ) {
                return rs.next() ? rs.getLong( 1 ) : 0L;
            }
        } catch ( final SQLException e ) {
            LOG.warn( "countOrphanedNodes failed: {}", e.getMessage(), e );
            throw new RuntimeException( e );
        }
    }

    /** Shared builder for orphan list/count queries. Appends bound params to {@code params}. */
    private static String buildOrphanedNodesSql( final Map< String, Object > filters,
                                                  final boolean countOnly,
                                                  final List< Object > params ) {
        final StringBuilder sql = new StringBuilder();
        sql.append( countOnly ? "SELECT COUNT(*) FROM kg_nodes n " : "SELECT n.* FROM kg_nodes n " )
           .append( "LEFT JOIN kg_excluded_pages kgxn ON n.source_page = kgxn.page_name " )
           .append( "WHERE NOT EXISTS ( SELECT 1 FROM kg_edges e WHERE e.source_id = n.id OR e.target_id = n.id )" );

        if ( filters != null ) {
            if ( filters.containsKey( "node_type" ) ) {
                sql.append( " AND n.node_type = ?" );
                params.add( filters.get( "node_type" ) );
            }
            if ( filters.containsKey( "source_page" ) ) {
                sql.append( " AND n.source_page = ?" );
                params.add( filters.get( "source_page" ) );
            }
            final Object excluded = filters.get( "source_page_excluded" );
            if ( excluded instanceof Boolean b ) {
                if ( b ) {
                    // Excluded=true: page is on kg_excluded_pages OR node is a stub (no source_page)
                    sql.append( " AND ( n.source_page IS NULL OR kgxn.page_name IS NOT NULL )" );
                } else {
                    // Excluded=false: page is NOT on kg_excluded_pages (this also keeps stubs since
                    // a stub's LEFT JOIN yields kgxn.page_name IS NULL — they cannot be classified
                    // either way, so they remain visible regardless of the filter direction).
                    sql.append( " AND kgxn.page_name IS NULL" );
                }
            }
        }
        return sql.toString();
    }

    public Map< UUID, String > getNodeNames( final Collection< UUID > ids ) {
        if ( ids == null || ids.isEmpty() ) return Map.of();
        final String placeholders = String.join( ", ", Collections.nCopies( ids.size(), "?" ) );
        final String sql = "SELECT n.id, n.name FROM kg_nodes n"
                + KgInclusionFilter.NODE_FILTER_JOIN
                + "WHERE n.id IN ( " + placeholders + " )"
                + " AND" + KgInclusionFilter.NODE_FILTER_WHERE;
        final Map< UUID, String > result = new HashMap<>();
        try ( Connection conn = dataSource.getConnection();
              PreparedStatement ps = conn.prepareStatement( sql ) ) {
            int idx = 1;
            for ( final UUID id : ids ) ps.setObject( idx++, id );
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
}
