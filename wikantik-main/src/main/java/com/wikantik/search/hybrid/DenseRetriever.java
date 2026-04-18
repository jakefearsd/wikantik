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

import java.util.List;

/**
 * Dense retrieval: pulls the top-N nearest chunks from a {@link ChunkVectorIndex},
 * aggregates their cosine scores to page level via {@link PageAggregator}, and
 * returns the top page-ranked slice. All numeric parameters come from
 * {@link HybridConfig}; this class holds no mutable state.
 */
public final class DenseRetriever {

    private final ChunkVectorIndex index;
    private final PageAggregator aggregator;
    private final PageAggregation strategy;
    private final int chunkTop;
    private final int pageTop;

    public DenseRetriever( final ChunkVectorIndex index,
                           final PageAggregation strategy,
                           final int chunkTop,
                           final int pageTop ) {
        if( index == null ) throw new IllegalArgumentException( "index must not be null" );
        if( strategy == null ) throw new IllegalArgumentException( "strategy must not be null" );
        if( chunkTop <= 0 ) throw new IllegalArgumentException( "chunkTop must be positive" );
        if( pageTop <= 0 ) throw new IllegalArgumentException( "pageTop must be positive" );
        this.index = index;
        this.aggregator = new PageAggregator();
        this.strategy = strategy;
        this.chunkTop = chunkTop;
        this.pageTop = pageTop;
    }

    public List< ScoredPage > retrieve( final float[] queryVec ) {
        if( queryVec == null ) throw new IllegalArgumentException( "queryVec must not be null" );
        if( !index.isReady() ) return List.of();
        if( queryVec.length != index.dimension() ) {
            throw new IllegalArgumentException( "queryVec length " + queryVec.length
                + " does not match index dimension " + index.dimension() );
        }
        final List< ScoredChunk > chunks = index.topKChunks( queryVec, chunkTop );
        final List< ScoredPage > pages = aggregator.aggregate( chunks, strategy );
        return pages.size() > pageTop ? pages.subList( 0, pageTop ) : pages;
    }
}
