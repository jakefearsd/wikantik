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
package com.wikantik.api.eval;

import java.util.Optional;

/**
 * Retrieval pipeline variants evaluated by the
 * {@link RetrievalQualityRunner}. Wire form is the lowercase / underscore
 * spelling — {@code bm25}, {@code hybrid}, {@code hybrid_graph} — to match
 * the design doc and to read sanely on the {@code mode} column of
 * {@code retrieval_runs}.
 */
public enum RetrievalMode {

    /** BM25-only — Lucene baseline; no embedding, no graph. */
    BM25( "bm25" ),

    /** BM25 fused with dense embeddings (RRF). The production default. */
    HYBRID( "hybrid" ),

    /** Hybrid plus the entity-co-mention graph rerank step. */
    HYBRID_GRAPH( "hybrid_graph" );

    private final String wireName;

    RetrievalMode( final String wireName ) {
        this.wireName = wireName;
    }

    public String wireName() {
        return wireName;
    }

    /** Parses the lowercase / underscore form back into an enum. */
    public static Optional< RetrievalMode > fromWire( final String s ) {
        if ( s == null ) return Optional.empty();
        final String trimmed = s.trim().toLowerCase( java.util.Locale.ROOT );
        for ( final RetrievalMode m : values() ) {
            if ( m.wireName.equals( trimmed ) ) return Optional.of( m );
        }
        return Optional.empty();
    }
}
