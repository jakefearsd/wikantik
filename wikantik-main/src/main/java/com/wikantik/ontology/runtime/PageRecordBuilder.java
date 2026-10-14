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
package com.wikantik.ontology.runtime;

import com.wikantik.api.pagegraph.ClusterPath;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.function.Supplier;
import java.util.stream.Collectors;

import com.wikantik.api.frontmatter.FrontmatterParser;
import com.wikantik.api.frontmatter.ParsedPage;
import com.wikantik.api.identity.IdentityDisplay;
import com.wikantik.api.identity.PublicIdentity;
import com.wikantik.api.managers.PageManager;
import com.wikantik.api.providers.PageProvider;
import com.wikantik.ontology.projection.PageRecord;
import com.wikantik.pagegraph.spine.PageCanonicalIdsDao;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

/** Builds {@link PageRecord}s from page_canonical_ids rows enriched with frontmatter. */
public final class PageRecordBuilder {

    private static final Logger LOG = LogManager.getLogger( PageRecordBuilder.class );

    private final PageManager pageManager;
    private final Supplier< List< PageCanonicalIdsDao.Row > > rowSource;
    /** {@code null} = no identity service: frontmatter authors are projected exactly as written. */
    private final PublicIdentity publicIdentity;

    public PageRecordBuilder( final PageManager pageManager,
                              final Supplier< List< PageCanonicalIdsDao.Row > > rowSource ) {
        this( pageManager, rowSource, null );
    }

    public PageRecordBuilder( final PageManager pageManager,
                              final Supplier< List< PageCanonicalIdsDao.Row > > rowSource,
                              final PublicIdentity publicIdentity ) {
        this.pageManager = pageManager;
        this.rowSource = rowSource;
        this.publicIdentity = publicIdentity;
    }

    public List< PageRecord > build() {
        final List< PageRecord > out = new ArrayList<>();
        for ( final PageCanonicalIdsDao.Row row : rowSource.get() ) {
            out.add( fromRow( row, pageManager, publicIdentity ) );
        }
        return out;
    }

    /** As {@link #fromRow(PageCanonicalIdsDao.Row, PageManager, PublicIdentity)} with no identity service. */
    public static PageRecord fromRow( final PageCanonicalIdsDao.Row row, final PageManager pageManager ) {
        return fromRow( row, pageManager, null );
    }

    /**
     * Turns one canonical-id row into a PageRecord, enriching tags/summary/date/author from frontmatter. The
     * frontmatter {@code author:} is editor-typed text: it becomes the account's display name only when it is an
     * existing login (the public dataset must not publish logins), otherwise it is projected as written.
     */
    public static PageRecord fromRow( final PageCanonicalIdsDao.Row row, final PageManager pageManager,
                                      final PublicIdentity publicIdentity ) {
        Map< String, Object > md = Map.of();
        try {
            final String text = pageManager.getPureText( row.currentSlug(), PageProvider.LATEST_VERSION );
            if ( text != null ) {
                final ParsedPage parsed = FrontmatterParser.parse( text );
                md = parsed.metadata();
            }
        } catch ( final RuntimeException e ) {
            LOG.warn( "frontmatter parse failed for {}: {}", row.currentSlug(), e.getMessage() );
        }
        // Phase 5: the DB row carries only the primary cluster, but the ontology projects one
        // dct:subject per membership — so the full set is read from the page's own frontmatter.
        return new PageRecord(
                row.canonicalId(), row.currentSlug(), row.title(), row.type(), row.cluster(),
                ClusterPath.memberships( md.get( "cluster" ) ),
                tags( md.get( "tags" ) ), str( md.get( "summary" ) ), isoDate( md.get( "date" ) ),
                IdentityDisplay.ofLoginOrText( publicIdentity, str( md.get( "author" ) ) ) );
    }

    private static List< String > tags( final Object raw ) {
        if ( raw instanceof List< ? > list ) {
            return list.stream().map( Object::toString ).collect( Collectors.toList() );
        }
        return List.of();
    }

    private static String str( final Object raw ) {
        return raw == null ? null : raw.toString();
    }

    /**
     * SnakeYAML auto-types a bare {@code date: yyyy-MM-dd} as a {@link java.util.Date} (UTC midnight);
     * normalize it back to an ISO local-date string. Already-String values pass through unchanged.
     */
    private static String isoDate( final Object raw ) {
        if ( raw == null ) {
            return null;
        }
        if ( raw instanceof java.util.Date d ) {
            return d.toInstant().atZone( java.time.ZoneOffset.UTC ).toLocalDate().toString();
        }
        return raw.toString();
    }
}
