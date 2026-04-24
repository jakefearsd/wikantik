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

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import javax.sql.DataSource;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * Read-only view over {@code chunk_entity_mentions} — answers "is this node
 * mentioned by the extractor?" and "what nodes share chunks with this one?"
 * Provides the mention-covered subset that agent-facing MCP tools surface,
 * hiding nodes that exist in {@code kg_nodes} only because the legacy
 * {@code GraphProjector} put them there from frontmatter/links but no
 * extractor has confirmed them in real content.
 */
public class MentionIndex {

    private static final Logger LOG = LogManager.getLogger( MentionIndex.class );

    private static final String EXISTS_SQL =
        "SELECT 1 FROM chunk_entity_mentions WHERE node_id = ? LIMIT 1";

    private static final String ALL_MENTIONED_IDS_SQL =
        "SELECT DISTINCT node_id FROM chunk_entity_mentions";

    private static final String COMENTION_COUNTS_SQL =
        "SELECT m2.node_id, COUNT( DISTINCT m1.chunk_id ) AS shared "
      + "  FROM chunk_entity_mentions m1 "
      + "  JOIN chunk_entity_mentions m2 ON m1.chunk_id = m2.chunk_id "
      + " WHERE m1.node_id = ? AND m2.node_id <> m1.node_id "
      + " GROUP BY m2.node_id";

    private final DataSource dataSource;

    public MentionIndex( final DataSource dataSource ) {
        if ( dataSource == null ) throw new IllegalArgumentException( "dataSource required" );
        this.dataSource = dataSource;
    }

    public boolean isMentioned( final UUID nodeId ) {
        if ( nodeId == null ) return false;
        try ( final Connection c = dataSource.getConnection();
              final PreparedStatement ps = c.prepareStatement( EXISTS_SQL ) ) {
            ps.setObject( 1, nodeId );
            try ( final ResultSet rs = ps.executeQuery() ) {
                return rs.next();
            }
        } catch ( final SQLException e ) {
            LOG.warn( "MentionIndex.isMentioned failed for {}: {}", nodeId, e.getMessage(), e );
            return false;
        }
    }

    public Set< UUID > getMentionedIds() {
        final Set< UUID > out = new HashSet<>();
        try ( final Connection c = dataSource.getConnection();
              final PreparedStatement ps = c.prepareStatement( ALL_MENTIONED_IDS_SQL );
              final ResultSet rs = ps.executeQuery() ) {
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
        try ( final Connection c = dataSource.getConnection();
              final PreparedStatement ps = c.prepareStatement( COMENTION_COUNTS_SQL ) ) {
            ps.setObject( 1, nodeId );
            try ( final ResultSet rs = ps.executeQuery() ) {
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
}
