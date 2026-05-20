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

import com.wikantik.kgpolicy.KgInclusionFilter;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import javax.sql.DataSource;
import java.sql.Array;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * Read-only view over {@code chunk_entity_mentions} — answers "is this node
 * mentioned by the extractor?" and "what nodes share chunks with this one?"
 * Provides the mention-covered subset that agent-facing MCP tools surface.
 * Nodes that exist in {@code kg_nodes} but have no extractor mention are
 * hidden — typically legacy frontmatter/link-derived nodes left over from
 * the retired projection pipeline.
 */
public class MentionIndex {

    private static final Logger LOG = LogManager.getLogger( MentionIndex.class );

    private static final String EXISTS_SQL =
        "SELECT 1 FROM chunk_entity_mentions m"
        + KgInclusionFilter.MENTION_FILTER_JOIN
        + "WHERE m.node_id = ? AND"
        + KgInclusionFilter.MENTION_FILTER_WHERE
        + "LIMIT 1";

    private static final String ALL_MENTIONED_IDS_SQL =
        "SELECT DISTINCT m.node_id FROM chunk_entity_mentions m"
        + KgInclusionFilter.MENTION_FILTER_JOIN
        + "WHERE" + KgInclusionFilter.MENTION_FILTER_WHERE;

    private static final String COMENTION_COUNTS_SQL =
        "SELECT m2.node_id, COUNT( DISTINCT m1.chunk_id ) AS shared "
      + "  FROM chunk_entity_mentions m1 "
      + "  JOIN kg_content_chunks c     ON c.id = m1.chunk_id "
      + "  LEFT JOIN kg_excluded_pages kgxm  ON c.page_name = kgxm.page_name "
      + "  JOIN chunk_entity_mentions m2 ON m1.chunk_id = m2.chunk_id "
      + " WHERE m1.node_id = ? AND m2.node_id <> m1.node_id "
      + "   AND kgxm.page_name IS NULL "
      + " GROUP BY m2.node_id";

    private final DataSource dataSource;

    public MentionIndex( final DataSource dataSource ) {
        if ( dataSource == null ) throw new IllegalArgumentException( "dataSource required" );
        this.dataSource = dataSource;
    }

    public boolean isMentioned( final UUID nodeId ) {
        if ( nodeId == null ) return false;
        try ( Connection c = dataSource.getConnection();
              PreparedStatement ps = c.prepareStatement( EXISTS_SQL ) ) {
            ps.setObject( 1, nodeId );
            try ( ResultSet rs = ps.executeQuery() ) {
                return rs.next();
            }
        } catch ( final SQLException e ) {
            LOG.warn( "MentionIndex.isMentioned failed for {}: {}", nodeId, e.getMessage(), e );
            return false;
        }
    }

    public Set< UUID > getMentionedIds() {
        final Set< UUID > out = new HashSet<>();
        try ( Connection c = dataSource.getConnection();
              PreparedStatement ps = c.prepareStatement( ALL_MENTIONED_IDS_SQL );
              ResultSet rs = ps.executeQuery() ) {
            while ( rs.next() ) {
                out.add( rs.getObject( 1, UUID.class ) );
            }
        } catch ( final SQLException e ) {
            LOG.warn( "MentionIndex.getMentionedIds failed: {}", e.getMessage(), e );
            return Set.of();
        }
        return out;
    }

    public Map< UUID, Integer > getCoMentionCounts( final UUID nodeId ) {
        if ( nodeId == null ) return Map.of();
        final Map< UUID, Integer > counts = new HashMap<>();
        try ( Connection c = dataSource.getConnection();
              PreparedStatement ps = c.prepareStatement( COMENTION_COUNTS_SQL ) ) {
            ps.setObject( 1, nodeId );
            try ( ResultSet rs = ps.executeQuery() ) {
                while ( rs.next() ) {
                    counts.put( rs.getObject( 1, UUID.class ), rs.getInt( 2 ) );
                }
            }
        } catch ( final SQLException e ) {
            LOG.warn( "MentionIndex.getCoMentionCounts failed for {}: {}", nodeId, e.getMessage(), e );
            return Map.of();
        }
        final Map< UUID, Integer > sorted = new LinkedHashMap<>();
        counts.entrySet().stream()
            .sorted( ( a, b ) -> {
                final int byCount = Integer.compare( b.getValue(), a.getValue() );
                return byCount != 0 ? byCount : a.getKey().compareTo( b.getKey() );
            } )
            .forEach( e -> sorted.put( e.getKey(), e.getValue() ) );
        return sorted;
    }

