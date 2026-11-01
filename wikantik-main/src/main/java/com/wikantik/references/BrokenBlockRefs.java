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
package com.wikantik.references;

import com.wikantik.api.core.Page;
import com.wikantik.api.managers.PageManager;
import com.wikantik.api.parser.WikiLinkSyntax;
import com.wikantik.api.providers.PageProvider;
import com.wikantik.parser.markdown.BlockIds;
import com.wikantik.wikilink.WikiLinkResolver;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;

/**
 * Finds block references ({@code [[Page#^id]]}, {@code ![[Page#^id]]}) whose target page exists but carries no such
 * block. A reference to a page that does not exist is a broken <em>page</em> link and is reported elsewhere.
 */
public final class BrokenBlockRefs {

    private static final Logger LOG = LogManager.getLogger( BrokenBlockRefs.class );

    /** One broken reference: {@code page} links to {@code [[target#^id]]} and {@code target} has no block {@code id}. */
    public record Entry( String page, String target, String id ) {}

    private static final Comparator< Entry > ORDER = Comparator.comparing( Entry::page )
            .thenComparing( Entry::target ).thenComparing( Entry::id );

    /**
     * Scans every page.
     *
     * @return the broken references sorted by page, target, id; never {@code null}
     */
    public List< Entry > find( final PageManager pages, final WikiLinkResolver resolver ) {
        final Set< Entry > found = new TreeSet<>( ORDER );
        final Map< String, Set< String > > idsByPage = new HashMap<>();
        final Set< String > unreadable = new HashSet<>();
        final List< String > names = new ArrayList<>();
        try {
            for ( final Page p : pages.getAllPages() ) {
                names.add( p.getName() );
            }
        } catch ( final Exception e ) {
            LOG.warn( "Broken block references: cannot enumerate pages: {}", e.getMessage(), e );
            return List.of();
        }
        for ( final String name : names ) {
            scan( name, pages, resolver, found, idsByPage, unreadable );
        }
        return new ArrayList<>( found );
    }

    private void scan( final String name, final PageManager pages, final WikiLinkResolver resolver,
                       final Set< Entry > found, final Map< String, Set< String > > idsByPage,
                       final Set< String > unreadable ) {
        try {
            final String text = pages.getPureText( name, PageProvider.LATEST_VERSION );
            for ( final WikiLinkSyntax.WikiLinkRef ref : WikiLinkSyntax.findAll( text ) ) {
                if ( !BlockIds.isBlockRef( ref.heading() ) || ref.isAttachment() ) {
                    continue;
                }
                final String targetPage = ref.isSamePage() ? name : resolvedPage( ref.target(), resolver );
                if ( targetPage == null ) {
                    continue;
                }
                final String id = BlockIds.idOf( ref.heading() );
                final Set< String > ids = idsOf( targetPage, pages, idsByPage, unreadable, ref.isSamePage() ? text : null );
                if ( ids != null && !ids.contains( id ) ) {
                    found.add( new Entry( name, targetPage, id ) );
                }
            }
        } catch ( final Exception e ) {
            LOG.warn( "Broken block references: skipping page '{}': {}", name, e.getMessage(), e );
        }
    }

    private static String resolvedPage( final String target, final WikiLinkResolver resolver ) {
        final WikiLinkResolver.Resolution r = resolver.resolve( target );
        return r.exists() ? r.pageName() : null;
    }

    /** Block ids of {@code page}, cached for the run; {@code null} when its text cannot be read. */
    private Set< String > idsOf( final String page, final PageManager pages, final Map< String, Set< String > > cache,
                                 final Set< String > unreadable, final String known ) {
        if ( unreadable.contains( page ) ) {
            return null;
        }
        final Set< String > cached = cache.get( page );
        if ( cached != null ) {
            return cached;
        }
        try {
            final String text = known != null ? known : pages.getPureText( page, PageProvider.LATEST_VERSION );
            final Set< String > ids = new HashSet<>();
            BlockIds.list( text ).forEach( b -> {
                if ( b.id() != null ) {
                    ids.add( b.id() );
                }
            } );
            cache.put( page, ids );
            return ids;
        } catch ( final Exception e ) {
            LOG.warn( "Broken block references: cannot read target page '{}': {}", page, e.getMessage(), e );
            unreadable.add( page );
            return null;
        }
    }
}
