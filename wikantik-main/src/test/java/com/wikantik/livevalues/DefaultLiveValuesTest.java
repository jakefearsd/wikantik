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
package com.wikantik.livevalues;

import com.wikantik.api.livevalues.LiveValueExpander.Resolution;
import com.wikantik.api.livevalues.LiveValueSource;
import com.wikantik.api.pagegraph.StructuralIndexService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.Map;
import java.util.Set;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class DefaultLiveValuesTest {

    private final StructuralIndexService.StructuralProjectionSnapshot snap =
        mock( StructuralIndexService.StructuralProjectionSnapshot.class );
    private StructuralIndexService index;
    private long revision;

    @BeforeEach
    void setUp() {
        index = mock( StructuralIndexService.class );
        when( index.snapshot() ).thenReturn( snap );
        revision = 1;
        when( snap.revision() ).thenAnswer( i -> revision );
    }

    private static LiveValueSource source( final AtomicInteger calls, final Map< String, Resolution > values,
                                           final Set< String > declared ) {
        return new LiveValueSource() {
            @Override public Map< String, Resolution > values() { calls.incrementAndGet(); return values; }
            @Override public Set< String > declaredNames() { return declared; }
        };
    }

    @Test
    void recomputesOnlyWhenRevisionChanges() {
        final AtomicInteger calls = new AtomicInteger();
        final DefaultLiveValues lv = new DefaultLiveValues( index, true );
        lv.register( source( calls, Map.of( "articles", new Resolution.Value( 1234 ) ), Set.of( "articles" ) ) );
        assertEquals( "1,234", lv.expandText( "{{wiki:articles}}" ) );
        assertEquals( "1,200+", lv.expandText( "{{wiki:articles|round=100}}" ) );
        assertEquals( 1, calls.get() );
        revision = 2;
        lv.expandText( "{{wiki:articles}}" );
        assertEquals( 2, calls.get() );
    }

    @Test
    void generationChangesWithValues() {
        final AtomicInteger calls = new AtomicInteger();
        final Map< String, Resolution > values = new java.util.HashMap<>( Map.of( "articles", new Resolution.Value( 1 ) ) );
        final DefaultLiveValues lv = new DefaultLiveValues( index, true );
        lv.register( source( calls, values, Set.of( "articles" ) ) );
        final String g1 = lv.generation();
        assertEquals( g1, lv.generation() );
        values.put( "articles", new Resolution.Value( 2 ) );
        revision = 2;
        assertNotEquals( g1, lv.generation() );
        assertEquals( Map.of( "articles", "2" ), lv.renderedValues() );
    }

    @Test
    void disabledServesEverythingVerbatim() {
        final AtomicInteger calls = new AtomicInteger();
        final DefaultLiveValues lv = new DefaultLiveValues( index, false );
        lv.register( source( calls, Map.of( "articles", new Resolution.Value( 5 ) ), Set.of( "articles" ) ) );
        assertEquals( "{{wiki:articles}}", lv.expandText( "{{wiki:articles}}" ) );
        assertEquals( "{{wiki:articles}}", lv.expandBody( "{{wiki:articles}}" ) );
        assertEquals( 0, calls.get() );
    }

    @Test
    void throwingSourceMakesItsNamesUnwiredAndOthersStillExpand() {
        final DefaultLiveValues lv = new DefaultLiveValues( index, true );
        lv.register( new LiveValueSource() {
            @Override public Map< String, Resolution > values() { throw new UnsupportedOperationException( "boom" ); }
            @Override public Set< String > declaredNames() { return Set.of( "tools.admin" ); }
        } );
        lv.register( source( new AtomicInteger(), Map.of( "articles", new Resolution.Value( 7 ) ), Set.of( "articles" ) ) );
        assertEquals( "[x] 7", lv.expandText( "[{{wiki:tools.admin|else=x}}] {{wiki:articles}}" ) );
    }

    @Test
    void unknownNamesAndNullTextAndBuiltInsWithoutSource() {
        final DefaultLiveValues lv = new DefaultLiveValues( index, true );
        lv.register( source( new AtomicInteger(), java.util.Collections.singletonMap( "version", new Resolution.Text( null ) ),
                Set.of( "version" ) ) );
        assertEquals( "{{wiki:nosuch}}", lv.expandText( "{{wiki:nosuch}}" ) );
        assertEquals( "", lv.expandText( "{{wiki:version}}" ) );
        assertEquals( "{{wiki:cluster.x.pages}}", lv.expandText( "{{wiki:cluster.x.pages}}" ) );
        assertEquals( "n/a", lv.expandText( "{{wiki:tools.openapi|else=n/a}}" ) );
    }

    @Test
    void notReadySourceIsRetriedAtTheSameRevision() {
        final boolean[] ready = { false };
        final AtomicInteger calls = new AtomicInteger();
        final DefaultLiveValues lv = new DefaultLiveValues( index, true );
        lv.register( new LiveValueSource() {
            @Override public Map< String, Resolution > values() {
                calls.incrementAndGet();
                if( !ready[ 0 ] ) {
                    throw new LiveValueSource.NotReadyException( "not ready" );
                }
                return Map.of( "articles", new Resolution.Value( 9 ) );
            }
            @Override public Set< String > declaredNames() { return Set.of( "articles" ); }
        } );
        assertEquals( "[none]", lv.expandText( "[{{wiki:articles|else=none}}]" ) );
        ready[ 0 ] = true;
        assertEquals( "[9]", lv.expandText( "[{{wiki:articles|else=none}}]" ) );
        assertEquals( "[9]", lv.expandText( "[{{wiki:articles|else=none}}]" ) );
        assertEquals( 2, calls.get() );
    }

    @Test
    void plainIllegalStateExceptionIsCachedPerRevision() {
        final AtomicInteger calls = new AtomicInteger();
        final DefaultLiveValues lv = new DefaultLiveValues( index, true );
        lv.register( new LiveValueSource() {
            @Override public Map< String, Resolution > values() {
                calls.incrementAndGet();
                throw new IllegalStateException( "unrelated" );
            }
            @Override public Set< String > declaredNames() { return Set.of( "articles" ); }
        } );
        lv.expandText( "{{wiki:articles}}" );
        lv.expandText( "{{wiki:articles}}" );
        assertEquals( 1, calls.get() );
        revision = 2;
        lv.expandText( "{{wiki:articles}}" );
        assertEquals( 2, calls.get() );
    }
}