    /**
     * A page that shares at least one extractor-mention with the query page,
     * along with the names of the shared entity nodes and the count of distinct
     * shared entities. Result ordering in {@link #findRelatedPages} uses
     * {@code sharedCount} DESC, then {@code pageName} ASC for determinism.
     */
    public record RelatedByMention(
        String pageName,
        List< String > sharedEntityNames,
        int sharedCount
    ) {
        public RelatedByMention {
            if ( pageName == null || pageName.isBlank() ) {
                throw new IllegalArgumentException( "pageName must not be blank" );
            }
            sharedEntityNames = sharedEntityNames == null
                ? List.of() : List.copyOf( sharedEntityNames );
            if ( sharedCount < 0 ) {
                throw new IllegalArgumentException( "sharedCount must not be negative" );
            }
        }
    }

    /**
     * Finds pages connected to {@code pageName} via shared entity mentions.
     * Joins {@code kg_content_chunks} → {@code chunk_entity_mentions} for both
     * sides and groups the other-side rows by page, counting distinct
     * co-mentioned entities. Returns up to {@code limit} pages ordered by
     * descending shared-entity count (ties broken by page name).
     *
     * <p>Unlike {@link com.wikantik.knowledge.embedding.NodeMentionSimilarity#similarTo(String, int)},
     * this reads the mention table directly and does not require a KG node
     * keyed by the page name. It matches the post-cycle-6 data model where
     * pages exist only as {@code kg_content_chunks.page_name} values and
     * related-ness is evidenced by co-mention of extractor-found entities.</p>
     */
    public List< RelatedByMention > findRelatedPages( final String pageName, final int limit ) {
        if ( pageName == null || pageName.isBlank() || limit <= 0 ) {
            return List.of();
        }
        final String sql =
            "SELECT other_c.page_name, "
          + "       ARRAY_AGG( DISTINCT n.name ) AS shared_entities, "
          + "       COUNT( DISTINCT my_m.node_id ) AS shared_count "
          + "  FROM kg_content_chunks my_c "
          + "  JOIN chunk_entity_mentions my_m ON my_m.chunk_id = my_c.id "
          + "  JOIN chunk_entity_mentions other_m ON other_m.node_id = my_m.node_id "
          + "  JOIN kg_content_chunks other_c ON other_c.id = other_m.chunk_id "
          + "  JOIN kg_nodes n ON n.id = my_m.node_id "
          + "  LEFT JOIN kg_excluded_pages kgxm_my    ON my_c.page_name    = kgxm_my.page_name "
          + "  LEFT JOIN kg_excluded_pages kgxm_other ON other_c.page_name = kgxm_other.page_name "
          + " WHERE my_c.page_name = ? "
          + "   AND other_c.page_name <> my_c.page_name "
          + "   AND kgxm_my.page_name    IS NULL "
          + "   AND kgxm_other.page_name IS NULL "
          + " GROUP BY other_c.page_name "
          + " ORDER BY shared_count DESC, other_c.page_name ASC "
          + " LIMIT ?";
        final List< RelatedByMention > out = new ArrayList<>();
        try ( Connection c = dataSource.getConnection();
              PreparedStatement ps = c.prepareStatement( sql ) ) {
            ps.setString( 1, pageName );
            ps.setInt( 2, limit );
            try ( ResultSet rs = ps.executeQuery() ) {
                while ( rs.next() ) {
                    final String otherPage = rs.getString( 1 );
                    final Array arr = rs.getArray( 2 );
                    final List< String > shared = arrayToStringList( arr );
                    final int count = rs.getInt( 3 );
                    out.add( new RelatedByMention( otherPage, shared, count ) );
                }
            }
        } catch ( final SQLException e ) {
            LOG.warn( "MentionIndex.findRelatedPages failed for '{}': {}",
                pageName, e.getMessage(), e );
            return List.of();
        }
        return out;
    }

