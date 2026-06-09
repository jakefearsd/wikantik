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
package com.wikantik.drift;

import com.wikantik.WikiEngine;
import com.wikantik.api.frontmatter.schema.FrontmatterSchema;
import com.wikantik.api.managers.PageManager;
import com.wikantik.frontmatter.schema.SchemaDrivenFrontmatterValidator;
import com.wikantik.frontmatter.schema.SchemaValidationPageFilter;
import com.wikantik.ontology.OntologyShaclValidator;
import com.wikantik.ontology.runtime.OntologyRebuildCoordinator;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import javax.sql.DataSource;
import java.util.List;
import java.util.Properties;
import java.util.function.Supplier;

/**
 * Constructs the drift sweep service and registers it on the engine. Independent of the
 * ontology flag: with the ontology disabled ({@code coordinator == null}) the sweep still
 * works, just without the shacl family and without the scheduled post-rebuild trigger.
 * Named {@code *WiringHelper} per the decomposition convention (setManager only).
 */
public final class DriftWiringHelper {

    private static final Logger LOG = LogManager.getLogger( DriftWiringHelper.class );

    private DriftWiringHelper() {}

    public static void wireDrift( final WikiEngine engine,
                                  final Properties props,
                                  final DataSource dataSource,
                                  final PageManager pageManager,
                                  final OntologyRebuildCoordinator coordinator ) {
        final Supplier< List< OntologyShaclValidator.Violation > > shaclSource;
        if ( coordinator != null && coordinator.modelManager() != null ) {
            final OntologyShaclValidator shacl = new OntologyShaclValidator();
            final var mgr = coordinator.modelManager();
            shaclSource = () -> shacl.validate( mgr.inferenceSnapshot() );
        } else {
            shaclSource = null;
        }

        final DriftSweepService service = new DriftSweepService(
                pageManager,
                new SchemaDrivenFrontmatterValidator( FrontmatterSchema.defaultSchema() ),
                SchemaValidationPageFilter.engineBackedCtx( props, pageManager ),
                shaclSource,
                new DriftSnapshotRepository( dataSource ) );
        engine.setManager( DriftSweepService.class, service );

        if ( coordinator != null ) {
            coordinator.onRebuildComplete( () -> {
                try {
                    service.runSweep( "scheduled" );
                } catch ( final DriftSweepService.SweepAlreadyRunningException e ) {
                    LOG.info( "post-rebuild drift sweep skipped — already running" );
                }
            } );
        }
        LOG.info( "drift sweep wired (shacl={})", shaclSource != null );
    }
}
