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
package com.wikantik.insights.runtime;

import com.wikantik.insights.readers.ReaderAnalyticsReadback;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

/**
 * Timer for {@link ReaderAnalyticsReadback}: one daemon thread, first run
 * {@value #INITIAL_DELAY_MINUTES} minutes after start (so a deploy yields data the same day rather than one
 * full interval later), then every {@code intervalHours}. A tick never throws -- a failed tick must not end
 * the schedule.
 */
public final class ReaderAnalyticsScheduler implements AutoCloseable {

    private static final Logger LOG = LogManager.getLogger( ReaderAnalyticsScheduler.class );

    static final String THREAD_NAME = "wikantik-reader-readback";
    static final long INITIAL_DELAY_MINUTES = 5;

    private final Runnable task;
    private final long initialDelay;
    private final long period;
    private final TimeUnit unit;
    private ScheduledExecutorService executor;

    public ReaderAnalyticsScheduler( final ReaderAnalyticsReadback readback, final int intervalHours ) {
        this( readback::runOnce, INITIAL_DELAY_MINUTES, Math.max( 1, intervalHours ) * 60L, TimeUnit.MINUTES );
    }

    ReaderAnalyticsScheduler( final Runnable task, final long initialDelay, final long period, final TimeUnit unit ) {
        this.task = task;
        this.initialDelay = initialDelay;
        this.period = period;
        this.unit = unit;
    }

    public synchronized void start() {
        if ( executor != null ) {
            return;
        }
        executor = Executors.newSingleThreadScheduledExecutor( r -> {
            final Thread t = new Thread( r, THREAD_NAME );
            t.setDaemon( true );
            return t;
        } );
        executor.scheduleAtFixedRate( this::tick, initialDelay, period, unit );
        LOG.info( "reader analytics read-back scheduled (first run in {} {}, then every {} {})",
                initialDelay, unit, period, unit );
    }

    private void tick() {
        try {
            task.run();
        } catch ( final RuntimeException e ) {
            LOG.warn( "scheduled reader analytics read-back failed: {}", e.getMessage(), e );
        }
    }

    public synchronized void stop() {
        if ( executor != null ) {
            executor.shutdownNow();
            executor = null;
        }
    }

    @Override
    public void close() {
        stop();
    }
}
