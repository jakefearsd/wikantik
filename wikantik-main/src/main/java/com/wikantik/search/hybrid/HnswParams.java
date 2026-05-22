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
public record HnswParams( int m, int efConstruction, int efSearch ) {

    public HnswParams {
        if ( m <= 0 ) throw new IllegalArgumentException( "m must be positive, got " + m );
        if ( efConstruction <= 0 ) {
            throw new IllegalArgumentException( "efConstruction must be positive, got " + efConstruction );
        }
        if ( efSearch <= 0 ) throw new IllegalArgumentException( "efSearch must be positive, got " + efSearch );
    }

    public static HnswParams fromProperties( final Properties props ) {
        return new HnswParams(
            intProp( props, "wikantik.search.dense.lucene.m", 16 ),
            intProp( props, "wikantik.search.dense.lucene.ef_construction", 64 ),
            intProp( props, "wikantik.search.dense.lucene.ef_search", 100 ) );
    }

    private static int intProp( final Properties props, final String key, final int dflt ) {
        final String raw = props == null ? null : props.getProperty( key );
        if ( raw == null || raw.isBlank() ) return dflt;
        return Integer.parseInt( raw.trim() );
    }
}
