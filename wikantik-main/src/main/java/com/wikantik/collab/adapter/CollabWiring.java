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
package com.wikantik.collab.adapter;

import com.wikantik.WikiEngine;
import com.wikantik.api.core.Engine;
import com.wikantik.api.managers.PageManager;
import com.wikantik.api.observability.MeterRegistryHolder;
import com.wikantik.api.pages.PageSaveHelper;
import com.wikantik.auth.SessionMonitor;
import com.wikantik.auth.identity.PublicIdentities;
import com.wikantik.auth.subsystem.AuthSubsystem;
import com.wikantik.auth.subsystem.AuthSubsystemBridge;
import com.wikantik.collab.CollabConfig;
import com.wikantik.collab.CollabMetrics;
import com.wikantik.collab.CollabPorts;
import com.wikantik.collab.CollabSessionRegistry;
import com.wikantik.collab.CollabStore;
import com.wikantik.event.WikiEventManager;
import com.wikantik.jdbc.Jdbc;
import com.wikantik.page.subsystem.PageSubsystemBridge;
import com.wikantik.render.subsystem.RenderingSubsystemBridge;
import io.micrometer.core.instrument.MeterRegistry;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import javax.sql.DataSource;
import java.time.Clock;
import java.util.Optional;
import java.util.Properties;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

/**
 * Builds the engine's {@link CollabSessionRegistry} over the wiki datasource and the three wiki adapters, registers its
 * {@link CollabPageEventListener} on the page manager, the filter manager, the page renamer and the security sources
 * (session monitor, authentication manager, group manager), and drives its {@link CollabSessionRegistry#tick} once a
 * second on one daemon thread. Registered on the engine's service registry (read through {@link CollabEngineAccess} and
 * {@link #registryFor}, never through {@code getManager}), which keeps the listener strongly reachable
 * ({@link WikiEventManager} holds listeners weakly), and closed with the engine.
 *
 * <p>Audit entries go to the engine's {@code AuditService} ({@link AuditServiceCollabAudit}, read per entry as it is
 * built after this wiring) and metrics to the shared {@code MeterRegistry} ({@link MicrometerCollabMetrics}, with the
 * session and participant gauges); with no registry installed the metrics are a no-op and one WARN says so.</p>
 *
 * <p>Wiring happens whenever a datasource exists; with {@code wikantik.collab.enabled} false (the default) the registry
 * is present but {@link CollabSessionRegistry#isEnabled() disabled}; the tick thread runs regardless, as the flag can change. With no datasource nothing
 * is wired and {@link #registryFor} is empty.</p>
 */
public final class CollabWiring implements AutoCloseable {

    private static final Logger LOG = LogManager.getLogger( CollabWiring.class );
    private static final String PROP_MAX_PAGE_BYTES = "wikantik.api.maxPageBytes";
    private static final long DEFAULT_MAX_PAGE_BYTES = 256L * 1024;

    private final CollabSessionRegistry registry;
    /** Held strongly: {@link WikiEventManager} keeps listeners by weak reference. */
    private final CollabPageEventListener listener;
    private final ScheduledExecutorService ticker;

    private CollabWiring( final CollabSessionRegistry registry, final CollabPageEventListener listener,
                          final ScheduledExecutorService ticker ) {
        this.registry = registry;
        this.listener = listener;
        this.ticker = ticker;
    }

    /**
     * Builds and registers the collaboration registry on {@code engine}; empty (nothing registered) when {@code ds} is
     * null.
     */
    public static Optional< CollabWiring > wire( final WikiEngine engine, final Properties props, final DataSource ds,
                                                 final PageManager pages ) {
        if( ds == null ) {
            LOG.info( "Collaboration not wired: no datasource" );
            return Optional.empty();
        }
        return Optional.of( wireWith( engine, props, new JdbcCollabStore( new Jdbc( ds ) ), pages ) );
    }

