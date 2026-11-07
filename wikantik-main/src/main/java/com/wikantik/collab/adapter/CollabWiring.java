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
import com.wikantik.api.pages.PageSaveHelper;
import com.wikantik.auth.identity.PublicIdentities;
import com.wikantik.collab.CollabConfig;
import com.wikantik.collab.CollabSessionRegistry;
import com.wikantik.jdbc.Jdbc;
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
 * Builds the engine's {@link CollabSessionRegistry} over the wiki datasource and the three wiki adapters, and drives its
 * {@link CollabSessionRegistry#tick} once a second on one daemon thread. Registered on the engine's service registry
 * (read through {@link #registryFor}, never through {@code getManager}) and closed with the engine.
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
    private final ScheduledExecutorService ticker;

    private CollabWiring( final CollabSessionRegistry registry, final ScheduledExecutorService ticker ) {
        this.registry = registry;
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
        final CollabConfig config = CollabConfig.from( props );
        final CollabSessionRegistry registry = new CollabSessionRegistry(
                new JdbcCollabStore( new Jdbc( ds ) ),
                new PageSaveHelperPageWriter( pages, new PageSaveHelper( engine, pages ) ),
                new WikiCollabPermissions( engine ),
                new PublicIdentityCollabIdentity( PublicIdentities.forEngine( engine ) ),
                Clock.systemUTC(), config, maxPageBytes( props ) );
        // Always ticking: the enabled flag can be flipped at runtime, and tick() over no sessions is cheap.
        final ScheduledExecutorService ticker = Executors.newSingleThreadScheduledExecutor( r -> {
            final Thread t = new Thread( r, "collab-tick" );
            t.setDaemon( true );
            return t;
        } );
        ticker.scheduleAtFixedRate( guarded( registry::tick ), 1, 1, TimeUnit.SECONDS );
        final CollabWiring wiring = new CollabWiring( registry, ticker );
        engine.setManager( CollabWiring.class, wiring );
        LOG.info( "Collaboration wired (enabled={})", config.enabled() );
        return Optional.of( wiring );
    }

    /** The registry wired on {@code engine}, or empty when none was (no datasource, or not a {@link WikiEngine}). */
    public static Optional< CollabSessionRegistry > registryFor( final Engine engine ) {
        if( engine instanceof WikiEngine we ) {
            final CollabWiring wiring = we.serviceRegistry().get( CollabWiring.class );
            return wiring == null ? Optional.empty() : Optional.of( wiring.registry );
        }
        return Optional.empty();
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
                LOG.error( "Collaboration tick failed; it will run again next second: {}", t.getMessage(), t );
            }
        };
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

    @Override
    public void close() {
        if( ticker == null ) {
            return;
        }
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
