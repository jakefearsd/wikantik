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
package com.wikantik.search.hybrid;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import javax.sql.DataSource;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.Collection;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * Bulk loader for {@code pageName -> mentioned entity ids}. Resolves the
 * candidate page set to their {@code chunk_entity_mentions} rows in a single
 * SQL round-trip so the graph rerank step avoids N round-trips per query.
 *
 * <p>Joins {@code kg_content_chunks} to {@code chunk_entity_mentions} to map
 * chunk-level mentions back up to the page grain at which the search results
 * are ranked. Pages with no mentions are simply absent from the returned map
 * so callers can fold the output straight into {@link GraphProximityScorer}.
 * </p>
 */
public class PageMentionsLoader {

    private static final Logger LOG = LogManager.getLogger( PageMentionsLoader.class );

    private static final String SELECT_SQL =
        "SELECT c.page_name, m.node_id "
      + "  FROM kg_content_chunks c "
      + "  JOIN chunk_entity_mentions m ON m.chunk_id = c.id "
      + " WHERE c.page_name = ANY( ? )";

    private final DataSource dataSource;

    public PageMentionsLoader( final DataSource dataSource ) {
        if( dataSource == null ) throw new IllegalArgumentException( "dataSource must not be null" );
        this.dataSource = dataSource;
    }

    /**
     * Returns {@code pageName -> set of mentioned node ids} for pages that have
     * at least one mention. An empty input collection or a SQL failure yields
     * an empty map — the caller's rerank step treats empty mentions as "no
     * boost available" and degrades gracefully.
     */
    public Map< String, Set< UUID > > loadFor( final Collection< String > pageNames ) {
        if( pageNames == null || pageNames.isEmpty() ) return Map.of();

        final String[] nameArr = pageNames.toArray( new String[ 0 ] );
        final Map< String, Set< UUID > > out = new HashMap<>( nameArr.length * 2 );
        try( Connection c = dataSource.getConnection();
             PreparedStatement ps = c.prepareStatement( SELECT_SQL ) ) {
            ps.setArray( 1, c.createArrayOf( "text", nameArr ) );
            try( ResultSet rs = ps.executeQuery() ) {
                while( rs.next() ) {
                    final String page = rs.getString( 1 );
                    final UUID node = rs.getObject( 2, UUID.class );
                    if( page == null || node == null ) continue;
                    out.computeIfAbsent( page, k -> new HashSet<>() ).add( node );
                }
            }
        } catch( final SQLException e ) {
            LOG.warn( "PageMentionsLoader failed for {} pages: {}", nameArr.length, e.getMessage(), e );
            return Map.of();
        }
        return out;
    }
}
