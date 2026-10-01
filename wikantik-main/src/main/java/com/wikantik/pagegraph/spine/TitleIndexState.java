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
package com.wikantik.pagegraph.spine;

import com.wikantik.api.pagegraph.PageDescriptor;
import com.wikantik.api.pagegraph.PageTitleLookup;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 *  Title/alias bookkeeping behind {@link DefaultStructuralIndexService#titleLookup()}: the
 *  canonical_id-to-aliases map (copy-on-write), the "titles ready" flag and the cached index.
 *  Mutators are called under the service's lock; {@link #lookup} may be called from any thread.
 */
final class TitleIndexState {

    /** canonical_id to aliases, copy-on-write. */
    private volatile Map< String, List< String > > aliasesByCanonicalId = Map.of();
    /** True once a rebuild has completed — before that the title lookup is "warming". */
    private volatile boolean titlesReady;
    private volatile CachedTitles cachedTitles;

    private record CachedTitles( StructuralProjection projection, Map< String, List< String > > aliases,
                                 PageTitleIndex index ) {}

    void onRebuilt( final Map< String, List< String > > aliases ) {
        this.aliasesByCanonicalId = Map.copyOf( aliases );
        this.titlesReady = true;
    }

    void onSaved( final String canonicalId, final Map< String, Object > fm ) {
        final Map< String, List< String > > next = new HashMap<>( aliasesByCanonicalId );
        next.put( canonicalId, aliasesOf( fm ) );
        this.aliasesByCanonicalId = Map.copyOf( next );
    }

    void onDeleted( final String canonicalId ) {
        final Map< String, List< String > > next = new HashMap<>( aliasesByCanonicalId );
        next.remove( canonicalId );
        this.aliasesByCanonicalId = Map.copyOf( next );
    }

    Optional< PageTitleLookup > lookup( final StructuralProjection proj ) {
        if ( !titlesReady ) {
            return Optional.empty();
        }
        final Map< String, List< String > > aliases = aliasesByCanonicalId;
        final CachedTitles cached = cachedTitles;
        if ( cached != null && cached.projection() == proj && cached.aliases() == aliases ) {
            return Optional.of( cached.index() );
        }
        final Map< String, List< String > > aliasesBySlug = new HashMap<>();
        for ( final PageDescriptor d : proj.allPages() ) {
            final List< String > a = aliases.get( d.canonicalId() );
            if ( a != null && !a.isEmpty() ) {
                aliasesBySlug.put( d.slug(), a );
            }
        }
        final PageTitleIndex index = PageTitleIndex.of( proj.allPages(), aliasesBySlug );
        cachedTitles = new CachedTitles( proj, aliases, index );
        return Optional.of( index );
    }

    static List< String > aliasesOf( final Map< String, Object > fm ) {
        return DefaultStructuralIndexService.stringList( fm.get( "aliases" ) ).stream()
                .map( String::trim ).filter( s -> !s.isEmpty() ).toList();
    }
}
