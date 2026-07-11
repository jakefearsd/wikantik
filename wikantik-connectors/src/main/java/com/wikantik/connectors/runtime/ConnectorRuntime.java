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
package com.wikantik.connectors.runtime;

import com.wikantik.api.connectors.SourceConnector;
import com.wikantik.connectors.SyncOrchestrator;
import com.wikantik.connectors.SyncReport;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

/** Operable facade over the Phase-1 sync stack: manual trigger, status, and a periodic scheduler. */
public final class ConnectorRuntime {

    private static final Logger LOG = LogManager.getLogger( ConnectorRuntime.class );

    private final ConnectorRegistry registry;
    private final SyncOrchestrator orchestrator;
    private final ConnectorStatusReader statusReader;
    private ScheduledExecutorService executor;

    public ConnectorRuntime( final ConnectorRegistry registry, final SyncOrchestrator orchestrator,
                             final ConnectorStatusReader statusReader ) {
        this.registry = registry;
        this.orchestrator = orchestrator;
        this.statusReader = statusReader;
    }

    public SyncReport syncNow( final String connectorId ) {
        final SourceConnector c = registry.get( connectorId ).orElseThrow(
            () -> new IllegalArgumentException( "unknown connector: " + connectorId ) );
        return orchestrator.sync( c );
    }

    public ConnectorStatus status( final String connectorId ) {
        if ( registry.get( connectorId ).isEmpty() ) {
            throw new IllegalArgumentException( "unknown connector: " + connectorId );
        }
        return statusReader.read( connectorId, registry.typeOf( connectorId ) );
    }

    public List< ConnectorStatus > list() {
        final List< ConnectorStatus > out = new ArrayList<>();
        for ( final String id : registry.ids() ) out.add( statusReader.read( id, registry.typeOf( id ) ) );
        return out;
    }

    public synchronized void startScheduler( final long intervalHours ) {
        if ( intervalHours <= 0 ) {
            LOG.info( "connector sync scheduler disabled (interval={}h)", intervalHours );
            return;
        }
        executor = Executors.newSingleThreadScheduledExecutor( r -> {
            final Thread t = new Thread( r, "wikantik-connector-sync-scheduler" );
            t.setDaemon( true );
            return t;
        } );
        executor.scheduleAtFixedRate( this::syncAll, intervalHours, intervalHours, TimeUnit.HOURS );
        LOG.info( "connector sync scheduler started (every {}h, {} connectors)", intervalHours, registry.ids().size() );
    }

    private void syncAll() {
        for ( final String id : registry.ids() ) {
            try {
                final SyncReport r = syncNow( id );
                LOG.info( "scheduled connector sync '{}': {}", id, r );
            } catch ( final RuntimeException e ) {
                LOG.warn( "scheduled connector sync '{}' failed: {}", id, e.getMessage() );
            }
        }
    }

    public boolean isSchedulerRunning() { return executor != null && !executor.isShutdown(); }

    public synchronized void stop() {
        if ( executor != null ) {
            executor.shutdownNow();
            executor = null;
        }
    }
}
