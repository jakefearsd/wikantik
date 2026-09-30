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
package com.wikantik.export;

import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import com.wikantik.api.core.Page;
import com.wikantik.api.core.Session;
import com.wikantik.api.exceptions.ProviderException;
import com.wikantik.api.frontmatter.FrontmatterParser;
import com.wikantik.api.managers.PageManager;
import com.wikantik.api.managers.ReferenceManager;
import com.wikantik.api.pagegraph.PageDescriptor;
import com.wikantik.api.pagegraph.StructuralIndexService;
import com.wikantik.auth.permissions.PermissionFilter;

/**
 * Wires {@link ExportSelectionResolver}'s {@link ExportCatalog} contract onto the live wiki
 * engine: the whole-corpus sitemap, frontmatter {@code status}, outbound wikilinks (attachments
 * and non-existent targets excluded), and per-session view ACLs. Frontmatter is parsed lazily
 * and cached per slug for the life of this instance, which is scoped to a single request.
 */
public final class EngineExportCatalog implements ExportCatalog {

    private static final Logger LOG = LogManager.getLogger( EngineExportCatalog.class );

    private final StructuralIndexService index;
    private final PageManager pages;
    private final ReferenceManager refs;
    private final PermissionFilter permissions;
    private final Session session;
    private final Map< String, Optional< String > > statusCache = new HashMap<>();

    public EngineExportCatalog( final StructuralIndexService index, final PageManager pages, final ReferenceManager refs,
                                final PermissionFilter permissions, final Session session ) {
        this.index = index;
        this.pages = pages;
        this.refs = refs;
        this.permissions = permissions;
        this.session = session;
    }

    @Override
    public List< PageDescriptor > allPages() {
        return index.sitemap().pages();
    }

    @Override
    public Optional< String > status( final String slug ) {
        return statusCache.computeIfAbsent( slug, this::readStatus );
    }

    private Optional< String > readStatus( final String slug ) {
        final Page page = pages.getPage( slug );
        if ( page == null ) {
            return Optional.empty();
        }
        final Object raw = FrontmatterParser.parse( pages.getPureText( page ) ).metadata().get( "status" );
        return raw == null ? Optional.empty() : Optional.of( raw.toString() );
    }

    @Override
    public Collection< String > outboundPages( final String slug ) {
        final List< String > out = new ArrayList<>();
        for ( final String name : refs.findRefersTo( slug ) ) {
            if ( name.indexOf( '/' ) >= 0 ) {
                continue; // "Page/file.ext" is an attachment reference, not a page link
            }
            if ( pageExists( name ) ) {
                out.add( name );
            }
        }
        return out;
    }

    private boolean pageExists( final String name ) {
        try {
            return pages.pageExists( name );
        } catch ( final ProviderException e ) {
            LOG.warn( "Export: checking existence of {} failed: {}", name, e.getMessage(), e );
            return false;
        }
    }

    @Override
    public Set< String > viewable( final Collection< String > slugs ) {
        return permissions.filterViewableQuietly( session, slugs );
    }
}
