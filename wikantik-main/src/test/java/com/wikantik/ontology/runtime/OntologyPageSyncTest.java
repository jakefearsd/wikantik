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

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.when;

import java.time.Instant;
import java.util.Optional;

import com.wikantik.api.managers.PageManager;
import com.wikantik.ontology.Iris;
import com.wikantik.ontology.OntologyModelManager;
import com.wikantik.pagegraph.spine.PageCanonicalIdsDao;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith( MockitoExtension.class )
class OntologyPageSyncTest {

    @Mock PageManager pageManager;
    @Mock PageCanonicalIdsDao dao;

    private static final String CID = "01CANON0000000000000000010";

    private PageCanonicalIdsDao.Row row( final String slug ) {
        return new PageCanonicalIdsDao.Row( CID, slug, "T", "article", "ml", Instant.EPOCH, Instant.EPOCH );
    }

    private OntologyPageSync sync( final OntologyModelManager mgr ) {
        return new OntologyPageSync( mgr, dao, pageManager, slug -> true );
    }

    @Test
    void onPageSavedProjectsPageAndConceptGraphs() {
        final OntologyModelManager mgr = OntologyModelManager.inMemory();
        mgr.loadTBox();
        when( dao.findBySlug( "Solo" ) ).thenReturn( Optional.of( row( "Solo" ) ) );
        when( pageManager.getPureText( "Solo", -1 ) ).thenReturn( "---\ntags: [graphs]\n---\nbody" );

        sync( mgr ).onPageSaved( "Solo" );

        assertTrue( mgr.namedGraphExists( Iris.page( CID ) ), "page graph projected" );
        assertTrue( mgr.namedGraphExists( Iris.concept( "graphs" ) ), "tag concept graph projected" );
        assertTrue( mgr.namedGraphExists( Iris.concept( "ml" ) ), "cluster concept graph projected" );
    }

    @Test
    void onPageDeletedRemovesGraphWhenCanonicalIdIsGone() {
        final OntologyModelManager mgr = OntologyModelManager.inMemory();
        mgr.loadTBox();
        when( dao.findBySlug( "Solo" ) ).thenReturn( Optional.of( row( "Solo" ) ) );
        when( pageManager.getPureText( "Solo", -1 ) ).thenReturn( "---\ntags: [graphs]\n---\nbody" );
        final OntologyPageSync s = sync( mgr );
        s.onPageSaved( "Solo" );
        assertTrue( mgr.namedGraphExists( Iris.page( CID ) ) );

        when( dao.findByCanonicalId( CID ) ).thenReturn( Optional.empty() );
        s.onPageDeleted( "Solo" );
        assertFalse( mgr.namedGraphExists( Iris.page( CID ) ), "page graph removed on true delete" );
    }

    @Test
    void onPageDeletedKeepsGraphWhenCanonicalIdStillLives_renameCase() {
        final OntologyModelManager mgr = OntologyModelManager.inMemory();
        mgr.loadTBox();
        when( dao.findBySlug( "Old" ) ).thenReturn( Optional.of( row( "Old" ) ) );
        when( pageManager.getPureText( "Old", -1 ) ).thenReturn( "---\ntags: [graphs]\n---\nbody" );
        final OntologyPageSync s = sync( mgr );
        s.onPageSaved( "Old" );

        when( dao.findByCanonicalId( CID ) ).thenReturn( Optional.of( row( "New" ) ) );
        s.onPageDeleted( "Old" );
        assertTrue( mgr.namedGraphExists( Iris.page( CID ) ),
                "rename must NOT drop the page graph (canonical_id still lives)" );
    }

    @Test
    void onPageRenamedReprojectsUnderNewSlug() {
        final OntologyModelManager mgr = OntologyModelManager.inMemory();
        mgr.loadTBox();
        when( dao.findBySlug( "New" ) ).thenReturn( Optional.of( row( "New" ) ) );
        when( pageManager.getPureText( "New", -1 ) ).thenReturn( "---\ntags: [graphs]\n---\nbody" );

        sync( mgr ).onPageRenamed( "Old", "New" );
        assertTrue( mgr.namedGraphExists( Iris.page( CID ) ), "page graph present after rename re-projection" );
    }

    @Test
    void onPageSavedRemovesGraphWhenPageBecomesRestricted() {
        final OntologyModelManager mgr = OntologyModelManager.inMemory();
        mgr.loadTBox();
        when( dao.findBySlug( "Solo" ) ).thenReturn( Optional.of( row( "Solo" ) ) );
        when( pageManager.getPureText( "Solo", -1 ) ).thenReturn( "---\ntags: [graphs]\n---\nbody" );
        // First public:
        new OntologyPageSync( mgr, dao, pageManager, slug -> true ).onPageSaved( "Solo" );
        assertTrue( mgr.namedGraphExists( Iris.page( CID ) ) );
        // Now restricted: a save must remove it from the public dataset.
        new OntologyPageSync( mgr, dao, pageManager, slug -> false ).onPageSaved( "Solo" );
        assertFalse( mgr.namedGraphExists( Iris.page( CID ) ), "restricted page graph removed on save" );
    }
}
