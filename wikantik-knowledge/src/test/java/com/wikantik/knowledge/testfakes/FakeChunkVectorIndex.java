/*
 * Licensed to the Apache Software Foundation (ASF) under one
 * or more contributor license agreements.  See the NOTICE file
 * distributed with this work for additional information
 * regarding copyright ownership.  The ASF licenses this file
 * to you under the Apache License, Version 2.0 (the
 * "License"); you may not use this file except in compliance
 * with the License.  You may obtain a copy of the License at
 *
 *   http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing,
 * software distributed under the License is distributed on an
 * "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY
 * KIND, either express or implied.  See the License for the
 * specific language governing permissions and limitations
 * under the License.
 */
package com.wikantik.knowledge.testfakes;

import com.wikantik.search.hybrid.ChunkVectorIndex;
import com.wikantik.search.hybrid.ScoredChunk;

import java.util.List;

/** In-memory {@link ChunkVectorIndex} for unit tests. */
public class FakeChunkVectorIndex implements ChunkVectorIndex {

    private boolean enabled;
    private int dim;
    private List< ScoredChunk > topK = List.of();

    public void setEnabled( boolean b ) { this.enabled = b; }
    public void setDim( int d ) { this.dim = d; }
    public void setTopK( List< ScoredChunk > chunks ) { this.topK = List.copyOf( chunks ); }

    @Override public List< ScoredChunk > topKChunks( float[] vec, int k ) {
        return topK.size() > k ? topK.subList( 0, k ) : topK;
    }
    @Override public boolean isReady() { return enabled; }
    @Override public int dimension() { return dim; }
}
