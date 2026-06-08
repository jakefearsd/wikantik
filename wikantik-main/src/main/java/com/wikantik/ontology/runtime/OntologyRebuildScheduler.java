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

import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

/** Periodically triggers a full ontology rebuild as the backstop for missed/uneventful changes. */
public final class OntologyRebuildScheduler {

    private static final Logger LOG = LogManager.getLogger( OntologyRebuildScheduler.class );

    private final OntologyRebuildCoordinator coordinator;
    private final long intervalHours;
    private ScheduledExecutorService executor;

    public OntologyRebuildScheduler( final OntologyRebuildCoordinator coordinator, final long intervalHours ) {
        this.coordinator = coordinator;
        this.intervalHours = intervalHours;
    }

    /** Starts the timer (no-op when intervalHours <= 0). First run is one interval out. */
    public void start() {
        if ( intervalHours <= 0 ) {
            LOG.info( "ontology rebuild scheduler disabled (interval={}h)", intervalHours );
            return;
        }
        executor = Executors.newSingleThreadScheduledExecutor( r -> {
            final Thread t = new Thread( r, "wikantik-ontology-rebuild-scheduler" );
            t.setDaemon( true );
            return t;
        } );
        executor.scheduleAtFixedRate( this::runOnce, intervalHours, intervalHours, TimeUnit.HOURS );
        LOG.info( "ontology rebuild scheduler started (every {}h)", intervalHours );
    }

    /** One scheduled tick — triggers a rebuild, swallowing the "already running" conflict. */
    void runOnce() {
        try {
            coordinator.triggerRebuild();
            LOG.info( "scheduled ontology rebuild triggered" );
        } catch ( final OntologyRebuildCoordinator.ConflictException e ) {
            LOG.info( "scheduled ontology rebuild skipped — already running" );
        } catch ( final OntologyRebuildCoordinator.DisabledException e ) {
            LOG.info( "scheduled ontology rebuild skipped — disabled" );
        } catch ( final RuntimeException e ) {
            LOG.warn( "scheduled ontology rebuild failed: {}", e.getMessage(), e );
        }
    }

    public void stop() {
        if ( executor != null ) {
            executor.shutdownNow();
        }
    }
}