    /**
     * Batched variant of {@link #findRelatedPages(String, int)}. Returns a
     * map keyed by input page name; missing keys (pages with no related
     * matches) map to an empty list. Behaviour per page is identical to a
     * stand-alone {@code findRelatedPages} call — only the I/O shape changes:
     * one borrowed connection and one prepared statement reused across the
     * N executions, instead of N connection acquires and N statement preps.
     *
     * <p>This eliminates the N+1 lookup pattern that
     * {@code DefaultContextRetrievalService} used in its result-building
     * loop (one call per result page). Under saturation the connection-pool
     * contention from the per-page acquires was a measurable cost; this
     * collapses it to one acquire for the whole search response.</p>
     *
     * <p>The single-name {@link #findRelatedPages} method is retained
     * unchanged for any callers that only need one page.</p>
     */
    public Map< String, List< RelatedByMention > > findRelatedPagesBatch(
            final List< String > pageNames, final int limit ) {
        if ( pageNames == null || pageNames.isEmpty() || limit <= 0 ) {
            return Map.of();
        }
        final String sql =
            "SELECT other_c.page_name, "
          + "       ARRAY_AGG( DISTINCT n.name ) AS shared_entities, "
          + "       COUNT( DISTINCT my_m.node_id ) AS shared_count "
          + "  FROM kg_content_chunks my_c "
          + "  JOIN chunk_entity_mentions my_m ON my_m.chunk_id = my_c.id "
          + "  JOIN chunk_entity_mentions other_m ON other_m.node_id = my_m.node_id "
          + "  JOIN kg_content_chunks other_c ON other_c.id = other_m.chunk_id "
          + "  JOIN kg_nodes n ON n.id = my_m.node_id "
          + "  LEFT JOIN kg_excluded_pages kgxm_my    ON my_c.page_name    = kgxm_my.page_name "
          + "  LEFT JOIN kg_excluded_pages kgxm_other ON other_c.page_name = kgxm_other.page_name "
          + " WHERE my_c.page_name = ? "
          + "   AND other_c.page_name <> my_c.page_name "
          + "   AND kgxm_my.page_name    IS NULL "
          + "   AND kgxm_other.page_name IS NULL "
          + " GROUP BY other_c.page_name "
          + " ORDER BY shared_count DESC, other_c.page_name ASC "
          + " LIMIT ?";
        final Map< String, List< RelatedByMention > > out = new LinkedHashMap<>();
        // Pre-seed an empty list for every input name so callers can rely on
        // map.get(name) never returning null. Pages with zero related matches
        // stay as the empty list set here.
        for ( final String name : pageNames ) {
            if ( name != null && !name.isBlank() ) out.put( name, List.of() );
        }
        if ( out.isEmpty() ) return Map.of();
        try ( Connection c = dataSource.getConnection();
              PreparedStatement ps = c.prepareStatement( sql ) ) {
            for ( final String pageName : out.keySet() ) {
                ps.setString( 1, pageName );
                ps.setInt( 2, limit );
                final List< RelatedByMention > forPage = new ArrayList<>();
                try ( ResultSet rs = ps.executeQuery() ) {
                    while ( rs.next() ) {
                        final String otherPage = rs.getString( 1 );
                        final Array arr = rs.getArray( 2 );
                        final List< String > shared = arrayToStringList( arr );
                        final int count = rs.getInt( 3 );
                        forPage.add( new RelatedByMention( otherPage, shared, count ) );
                    }
                }
                ps.clearParameters();
                if ( !forPage.isEmpty() ) out.put( pageName, forPage );
            }
        } catch ( final SQLException e ) {
            LOG.warn( "MentionIndex.findRelatedPagesBatch failed for {} pages: {}",
                pageNames.size(), e.getMessage(), e );
            // Fall back to a per-name retry loop on the next call shape: callers
            // can simply re-attempt via single-name findRelatedPages if the
            // batched path failed. We return what we managed to compute so far
            // rather than zeroing out all results.
        }
        return out;
    }

    private static List< String > arrayToStringList( final Array array ) throws SQLException {
        if ( array == null ) return List.of();
        final Object raw = array.getArray();
        if ( !( raw instanceof String[] items ) ) return List.of();
        final List< String > out = new ArrayList<>( items.length );
        for ( final String s : items ) {
            if ( s != null ) out.add( s );
        }
        return List.copyOf( out );
    }
}
