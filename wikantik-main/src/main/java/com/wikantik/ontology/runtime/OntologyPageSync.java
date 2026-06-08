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

import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

import com.wikantik.api.managers.PageManager;
import com.wikantik.ontology.Iris;
import com.wikantik.ontology.OntologyModelManager;
import com.wikantik.ontology.projection.ConceptProjector;
import com.wikantik.ontology.projection.PageProjector;
import com.wikantik.ontology.projection.PageRecord;
import com.wikantik.pagegraph.spine.PageCanonicalIdsDao;
import org.apache.jena.rdf.model.Model;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

/**
 * Single-page incremental re-projection into the ontology dataset. Page + concept
 * graphs only — entity graphs change via the nightly full rebuild (no KG events exist).
 */
public final class OntologyPageSync {

    private static final Logger LOG = LogManager.getLogger( OntologyPageSync.class );

    private final OntologyModelManager manager;
    private final PageCanonicalIdsDao dao;
    private final PageManager pageManager;
    /** slug -> public? — only anonymously-viewable pages are projected into the public dataset. */
    private final java.util.function.Predicate< String > isPublic;
    /** slug -> canonical_id, populated on save so deletes can resolve the page IRI after the row is gone. */
    private final ConcurrentHashMap< String, String > slugToCanonical = new ConcurrentHashMap<>();

    public OntologyPageSync( final OntologyModelManager manager, final PageCanonicalIdsDao dao,
                             final PageManager pageManager, final java.util.function.Predicate< String > isPublic ) {
        this.manager = manager;
        this.dao = dao;
        this.pageManager = pageManager;
        this.isPublic = isPublic;
    }

    /** Re-projects the page's named graph + its tag/cluster concept graphs (public pages only). */
    public void onPageSaved( final String slug ) {
        final Optional< PageCanonicalIdsDao.Row > row = dao.findBySlug( slug );
        if ( row.isEmpty() ) {
            LOG.warn( "onPageSaved: no canonical_id row for slug '{}'; skipping (nightly rebuild will reconcile)", slug );
            return;
        }
        final PageRecord record = PageRecordBuilder.fromRow( row.get(), pageManager );
        slugToCanonical.put( slug, record.canonicalId() );
        if ( !isPublic.test( slug ) ) {
            // Page is (now) ACL-restricted: ensure it is absent from the public dataset.
            manager.removeNamedGraph( Iris.page( record.canonicalId() ) );
            return;
        }
        manager.replaceNamedGraph( Iris.page( record.canonicalId() ), PageProjector.project( record ) );
        for ( final Map.Entry< String, Model > c : ConceptProjector.project( List.of( record ) ).entrySet() ) {
            manager.replaceNamedGraph( c.getKey(), c.getValue() );
        }
    }

    /**
     * Removes the page graph ONLY if the canonical_id is truly gone. If the id still
     * lives (rename surfaced as delete-of-old), the graph is left for the save event.
     */
    public void onPageDeleted( final String slug ) {
        String canonicalId = slugToCanonical.remove( slug );
        if ( canonicalId == null ) {
            canonicalId = dao.findBySlug( slug ).map( PageCanonicalIdsDao.Row::canonicalId ).orElse( null );
        }
        if ( canonicalId == null ) {
            LOG.warn( "onPageDeleted: cannot resolve canonical_id for slug '{}'; nightly rebuild will prune", slug );
            return;
        }
        if ( dao.findByCanonicalId( canonicalId ).isPresent() ) {
            LOG.info( "onPageDeleted: canonical_id {} still lives (rename of '{}'); keeping page graph", canonicalId, slug );
            return;
        }
        manager.removeNamedGraph( Iris.page( canonicalId ) );
    }

    /** Rename: the page IRI keys on the stable canonical_id, so re-project under the new slug. */
    public void onPageRenamed( final String oldSlug, final String newSlug ) {
        slugToCanonical.remove( oldSlug );
        onPageSaved( newSlug );
    }
}
