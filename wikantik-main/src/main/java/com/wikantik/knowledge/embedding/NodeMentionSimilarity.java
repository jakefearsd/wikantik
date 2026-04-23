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
package com.wikantik.knowledge.embedding;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import javax.sql.DataSource;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

/**
 * Derives per-KG-node vectors as the centroid of their mention-chunk vectors
 * from {@code content_chunk_embeddings}. Replaces the legacy TF-IDF
 * {@code kg_content_embeddings} stack so KG node similarity shares the same
 * vector space (and the same Ollama-backed cadence) as the search path.
 *
 * <p>A node's vector is defined as the L2-normalized sum of the L2-normalized
 * mention-chunk vectors (via {@code chunk_entity_mentions}). Chunks whose
 * embedding rows are missing are silently skipped — the mention table is
 * populated by the extractor pipeline (Phase 2) and can race ahead of the
 * embedding indexer. A node with no mentions yields {@link Optional#empty()}.
 *
 * <p>Every call issues a scoped SQL query; there is no in-process cache yet.
 * For the read volumes we see in hub-discovery / find-similar (a few hundred
 * nodes, tens of candidates per run) this is cheap and simple. A caching layer
 * can be layered on later if needed — the API is deliberately small so that
 * substitution stays mechanical.
 */
public class NodeMentionSimilarity {

    private static final Logger LOG = LogManager.getLogger( NodeMentionSimilarity.class );

    /** A scored node name, used by similarity listings. */
    public record ScoredName( String name, double score ) {}

    private static final String SELECT_DIM_SQL =
        "SELECT dim FROM content_chunk_embeddings WHERE model_code = ? LIMIT 1";

    /**
     * Fetches every chunk vector mentioned by the node with the given name.
     * Join kg_nodes → chunk_entity_mentions → content_chunk_embeddings, filtered
     * to the active model_code.
     */
    private static final String SELECT_VECTORS_FOR_NODE_NAME_SQL =
        "SELECT cce.dim, cce.vec "
      + "  FROM kg_nodes n "
      + "  JOIN chunk_entity_mentions m ON m.node_id = n.id "
      + "  JOIN content_chunk_embeddings cce ON cce.chunk_id = m.chunk_id "
      + " WHERE n.name = ? "
      + "   AND cce.model_code = ?";

    /**
     * Loads every mentioned node plus its chunk vectors in one pass. Read once
     * per similarity call so we can compute centroids for all candidates
     * without N round-trips.
     */
    private static final String SELECT_ALL_NODE_CHUNK_VECTORS_SQL =
        "SELECT n.name, cce.dim, cce.vec "
      + "  FROM kg_nodes n "
      + "  JOIN chunk_entity_mentions m ON m.node_id = n.id "
      + "  JOIN content_chunk_embeddings cce ON cce.chunk_id = m.chunk_id "
      + " WHERE cce.model_code = ? "
      + " ORDER BY n.name";

    private static final String SELECT_MENTIONED_NODE_NAMES_SQL =
        "SELECT DISTINCT n.name "
      + "  FROM kg_nodes n "
      + "  JOIN chunk_entity_mentions m ON m.node_id = n.id "
      + " ORDER BY n.name";

    private final DataSource dataSource;
    private final String modelCode;

    public NodeMentionSimilarity( final DataSource dataSource, final String modelCode ) {
        if ( dataSource == null ) throw new IllegalArgumentException( "dataSource must not be null" );
        if ( modelCode == null || modelCode.isBlank() ) {
            throw new IllegalArgumentException( "modelCode must not be blank" );
        }
        this.dataSource = dataSource;
        this.modelCode = modelCode;
    }

    /**
     * Returns the embedding dimension declared in {@code content_chunk_embeddings}
     * for the active model, or {@code 0} if no rows exist yet.
     */
    public int dimension() {
        try ( final Connection c = dataSource.getConnection();
              final PreparedStatement ps = c.prepareStatement( SELECT_DIM_SQL ) ) {
            ps.setString( 1, modelCode );
            try ( final ResultSet rs = ps.executeQuery() ) {
                if ( rs.next() ) return rs.getInt( 1 );
                return 0;
            }
        } catch ( final SQLException e ) {
            LOG.warn( "NodeMentionSimilarity.dimension query failed (model={}): {}",
                modelCode, e.getMessage(), e );
            return 0;
        }
    }

