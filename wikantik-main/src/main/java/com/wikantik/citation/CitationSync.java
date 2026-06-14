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
package com.wikantik.citation;

import com.wikantik.api.citation.CitationStatus;
import com.wikantik.api.pagegraph.StructuralIndexService;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.function.Function;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/** Orchestrates citation extraction, grading, and inbound re-checks across save/rename/delete + full reconcile. */
public final class CitationSync {

    private static final Logger LOG = LoggerFactory.getLogger( CitationSync.class );

    private final CitationRepository repo;
    private final CitationMarkupParser parser;
    private final CitationStalenessGrader grader;
    private final StructuralIndexService structuralIndex;
    private final Function< String, Optional< String > > bodyLoader;     // slug -> raw body
    private final Function< String, Optional< Integer > > versionLoader; // slug -> current version

    public CitationSync( final CitationRepository repo, final CitationMarkupParser parser,
                         final CitationStalenessGrader grader, final StructuralIndexService structuralIndex,
                         final Function< String, Optional< String > > bodyLoader,
                         final Function< String, Optional< Integer > > versionLoader ) {
        this.repo = repo; this.parser = parser; this.grader = grader; this.structuralIndex = structuralIndex;
        this.bodyLoader = bodyLoader; this.versionLoader = versionLoader;
    }

    /** A page was saved: reconcile its outbound citations and re-grade citations that target it. */
    public void onPageSaved( final String pageName ) {
        bodyLoader.apply( pageName ).ifPresent( body -> reconcileSource( pageName, body ) );
        recheckInbound( pageName );
    }

    /** A page was deleted: best-effort flag inbound citations target_missing (skip renames).
     *  reconcileAll() is the guarantee; this is only the fast path. */
    public void onPageDeleted( final String pageName ) {
        final Optional< String > cid = structuralIndex.resolveCanonicalIdFromSlug( pageName );
        if ( cid.isEmpty() ) { return; }                                 // row already gone — reconcileAll handles it
        if ( structuralIndex.resolveSlugFromCanonicalId( cid.get() ).isPresent() ) { return; }  // rename, not delete
        for ( final CitationRow r : repo.findByTarget( cid.get() ) ) {
            if ( r.status() != CitationStatus.TARGET_MISSING ) {
                repo.updateStatus( r.id(), CitationStatus.TARGET_MISSING, Instant.now() );
            }
        }
    }

    /** A page was renamed: canonical_id is stable, so only inbound re-grade is needed (heading anchors unchanged). */
    public void onPageRenamed( final String oldName, final String newName ) {
        recheckInbound( newName );
    }

    /** Re-grade every citation. Safety net for missed events / silent deletes. */
    public void reconcileAll() {
        for ( final CitationRow r : repo.findAll() ) { regrade( r ); }
    }

    private void reconcileSource( final String pageName, final String body ) {
        final Optional< String > sourceCid = structuralIndex.resolveCanonicalIdFromSlug( pageName );
        if ( sourceCid.isEmpty() ) {
            LOG.debug( "citation: '{}' has no canonical_id; skipping outbound reconcile", pageName );
            return;
        }
        final List< CitationRow > rows = new ArrayList<>();
        for ( final ParsedCitation p : parser.parse( body ) ) {
            final CitationStatus status = grader.grade( p.targetCanonicalId(), p.targetHeadingPath(), p.spanText() );
            final Integer version = structuralIndex.resolveSlugFromCanonicalId( p.targetCanonicalId() )
                    .flatMap( versionLoader ).orElse( null );
            rows.add( new CitationRow( 0, sourceCid.get(), p.targetCanonicalId(), p.targetHeadingPath(),
                    p.spanText(), p.spanHash(), p.claimText(), p.ordinal(), version, status, null, null, null ) );
        }
        repo.replaceForSource( sourceCid.get(), rows );
    }

    private void recheckInbound( final String pageName ) {
        final Optional< String > targetCid = structuralIndex.resolveCanonicalIdFromSlug( pageName );
        if ( targetCid.isEmpty() ) { return; }
        for ( final CitationRow r : repo.findByTarget( targetCid.get() ) ) { regrade( r ); }
    }

    private void regrade( final CitationRow r ) {
        final CitationStatus now = grader.grade( r.targetCanonicalId(), r.targetHeadingPath(), r.spanText() );
        if ( now != r.status() ) { repo.updateStatus( r.id(), now, Instant.now() ); }
        else { repo.touchChecked( r.id(), Instant.now() ); }
    }
}
