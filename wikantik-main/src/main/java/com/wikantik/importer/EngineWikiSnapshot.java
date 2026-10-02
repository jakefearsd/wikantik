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
package com.wikantik.importer;

import com.wikantik.api.core.Page;
import com.wikantik.api.exceptions.ProviderException;
import com.wikantik.api.managers.PageManager;
import com.wikantik.api.managers.SystemPageRegistry;
import com.wikantik.api.pagegraph.ClusterDetails;
import com.wikantik.api.pagegraph.PageDescriptor;
import com.wikantik.api.pagegraph.StructuralIndexService;

import java.util.HashSet;
import java.util.Optional;
import java.util.Set;
import java.util.TreeMap;

/**
 * A point-in-time view of the wiki: exact then case-insensitive (lexicographically lowest) page lookup,
 * the system-page registry, and the structural index's cluster hubs.
 */
public final class EngineWikiSnapshot implements WikiSnapshot {

    private final Set< String > exact;
    private final TreeMap< String, String > byLowercase;
    private final SystemPageRegistry registry;
    private final StructuralIndexService index;

    private EngineWikiSnapshot( final Set< String > exact, final TreeMap< String, String > byLowercase,
                                final SystemPageRegistry registry, final StructuralIndexService index ) {
        this.exact = exact;
        this.byLowercase = byLowercase;
        this.registry = registry;
        this.index = index;
    }

    /** Captures the page names once; {@code registry} and {@code index} may be null. */
    public static EngineWikiSnapshot capture( final PageManager pages, final SystemPageRegistry registry,
                                              final StructuralIndexService index ) throws ProviderException {
        final Set< String > exact = new HashSet<>();
        final TreeMap< String, String > lower = new TreeMap<>();
        for ( final Page p : pages.getAllPages() ) {
            final String name = p.getName();
            exact.add( name );
            lower.merge( name.toLowerCase( java.util.Locale.ROOT ), name, ( a, b ) -> a.compareTo( b ) <= 0 ? a : b );
        }
        return new EngineWikiSnapshot( exact, lower, registry, index );
    }

    @Override
    public Optional< String > existingPage( final String name ) {
        if ( exact.contains( name ) ) {
            return Optional.of( name );
        }
        return Optional.ofNullable( byLowercase.get( name.toLowerCase( java.util.Locale.ROOT ) ) );
    }

    @Override
    public boolean isSystemPage( final String name ) {
        return registry != null && registry.isSystemPage( name );
    }

    @Override
    public Optional< String > hubPage( final String cluster ) {
        if ( index == null ) {
            return Optional.empty();
        }
        return index.getCluster( cluster ).map( ClusterDetails::hubPage ).map( PageDescriptor::slug );
    }
}
