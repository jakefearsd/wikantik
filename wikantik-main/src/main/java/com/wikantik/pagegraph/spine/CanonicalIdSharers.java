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

import com.wikantik.api.core.Page;
import com.wikantik.api.frontmatter.FrontmatterParser;
import com.wikantik.api.managers.PageManager;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Answers the questions a page delete must settle before {@link DefaultStructuralIndexService} retires a
 * canonical_id: is the page really gone, and does another live page carry the same id? Two pages authored
 * with one canonical_id is a defect, but a real one — retiring the id on either delete would wipe the
 * surviving page's owners, comment threads, citations and id row.
 *
 * <p>Every lookup failure resolves towards "keep": skipping a retirement leaks rows, a wrong retirement
 * destroys them.</p>
 */
final class CanonicalIdSharers {

    private static final Logger LOG = LogManager.getLogger( CanonicalIdSharers.class );

    private final PageManager pageManager;
    private final PageCanonicalIdsDao dao;

    CanonicalIdSharers( final PageManager pageManager, final PageCanonicalIdsDao dao ) {
        this.pageManager = pageManager;
        this.dao = dao;
    }

    /** Whether {@code pageName} may still be in the page store; a lookup failure counts as "may exist". */
    boolean mayStillExist( final String pageName ) {
        try {
            return pageManager.pageExists( pageName );
        } catch ( final Exception e ) {
            LOG.warn( "pageExists({}) failed — treating the page as live: {}", pageName, e.getMessage() );
            return true;
        }
    }

    /**
     * Other existing pages whose frontmatter carries {@code canonicalId}. Candidates come from the
     * projection (which holds one descriptor per id, so an incremental splice can lose a sharer's slug)
     * and from the id row's current slug and slug history; each is confirmed by re-reading its frontmatter.
     */
    List< String > liveSharersOf( final StructuralProjection projection, final String canonicalId,
                                  final String deletedSlug ) {
        final Set< String > candidates = new LinkedHashSet<>( projection.slugsOf( canonicalId ) );
        try {
            dao.findByCanonicalId( canonicalId ).ifPresent( row -> candidates.add( row.currentSlug() ) );
            candidates.addAll( dao.slugHistory( canonicalId ) );
        } catch ( final RuntimeException dbx ) {
            LOG.warn( "Could not read the id row of {} while checking for sharers: {}",
                      canonicalId, dbx.getMessage() );
        }
        candidates.remove( deletedSlug );
        final List< String > sharers = new ArrayList<>();
        for ( final String slug : candidates ) {
            if ( slug != null && mayStillExist( slug ) && carriesCanonicalId( slug, canonicalId ) ) {
                sharers.add( slug );
            }
        }
        return sharers;
    }

    /** Whether page {@code slug}'s frontmatter names {@code canonicalId}; a read failure counts as yes. */
    private boolean carriesCanonicalId( final String slug, final String canonicalId ) {
        try {
            final Page page = pageManager.getPage( slug );
            if ( page == null ) {
                return false;
            }
            final Map< String, Object > fm = FrontmatterParser.parse( pageManager.getPureText( page ) ).metadata();
            return canonicalId.equals( SpineFrontmatter.asString( fm.get( "canonical_id" ) ) );
        } catch ( final Exception e ) {
            LOG.warn( "Could not read {} while checking it for canonical_id {} — treating it as a sharer: {}",
                      slug, canonicalId, e.getMessage() );
            return true;
        }
    }
}
