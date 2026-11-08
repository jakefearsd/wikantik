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
package com.wikantik.search.embedding;

import com.wikantik.jdbc.Jdbc;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import javax.sql.DataSource;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.sql.SQLException;
import java.util.HashMap;
import java.util.Map;

/**
 * Loads one unit-length centroid per page — the L2-normalized mean of the page's L2-normalized chunk
 * vectors in {@code content_chunk_embeddings} for one embedding model — in a single streaming pass.
 * Rows arrive ordered by page, so only one page's accumulator is live at a time.
 */
final class PageCentroidLoader {

    private static final Logger LOG = LogManager.getLogger( PageCentroidLoader.class );

    private static final int FETCH_SIZE = 500;

    private static final String SELECT_PAGE_CHUNK_VECTORS_SQL =
        "SELECT c.page_name, e.dim, e.vec "
      + "  FROM content_chunk_embeddings e "
      + "  JOIN kg_content_chunks c ON c.id = e.chunk_id "
      + " WHERE e.model_code = ? "
      + " ORDER BY c.page_name";

    private final Jdbc jdbc;
    private final String modelCode;

    PageCentroidLoader( final DataSource dataSource, final String modelCode ) {
        this.jdbc = new Jdbc( dataSource );
        this.modelCode = modelCode;
    }

    /** Page name to unit centroid; {@code null} when the query fails (logged). */
    Map< String, float[] > load() {
        final Map< String, float[] > out = new HashMap<>();
        final String[] currentPage = { null };
        final float[][] sum = { null };
        try {
            jdbc.forEachRow( SELECT_PAGE_CHUNK_VECTORS_SQL, ps -> ps.setString( 1, modelCode ), FETCH_SIZE, rs -> {
                final String page = rs.getString( 1 );
                final float[] v = decode( page, rs.getBytes( 3 ), rs.getInt( 2 ) );
                if ( v == null ) return;
                if ( !page.equals( currentPage[ 0 ] ) ) {
                    finishInto( out, currentPage[ 0 ], sum[ 0 ] );
                    currentPage[ 0 ] = page;
                    sum[ 0 ] = new float[ v.length ];
                }
                accumulate( sum[ 0 ], v );
            } );
        } catch ( final SQLException | RuntimeException e ) {
            LOG.warn( "PageCentroidLoader: loading page centroids failed (model={}): {}",
                modelCode, e.getMessage(), e );
            return null;
        }
        finishInto( out, currentPage[ 0 ], sum[ 0 ] );
        return out;
    }

    /** Dot product; equals cosine similarity for the unit centroids this loader produces. */
    static double dot( final float[] a, final float[] b ) {
        double d = 0.0;
        for ( int i = 0; i < a.length; i++ ) d += (double) a[ i ] * (double) b[ i ];
        return d;
    }

    private static void finishInto( final Map< String, float[] > out, final String page, final float[] sum ) {
        if ( page == null ) return;
        final float[] unit = normalized( sum );
        if ( unit != null ) out.put( page, unit );
    }

    /** Adds the unit-normalized {@code v} into {@code sum}; mixed-dimension and zero vectors are skipped. */
    private static void accumulate( final float[] sum, final float[] v ) {
        if ( v.length != sum.length ) return;
        final float[] unit = normalized( v );
        if ( unit == null ) return;
        for ( int i = 0; i < sum.length; i++ ) sum[ i ] += unit[ i ];
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

    private static float[] decode( final String page, final byte[] raw, final int dim ) {
        if ( raw == null || dim <= 0 || raw.length != dim * Float.BYTES ) {
            LOG.warn( "PageCentroidLoader: skipping malformed chunk vector for page '{}' (bytes={}, dim={})",
                page, raw == null ? -1 : raw.length, dim );
            return null;
        }
        final float[] out = new float[ dim ];
        ByteBuffer.wrap( raw ).order( ByteOrder.LITTLE_ENDIAN ).asFloatBuffer().get( out );
        return out;
    }
}
