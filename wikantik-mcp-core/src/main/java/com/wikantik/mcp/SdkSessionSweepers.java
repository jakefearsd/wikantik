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
package com.wikantik.mcp;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import reactor.core.scheduler.Scheduler;
import reactor.core.scheduler.Schedulers;

import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Disposes the MCP SDK's idle-session sweeper schedulers when the webapp stops.
 *
 * <p>Since MCP Java SDK 2.1.0 every streamable HTTP transport sweeps idle sessions on its own
 * {@code Schedulers.newSingle(...)} scheduler, whose thread is non-daemon, and closing the
 * transport disposes the sweep but never that scheduler. A Tomcat stopped with
 * {@code shutdown.sh} then cannot exit, and the next start fails to take the ontology TDB2 lock
 * the stale JVM still holds. Reactor offers no handle on the scheduler, so a Reactor executor
 * decorator, registered before the transports are built, records the sweepers as they start.
 */
public final class SdkSessionSweepers {

    private static final Logger LOG = LogManager.getLogger( SdkSessionSweepers.class );
    static final String DECORATOR_KEY = "wikantik-mcp-session-sweepers";
    /** The SDK's scheduler name; a newSingle scheduler's toString carries it. */
    private static final String SWEEPER_NAME = "streamable-http-server-transport-session-sweeper";
    private static final Set< Scheduler > SWEEPERS = ConcurrentHashMap.newKeySet();

    private SdkSessionSweepers() {}

    /** Starts recording sweeper schedulers; call before building a transport. Safe to call more than once. */
    public static void track() {
        Schedulers.addExecutorServiceDecorator( DECORATOR_KEY, ( scheduler, executor ) -> {
            if ( scheduler.toString().contains( SWEEPER_NAME ) ) {
                SWEEPERS.add( scheduler );
            }
            return executor;
        } );
    }

    /**
     * Disposes every recorded sweeper scheduler, for every MCP endpoint, and uninstalls the
     * decorator; the next {@link #track()} reinstalls it. Call when the webapp stops, after closing
     * the MCP servers — both endpoints live and die with the one webapp. Safe to call more than once.
     */
    public static void disposeAll() {
        for ( final Scheduler sweeper : SWEEPERS ) {
            try {
                sweeper.dispose();
            } catch ( final RuntimeException e ) {
                LOG.warn( "Could not dispose MCP session-sweeper scheduler {}: {}", sweeper, e.getMessage() );
            }
            SWEEPERS.remove( sweeper );
        }
        Schedulers.removeExecutorServiceDecorator( DECORATOR_KEY );
    }
}