    /** True iff at least one embedding row exists for the active model. */
    public boolean isReady() {
        return dimension() > 0;
    }

    /**
     * Returns the centroid of the chunks that mention the given node. Empty if
     * the node has no mentions or all mentions lack embedding rows for the
     * active model.
     */
    public Optional< float[] > vectorFor( final String nodeName ) {
        if ( nodeName == null || nodeName.isBlank() ) return Optional.empty();
        final List< float[] > vectors = new ArrayList<>();
        try ( final Connection c = dataSource.getConnection();
              final PreparedStatement ps = c.prepareStatement( SELECT_VECTORS_FOR_NODE_NAME_SQL ) ) {
            ps.setString( 1, nodeName );
            ps.setString( 2, modelCode );
            try ( final ResultSet rs = ps.executeQuery() ) {
                while ( rs.next() ) {
                    final int dim = rs.getInt( 1 );
                    final byte[] raw = rs.getBytes( 2 );
                    final float[] v = decodeVector( nodeName, raw, dim );
                    if ( v != null ) vectors.add( v );
                }
            }
        } catch ( final SQLException e ) {
            LOG.warn( "NodeMentionSimilarity.vectorFor '{}' failed (model={}): {}",
                nodeName, modelCode, e.getMessage(), e );
            return Optional.empty();
        }
        if ( vectors.isEmpty() ) return Optional.empty();
        return Optional.of( centroid( vectors ) );
    }

    /**
     * Bulk snapshot: name → centroid for every node with at least one mention
     * whose chunks have embedding rows for the active model. Single SQL query,
     * aggregated in memory. Used by pipeline callers (hub overview, discovery,
     * proposals) to avoid N round-trips when they iterate over the candidate
     * set and do their own math on the resulting vectors.
     */
    public Map< String, float[] > allCentroids() {
        return loadAllCentroids();
    }

    /** Names of all nodes with at least one chunk mention. */
    public List< String > mentionedNodeNames() {
        final List< String > names = new ArrayList<>();
        try ( final Connection c = dataSource.getConnection();
              final PreparedStatement ps = c.prepareStatement( SELECT_MENTIONED_NODE_NAMES_SQL );
              final ResultSet rs = ps.executeQuery() ) {
            while ( rs.next() ) names.add( rs.getString( 1 ) );
        } catch ( final SQLException e ) {
            LOG.warn( "NodeMentionSimilarity.mentionedNodeNames failed: {}", e.getMessage(), e );
            return List.of();
        }
        return names;
    }

    /**
     * Returns the top-{@code limit} nodes most similar to {@code nodeName} by
     * cosine similarity of their mention-chunk centroids. Excludes the query
     * node itself. Empty if the query node has no usable centroid.
     */
    public List< ScoredName > similarTo( final String nodeName, final int limit ) {
        final Optional< float[] > query = vectorFor( nodeName );
        if ( query.isEmpty() ) return List.of();
        return similarTo( query.get(), limit, Set.of( nodeName ) );
    }

    /**
     * Returns the top-{@code limit} nodes most similar to {@code queryVector}
     * by cosine similarity. {@code excludeNames} is honored case-sensitively —
     * pass the query node name to keep it out of its own results.
     */
    public List< ScoredName > similarTo( final float[] queryVector, final int limit,
                                          final Set< String > excludeNames ) {
        if ( queryVector == null ) throw new IllegalArgumentException( "queryVector must not be null" );
        if ( limit <= 0 ) return List.of();

        final Map< String, float[] > centroids = loadAllCentroids();
        if ( centroids.isEmpty() ) return List.of();

        final float[] unitQuery = normalized( queryVector );
        if ( unitQuery == null ) return List.of();

        final List< ScoredName > scored = new ArrayList<>( centroids.size() );
        for ( final Map.Entry< String, float[] > e : centroids.entrySet() ) {
            if ( excludeNames != null && excludeNames.contains( e.getKey() ) ) continue;
            final float[] c = e.getValue();
            if ( c.length != unitQuery.length ) continue;
            double dot = 0.0;
            for ( int i = 0; i < c.length; i++ ) dot += (double) c[ i ] * (double) unitQuery[ i ];
            scored.add( new ScoredName( e.getKey(), dot ) );
        }
        scored.sort( ( a, b ) -> Double.compare( b.score(), a.score() ) );
        return scored.size() <= limit ? scored : scored.subList( 0, limit );
    }

