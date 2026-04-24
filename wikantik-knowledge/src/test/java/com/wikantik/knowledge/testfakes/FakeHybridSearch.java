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

import com.wikantik.search.hybrid.HybridSearchService;

import java.util.List;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/** Mockito-based factory for {@link HybridSearchService} test doubles. */
public final class FakeHybridSearch {

    public static HybridSearchService enabledReturning( final List< String > rerankOrder ) {
        final HybridSearchService mocked = mock( HybridSearchService.class );
        when( mocked.isEnabled() ).thenReturn( true );
        when( mocked.rerank( anyString(), anyList() ) ).thenReturn( rerankOrder );
        when( mocked.rerankWith( anyString(), anyList(), any() ) ).thenReturn( rerankOrder );
        when( mocked.prefetchQueryEmbedding( anyString() ) )
            .thenReturn( CompletableFuture.completedFuture( Optional.of( new float[]{ 1f } ) ) );
        return mocked;
    }

    public static HybridSearchService disabled() {
        final HybridSearchService mocked = mock( HybridSearchService.class );
        when( mocked.isEnabled() ).thenReturn( false );
        return mocked;
    }

    private FakeHybridSearch() {}
}