    /**
     * Builds the registry over {@code store} and the wiki adapters, registers its page event listener on the engine's
     * event sources and the wiring on {@code engine}, and starts the tick. Tests wire an in-memory store this way.
     */
    static CollabWiring wireWith( final WikiEngine engine, final Properties props, final CollabStore store,
                                  final PageManager pages ) {
        final CollabConfig config = CollabConfig.from( props );
        final PageSaveHelperPageWriter writer = new PageSaveHelperPageWriter( pages, new PageSaveHelper( engine, pages ) );
        final MicrometerCollabMetrics meters = meters();
        final CollabSessionRegistry registry = new CollabSessionRegistry( new CollabPorts( store, writer,
                new PublicIdentityCollabIdentity( PublicIdentities.forEngine( engine ) ),
                new WikiCollabPermissions( engine ), Clock.systemUTC(),
                new AuditServiceCollabAudit( engine::getAuditService ),   // built after this wiring: read per entry
                meters == null ? CollabMetrics.NONE : meters ), config, maxPageBytes( props ) );
        if( meters != null ) {
            meters.bindGauges( registry );
        }
        final CollabPageEventListener listener = new CollabPageEventListener( registry, writer );
        final AuthSubsystem.Services auth = AuthSubsystemBridge.fromLegacyEngine( engine );
        listener.register( pages, RenderingSubsystemBridge.fromLegacyEngine( engine ).filterManager(),
                PageSubsystemBridge.fromLegacyEngine( engine ).pageRenamer(),
                SessionMonitor.getInstance( engine ), auth.authentication(), auth.groups() );
        // Always ticking: the enabled flag can be flipped at runtime, and tick() over no sessions is cheap.
        final ScheduledExecutorService ticker = Executors.newSingleThreadScheduledExecutor( r -> {
            final Thread t = new Thread( r, "collab-tick" );
            t.setDaemon( true );
            return t;
        } );
        ticker.scheduleAtFixedRate( guarded( registry::tick ), 1, 1, TimeUnit.SECONDS );
        final CollabWiring wiring = new CollabWiring( registry, listener, ticker );
        engine.setManager( CollabWiring.class, wiring );
        LOG.info( "Collaboration wired (enabled={})", config.enabled() );
        return wiring;
    }

    /** The registry wired on {@code engine}, or empty when none was (no datasource, or not a {@link WikiEngine}). */
    public static Optional< CollabSessionRegistry > registryFor( final Engine engine ) {
        return Optional.ofNullable( CollabEngineAccess.registry( engine ) );
    }

    /** The wired registry. */
    CollabSessionRegistry registry() {
        return registry;
    }

    /** True while the tick thread is running. */
    public boolean isTicking() {
        return ticker != null && !ticker.isShutdown();
    }

    /** One escaping Error would cancel a fixed-rate task for good, so everything is caught and logged. */
    static Runnable guarded( final Runnable tick ) {
        return () -> {
            try {
                tick.run();
            } catch( final Throwable t ) {
                // LOG.error justified: an escaping Throwable (often an Error) from the scheduler's tick, kept alive here
                LOG.error( "Collaboration tick failed; it will run again next second: {}", t.getMessage(), t );
            }
        };
    }

    /** The collaboration meters on the shared registry, or null (one WARN) when none is installed. */
    private static MicrometerCollabMetrics meters() {
        final MeterRegistry shared = MeterRegistryHolder.get();
        if( shared == null ) {
            LOG.warn( "No shared MeterRegistry installed: the wikantik_collab_* metrics will NOT be scraped" );
            return null;
        }
        return new MicrometerCollabMetrics( shared );
    }

    private static long maxPageBytes( final Properties props ) {
        final String raw = props.getProperty( PROP_MAX_PAGE_BYTES );
        if( raw == null || raw.isBlank() ) {
            return DEFAULT_MAX_PAGE_BYTES;
        }
        try {
            final long v = Long.parseLong( raw.trim() );
            return v > 0 ? v : DEFAULT_MAX_PAGE_BYTES;
        } catch( final NumberFormatException e ) {
            LOG.warn( "{} = '{}' is not an integer; using {}: {}", PROP_MAX_PAGE_BYTES, raw, DEFAULT_MAX_PAGE_BYTES,
                    e.getMessage() );
            return DEFAULT_MAX_PAGE_BYTES;
        }
    }

    /**
     * Unregisters the page event listener, stops the tick thread, then stores every live session's draft markers
     * ({@link CollabSessionRegistry#close}) so a restart reads each draft's unpublished state.
     */
    @Override
    public void close() {
        WikiEventManager.removeWikiEventListener( listener );
        if( ticker != null ) {
            stopTicking();
        }
        registry.close();
    }

    private void stopTicking() {
        ticker.shutdownNow();
        try {
            if( !ticker.awaitTermination( 2, TimeUnit.SECONDS ) ) {
                LOG.warn( "Collaboration tick thread did not stop within 2 s of shutdown" );
            }
        } catch( final InterruptedException e ) {
            Thread.currentThread().interrupt();
            LOG.warn( "Interrupted while waiting for the collaboration tick thread to stop: {}", e.getMessage(), e );
        }
    }
}
