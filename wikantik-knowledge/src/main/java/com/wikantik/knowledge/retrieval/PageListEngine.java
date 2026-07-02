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
package com.wikantik.knowledge.retrieval;

import com.wikantik.api.core.Page;
import com.wikantik.api.frontmatter.FrontmatterParser;
import com.wikantik.api.knowledge.PageList;
import com.wikantik.api.knowledge.PageListFilter;
import com.wikantik.api.knowledge.RetrievedPage;
import com.wikantik.api.managers.PageManager;
import com.wikantik.api.providers.PageProvider;
import com.wikantik.search.FrontmatterMetadataCache;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Date;
import java.util.List;
import java.util.Map;
import java.util.function.BiFunction;

import com.wikantik.api.exceptions.ProviderException;

/**
 * Listing, filtering, and frontmatter-metadata access for
 * {@link com.wikantik.knowledge.DefaultContextRetrievalService}. Extracted
 * verbatim from that class (Task 4 decomposition) — no change to filtering
 * semantics or the frontmatter-cache lookup path.
 *
 * <p>{@link #metadataFor} is the single copy shared by every caller that
 * needs frontmatter metadata (list, filter, and per-page retrieval) — it is
 * NOT duplicated between the extracted {@link #listPages} path and the
 * retrieval path that remains on {@code DefaultContextRetrievalService}.</p>
 */
public final class PageListEngine {

    private static final Logger LOG = LogManager.getLogger( PageListEngine.class );

    private final PageManager pageManager;
    private final FrontmatterMetadataCache fmCache;
    private final BiFunction< Page, Map< String, Object >, RetrievedPage > pageBuilder;

    /**
     * @param pageBuilder builds a {@link RetrievedPage} from a page and its
     *                    parsed metadata, using score {@code 0.0} and no
     *                    chunks/related pages — mirrors
     *                    {@code DefaultContextRetrievalService.buildRetrievedPage}'s
     *                    zero-score call shape from {@code listPages}. Kept as a
     *                    callback so the RetrievedPage-construction logic (URL
     *                    building included) stays a single copy on the owning
     *                    class rather than being duplicated here.
     */
    public PageListEngine(
            final PageManager pageManager,
            final FrontmatterMetadataCache fmCache,
            final BiFunction< Page, Map< String, Object >, RetrievedPage > pageBuilder ) {
        this.pageManager = pageManager;
        this.fmCache = fmCache;
        this.pageBuilder = pageBuilder;
    }

    public PageList listPages( final PageListFilter filter ) {
        final PageListFilter f = filter == null ? PageListFilter.unfiltered() : filter;

        final Collection< Page > allPages;
        try {
            allPages = pageManager.getAllPages();
        } catch ( final ProviderException e ) {
            LOG.warn( "getAllPages failed: {}", e.getMessage(), e );
            return new PageList( List.of(), 0, f.limit(), f.offset() );
        }

        final List< RetrievedPage > matched = new ArrayList<>();
        for ( final Page page : allPages ) {
            if ( page == null ) continue;
            final Map< String, Object > meta = metadataFor( page );
            if ( !matchesFilter( page, meta, f ) ) continue;
            matched.add( pageBuilder.apply( page, meta ) );
        }
        final int total = matched.size();
        final int from = Math.min( f.offset(), total );
        final int to = Math.min( from + Math.max( f.limit(), 1 ), total );
        return new PageList( matched.subList( from, to ), total, f.limit(), f.offset() );
    }

    public boolean matchesFilter( final Page page, final Map< String, Object > meta, final PageListFilter f ) {
        if ( f.cluster() != null
                && !f.cluster().equals( meta.get( "cluster" ) ) ) {
            return false;
        }
        if ( f.type() != null
                && !f.type().equals( meta.get( "type" ) ) ) {
            return false;
        }
        if ( f.author() != null && !f.author().equals( page.getAuthor() ) ) {
            return false;
        }
        final List< String > tags = stringList( meta.get( "tags" ) );
        for ( final String required : f.tags() ) {
            if ( !tags.contains( required ) ) return false;
        }
        if ( f.modifiedAfter() != null ) {
            final Date d = page.getLastModified();
            if ( d == null || d.toInstant().isBefore( f.modifiedAfter() ) ) return false;
        }
        if ( f.modifiedBefore() != null ) {
            final Date d = page.getLastModified();
            if ( d == null || d.toInstant().isAfter( f.modifiedBefore() ) ) return false;
        }
        return true;
    }

    /**
     * Returns the frontmatter metadata for the given page. The retrieval path
     * needs only metadata (never the body), so this routes through the shared
     * {@link FrontmatterMetadataCache} — keyed on {@code (pageName, lastModified)}
     * so an edit invalidates naturally — avoiding a fresh text read + YAML parse of
     * every candidate page on every query. The cache is an optional dependency; when
     * absent the read degrades to a direct parse. Returns an empty (never null) map.
     */
    public Map< String, Object > metadataFor( final Page page ) {
        if ( fmCache != null ) {
            return fmCache.get( page.getName(), page.getLastModified() );
        }
        final String text = pageManager.getPureText( page.getName(), PageProvider.LATEST_VERSION );
        return FrontmatterParser.parse( text == null ? "" : text ).metadata();
    }

    public static String stringOrEmpty( final Object o ) {
        return o == null ? "" : o.toString();
    }

    public static String stringOrNull( final Object o ) {
        return o == null ? null : o.toString();
    }

    @SuppressWarnings( "unchecked" )
    public static List< String > stringList( final Object o ) {
        if ( !( o instanceof List< ? > raw ) ) return List.of();
        final List< String > out = new ArrayList<>( raw.size() );
        for ( final Object item : raw ) if ( item != null ) out.add( item.toString() );
        return List.copyOf( out );
    }
}
