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

import java.nio.file.Path;
import java.util.Properties;

import javax.sql.DataSource;

import com.wikantik.WikiEngine;
import com.wikantik.api.knowledge.Tier;
import com.wikantik.api.managers.PageManager;
import com.wikantik.knowledge.KgEdgeRepository;
import com.wikantik.knowledge.KgNodeRepository;
import com.wikantik.ontology.OntologyModelManager;
import com.wikantik.pagegraph.spine.PageCanonicalIdsDao;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

/**
 * Constructs the ontology runtime from live collaborators and registers the coordinator.
 * Named {@code *WiringHelper} so the decomposition ArchUnit rule permits engine access; it
 * calls only {@code setManager} (never {@code getManager}), so it adds no ArchUnit violation.
 */
public final class OntologyWiringHelper {

    private static final Logger LOG = LogManager.getLogger( OntologyWiringHelper.class );

    private OntologyWiringHelper() {}

    /**
     * Builds the TDB2-backed manager + rebuild coordinator and registers the coordinator on the
     * engine. Called from {@link WikiEngine#initKnowledgeGraph} with already-resolved collaborators.
     */
    public static OntologyRebuildCoordinator wireOntology( final WikiEngine engine,
                                     final Properties props,
                                     final DataSource dataSource,
                                     final PageManager pageManager,
                                     final com.wikantik.filters.FilterManager filterManager ) {
        final boolean enabled = Boolean.parseBoolean(
                props.getProperty( "wikantik.ontology.enabled", "true" ) );
        if ( !enabled ) {
            LOG.info( "ontology layer disabled (wikantik.ontology.enabled=false)" );
            return null;
        }

        final String dir = resolveDir( engine, props );
        final OntologyModelManager mgr = OntologyModelManager.tdb2( dir );
        mgr.loadTBox();

        final KgNodeRepository nodeRepo = new KgNodeRepository( dataSource );
        final KgEdgeRepository edgeRepo = new KgEdgeRepository( dataSource );
        final PageCanonicalIdsDao pageDao = new PageCanonicalIdsDao( dataSource );
        final PageRecordBuilder pageBuilder = new PageRecordBuilder( pageManager, pageDao::findAll );

        // ACL split: a page/node is PUBLIC iff a GUEST (anonymous) session may view it.
        // Reuse one guest session across the whole rebuild (stateless anonymous principal).
        final com.wikantik.api.core.Session guest = com.wikantik.WikiSession.guestSession( engine );
        final com.wikantik.auth.permissions.PermissionFilter permFilter =
                new com.wikantik.auth.permissions.PermissionFilter( engine );
        final java.util.function.Predicate< String > isPublic =
                slug -> permFilter.canAccess( guest, slug, "view" );

        // Tier.MACHINE includes BOTH human- and machine-tier rows (full dump);
        // Tier.HUMAN would exclude machine-tier nodes/edges. Each supplier projects only
        // ACL-public resources (restricted pages/entities/edges are never materialized).
        final OntologyRebuildCoordinator coordinator = new OntologyRebuildCoordinator(
                mgr,
                () -> com.wikantik.ontology.PublicProjectionFilter.publicNodes(
                        nodeRepo.getAllNodes( Tier.MACHINE ), isPublic ),
                () -> com.wikantik.ontology.PublicProjectionFilter.publicEdges(
                        edgeRepo.getAllEdges( Tier.MACHINE ),
                        com.wikantik.ontology.PublicProjectionFilter.publicNodeIds(
                                nodeRepo.getAllNodes( Tier.MACHINE ), isPublic ) ),
                () -> com.wikantik.ontology.PublicProjectionFilter.publicPages( pageBuilder.build(), isPublic ),
                true );

        engine.setManager( OntologyRebuildCoordinator.class, coordinator );
        LOG.info( "ontology runtime wired (tdb2 dir={})", dir );

        // Event-incremental sync: re-project a page's graph on save/rename, remove on true delete.
        // Same ACL gate — a restricted page's save removes it from the public dataset.
        final OntologyPageSync pageSync = new OntologyPageSync( mgr, pageDao, pageManager, isPublic );
        new OntologyEventListener( pageSync ).register( pageManager, filterManager );

        // Nightly full-rebuild backstop (catches KG drift + missed events).
        final long intervalHours = Long.parseLong(
                props.getProperty( "wikantik.ontology.rebuild.interval.hours", "24" ) );
        new OntologyRebuildScheduler( coordinator, intervalHours ).start();

        // Startup-if-empty: non-blocking self-heal on first boot.
        coordinator.rebuildIfEmpty();
        return coordinator;
    }

    private static String resolveDir( final WikiEngine engine, final Properties props ) {
        final String explicit = props.getProperty( "wikantik.ontology.tdb2.dir" );
        if ( explicit != null && !explicit.isBlank() ) {
            return explicit;
        }
        final String workDir = engine.getWorkDir();
        if ( workDir != null && !workDir.isBlank() ) {
            return Path.of( workDir, "ontology-tdb2" ).toString();
        }
        final String tmp = Path.of( System.getProperty( "java.io.tmpdir" ), "wikantik-ontology-tdb2" ).toString();
        LOG.warn( "wikantik.workDir unset; ontology TDB2 store falling back to {}", tmp );
        return tmp;
    }
}