    // ---- internals ----

    private Map< String, float[] > loadAllCentroids() {
        final Map< String, List< float[] > > perNode = new LinkedHashMap<>();
        try ( final Connection c = dataSource.getConnection();
              final PreparedStatement ps = c.prepareStatement( SELECT_ALL_NODE_CHUNK_VECTORS_SQL ) ) {
            ps.setString( 1, modelCode );
            ps.setFetchSize( 500 );
            try ( final ResultSet rs = ps.executeQuery() ) {
                while ( rs.next() ) {
                    final String name = rs.getString( 1 );
                    final int dim = rs.getInt( 2 );
                    final byte[] raw = rs.getBytes( 3 );
                    final float[] v = decodeVector( name, raw, dim );
                    if ( v == null ) continue;
                    perNode.computeIfAbsent( name, k -> new ArrayList<>() ).add( v );
                }
            }
        } catch ( final SQLException e ) {
            LOG.warn( "NodeMentionSimilarity.loadAllCentroids failed (model={}): {}",
                modelCode, e.getMessage(), e );
            return Map.of();
        }
        final Map< String, float[] > out = new HashMap<>( perNode.size() * 2 );
        for ( final Map.Entry< String, List< float[] > > e : perNode.entrySet() ) {
            out.put( e.getKey(), centroid( e.getValue() ) );
        }
        return out;
    }

    /**
     * Mean of L2-normalized vectors, then re-normalized. Returns a zero-length
     * array if the caller passes an empty list (shouldn't happen: callers
     * pre-check).
     */
    static float[] centroid( final List< float[] > vectors ) {
        if ( vectors.isEmpty() ) return new float[ 0 ];
        final int dim = vectors.get( 0 ).length;
        final float[] sum = new float[ dim ];
        for ( final float[] v : vectors ) {
            if ( v.length != dim ) continue;     // defensive: mixed-dim run
            final float[] unit = normalized( v );
            if ( unit == null ) continue;
            for ( int i = 0; i < dim; i++ ) sum[ i ] += unit[ i ];
        }
        final float[] unit = normalized( sum );
        return unit != null ? unit : sum;
    }

    private static float[] normalized( final float[] v ) {
        double sumSq = 0.0;
        for ( final float f : v ) sumSq += (double) f * (double) f;
        if ( sumSq <= 0.0 ) return null;
        final double inv = 1.0 / Math.sqrt( sumSq );
        final float[] out = new float[ v.length ];
        for ( int i = 0; i < v.length; i++ ) out[ i ] = (float) ( v[ i ] * inv );
        return out;
    }

    private static float[] decodeVector( final Object keyForLog, final byte[] raw, final int dim ) {
        if ( raw == null ) {
            LOG.warn( "NodeMentionSimilarity: null vec for {} — skipping", keyForLog );
            return null;
        }
        if ( raw.length != dim * Float.BYTES ) {
            LOG.warn( "NodeMentionSimilarity: vec bytes={} expected {} (dim={}) for {} — skipping",
                raw.length, dim * Float.BYTES, dim, keyForLog );
            return null;
        }
        final float[] out = new float[ dim ];
        final ByteBuffer buf = ByteBuffer.wrap( raw ).order( ByteOrder.LITTLE_ENDIAN );
        for ( int i = 0; i < dim; i++ ) out[ i ] = buf.getFloat();
        return out;
    }
}
