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
package com.wikantik.knowledge;

import com.wikantik.api.pagegraph.PageDescriptor;
import com.wikantik.api.pagegraph.StructuralIndexService;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Function;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class IndexedInlineTagsTest {

    private static StructuralIndexService indexWith( final String slug, final String descriptorSlug ) {
        final PageDescriptor d = mock( PageDescriptor.class );
        when( d.slug() ).thenReturn( descriptorSlug );
        when( d.writtenTags() ).thenReturn( List.of( "inline" ) );
        final StructuralIndexService index = mock( StructuralIndexService.class );
        when( index.resolveCanonicalIdFromSlug( slug ) ).thenReturn( Optional.of( "cid" ) );
        when( index.resolveCanonicalIdFromSlug( "Unknown" ) ).thenReturn( Optional.empty() );
        when( index.getByCanonicalId( "cid" ) ).thenReturn( Optional.of( d ) );
        return index;
    }

    @Test
    void indexedPageYieldsItsWrittenTags() {
        final StructuralIndexService index = indexWith( "Page", "Page" );
        final Function< String, Optional< List< String > > > f = DefaultContextRetrievalService.indexedInlineTags( () -> index );
        assertEquals( Optional.of( List.of( "inline" ) ), f.apply( "Page" ) );
    }

    @Test
    void unknownSlugIsEmpty() {
        final StructuralIndexService index = indexWith( "Page", "Page" );
        assertEquals( Optional.empty(), DefaultContextRetrievalService.indexedInlineTags( () -> index ).apply( "Unknown" ) );
    }

    @Test
    void nullIndexIsNotCachedAndALaterIndexIsUsed() {
        final AtomicReference< StructuralIndexService > ref = new AtomicReference<>();
        final Function< String, Optional< List< String > > > f = DefaultContextRetrievalService.indexedInlineTags( ref::get );
        assertEquals( Optional.empty(), f.apply( "Page" ) );
        ref.set( indexWith( "Page", "Page" ) );
        assertEquals( Optional.of( List.of( "inline" ) ), f.apply( "Page" ) );
    }

    @Test
    void descriptorOfADifferentSlugIsRejected() {
        final StructuralIndexService index = indexWith( "Alias", "Original" );
        assertEquals( Optional.empty(), DefaultContextRetrievalService.indexedInlineTags( () -> index ).apply( "Alias" ) );
    }
}
