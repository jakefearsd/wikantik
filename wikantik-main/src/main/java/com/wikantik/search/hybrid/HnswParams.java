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

import java.util.Properties;

/**
 * Tuning parameters for the Lucene HNSW dense backend. Defaults mirror the
 * pgvector backend's HNSW index ({@code m=16}, {@code ef_construction=64}) and
 * its query-time {@code ef_search=100}.
 */
public record HnswParams( int m, int efConstruction, int efSearch, Quantization quantization ) {

    /**
     * How the HNSW index stores each vector.
     *
     * <p>Deliberately a plain enum with no Lucene types: the mapping to Lucene's
     * {@code ScalarEncoding} lives in {@code LuceneHnswChunkVectorIndex}, so this record
     * stays a pure config value object.</p>
     *
     * <p><b>Why this knob exists.</b> The 2026-09-25 profiling campaign measured
     * {@code LuceneHnswChunkVectorIndex.topKChunks} at 36.77% of ALL CPU, and 58.25% of its
     * leaf samples were {@code ByteBuffersDataInput.readFloats} against only 13.70% in
     * {@code PanamaVectorUtilSupport.cosineBody} — roughly 4x more CPU spent READING
     * float32 vectors than computing cosines over them. At dim 1024 each vector is a 4 KB
     * read, paid for every candidate the HNSW graph visits. Quantization shrinks that read
     * ({@link #SEVEN_BIT} ~4x, {@link #PACKED_NIBBLE} ~8x) at some recall cost, which is
     * why it is opt-in rather than on by default.</p>
     */
    public enum Quantization {
        /** Raw float32 — the historical behaviour. */
        NONE,
        /** 7-bit scalar quantization: ~4x smaller vector reads. */
        SEVEN_BIT,
        /** 8-bit (unsigned byte) scalar quantization: ~4x smaller vector reads. */
        UNSIGNED_BYTE,
        /** 4-bit packed scalar quantization: ~8x smaller vector reads, lowest fidelity. */
        PACKED_NIBBLE;

        /**
         * Parses a configured value, failing SAFE rather than closed: an unrecognised value
         * yields {@link #NONE} (retrieval keeps working, unquantized) instead of throwing and
         * taking dense retrieval offline on a typo.
         */
        static Quantization parse( final String raw ) {
            if ( raw == null || raw.isBlank() ) {
                return NONE;
            }
            final String v = raw.trim().toUpperCase( java.util.Locale.ROOT );
            for ( final Quantization q : values() ) {
                if ( q.name().equals( v ) ) {
                    return q;
                }
            }
            return NONE;
        }
    }

    public HnswParams {
        if ( m <= 0 ) throw new IllegalArgumentException( "m must be positive, got " + m );
        if ( efConstruction <= 0 ) {
            throw new IllegalArgumentException( "efConstruction must be positive, got " + efConstruction );
        }
        if ( efSearch <= 0 ) throw new IllegalArgumentException( "efSearch must be positive, got " + efSearch );
        if ( quantization == null ) {
            quantization = Quantization.NONE;
        }
    }

    /**
     * Unquantized (float32) form. Kept so every pre-existing call site — production wiring
     * and roughly a dozen tests — compiles unchanged and keeps its original behaviour.
     */
    public HnswParams( final int m, final int efConstruction, final int efSearch ) {
        this( m, efConstruction, efSearch, Quantization.NONE );
    }

    public static HnswParams fromProperties( final Properties props ) {
        return new HnswParams(
            intProp( props, "wikantik.search.dense.lucene.m", 16 ),
            intProp( props, "wikantik.search.dense.lucene.ef_construction", 64 ),
            intProp( props, "wikantik.search.dense.lucene.ef_search", 100 ),
            Quantization.parse( props == null ? "none"
                : props.getProperty( "wikantik.search.dense.lucene.quantization", "none" ) ) );
    }

    private static int intProp( final Properties props, final String key, final int dflt ) {
        final String raw = props == null ? null : props.getProperty( key );
        if ( raw == null || raw.isBlank() ) return dflt;
        return Integer.parseInt( raw.trim() );
    }
}
