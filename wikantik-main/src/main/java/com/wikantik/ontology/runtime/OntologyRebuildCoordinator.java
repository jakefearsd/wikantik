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
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Supplier;

import com.wikantik.api.knowledge.KgEdge;
import com.wikantik.api.knowledge.KgNode;
import com.wikantik.ontology.OntologyModelManager;
import com.wikantik.ontology.projection.PageRecord;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

/**
 * Coordinates asynchronous full rebuilds of the materialized ontology, mirroring
 * {@link com.wikantik.admin.ContentIndexRebuildService}'s state/conflict/disabled semantics.
 * Delegates the actual projection to the pure {@link com.wikantik.ontology.OntologyRebuildService}.
 * Collaborators are injected (no WikiEngine#getManager) so this is ArchUnit-neutral
 * and unit-testable with in-memory data.
 */
public final class OntologyRebuildCoordinator {

    private static final Logger LOG = LogManager.getLogger( OntologyRebuildCoordinator.class );

    public enum State { IDLE, STARTING, RUNNING }

    public static final class ConflictException extends RuntimeException {
        private static final long serialVersionUID = 1L;
        public ConflictException( final String msg ) { super( msg ); }
    }

    public static final class DisabledException extends RuntimeException {
        private static final long serialVersionUID = 1L;
        public DisabledException() { super( "ontology rebuild disabled by configuration" ); }
    }

    private final OntologyModelManager manager;
    private final Supplier< List< KgNode > > nodeSource;
    private final Supplier< List< KgEdge > > edgeSource;
    private final Supplier< List< PageRecord > > pageSource;
    private final boolean enabled;
    private final com.wikantik.ontology.OntologyRebuildService materializer =
            new com.wikantik.ontology.OntologyRebuildService();

    private final AtomicReference< State > state = new AtomicReference<>( State.IDLE );
    private volatile long graphCount = -1;
    private volatile String lastError;

    public OntologyRebuildCoordinator( final OntologyModelManager manager,
                                       final Supplier< List< KgNode > > nodeSource,
                                       final Supplier< List< KgEdge > > edgeSource,
                                       final Supplier< List< PageRecord > > pageSource,
                                       final boolean enabled ) {
        this.manager = manager;
        this.nodeSource = nodeSource;
        this.edgeSource = edgeSource;
        this.pageSource = pageSource;
        this.enabled = enabled;
    }

    public synchronized OntologyRebuildStatus triggerRebuild() {
        if ( !enabled ) {
            throw new DisabledException();
        }
        if ( !state.compareAndSet( State.IDLE, State.STARTING ) ) {
            throw new ConflictException( "ontology rebuild already in state " + state.get() );
        }
        // Capture the snapshot at the STARTING transition, before the daemon thread can
        // advance state to RUNNING — otherwise the returned status races the worker.
        final OntologyRebuildStatus snap = status();
        final Thread t = new Thread( this::runRebuild, "wikantik-ontology-rebuild" );
        t.setDaemon( true );
        t.start();
        return snap;
    }

    /** Kicks a rebuild only if the dataset is empty (startup self-heal). Returns true if started. */
    public synchronized boolean rebuildIfEmpty() {
        if ( !enabled ) {
            return false;
        }
        if ( manager.namedGraphCount() > 0 ) {
            return false;
        }
        try {
            triggerRebuild();
            return true;
        } catch ( final ConflictException e ) {
            LOG.warn( "rebuildIfEmpty: rebuild already running: {}", e.getMessage() );
            return false;
        }
    }

    private void runRebuild() {
        state.set( State.RUNNING );
        try {
            final int written = materializer.rebuild( manager, nodeSource.get(), edgeSource.get(), pageSource.get() );
            graphCount = written;
            lastError = null;
            LOG.info( "ontology rebuild complete: {} named graphs", written );
        } catch ( final RuntimeException e ) {
            lastError = e.getMessage();
            LOG.warn( "ontology rebuild failed: {}", e.getMessage(), e );
        } finally {
            state.set( State.IDLE );
        }
    }

    public OntologyRebuildStatus status() {
        return new OntologyRebuildStatus( state.get().name(), enabled, graphCount, lastError );
    }
}
