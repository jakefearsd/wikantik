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
package com.wikantik.work.runtime;

import com.wikantik.work.WorkNotificationsService;
import com.wikantik.work.WorkService;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import java.sql.SQLException;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;

/**
 * Background pass that turns work events into inbox rows and mails what is due. One daemon thread, woken every
 * {@code wikantik.work.notify.intervalSeconds}; a dispatch that returns a full batch is followed at once by another
 * (up to {@link #MAX_PASSES_PER_CYCLE}). Each step of a cycle is caught on its own, so one failing never skips the
 * others, and a failed step is logged at WARN and tried again next cycle.
 */
public final class WorkNotifyDispatcher implements AutoCloseable {

    private static final Logger LOG = LogManager.getLogger( WorkNotifyDispatcher.class );

    /** The gauge that reads 1 while the dispatcher thread runs and 0 once it has stopped, for whatever reason. */
    public static final String ALIVE_GAUGE = "wikantik_work_dispatcher_alive";

    /** The size of one dispatch batch; a pass that reads this many probably left more behind. */
    static final int FULL_BATCH = 500;

    /** The most dispatch passes in one cycle, so an endless backlog cannot monopolise the thread. */
    static final int MAX_PASSES_PER_CYCLE = 10;

    private final WorkService service;
    private final Clock clock;
    private final WorkDaemon daemon;
    private Instant lastEmail;

    private WorkNotifyDispatcher( final WorkService service, final Clock clock, final Duration interval ) {
        this.service = service;
        this.clock = clock;
        this.daemon = new WorkDaemon( "work-notify-dispatcher", interval, this::cycle,
            "work notify dispatcher stopped — notifications will not be sent until restart", LOG );
    }

    /** Starts the dispatcher; the interval is {@code service.notifications().settings().dispatchInterval()}. */
    public static WorkNotifyDispatcher start( final WorkService service, final Clock clock ) {
        final Duration interval = service.features().notifications().settings().dispatchInterval();
        final WorkNotifyDispatcher dispatcher = new WorkNotifyDispatcher( service, clock, interval );
        dispatcher.daemon.start();
        return dispatcher;
    }

    /** Whether the thread is running (false once closed or stopped by an uncaught throwable). */
    public boolean alive() {
        return daemon.alive();
    }

    /** One cycle: inbox dispatch, webhook delivery, then email when its interval has passed. The cycle runs on one thread only. */
    private void cycle() {
        final WorkNotificationsService notifications = service.features().notifications();
        dispatchPasses( notifications );
        final Instant now = clock.instant();
        if ( !daemon.closed() ) {
            attempt( "webhooks", () -> service.features().webhooks().deliver( now ) );
        }
        if ( !daemon.closed() && emailDue( notifications, now ) ) {
            lastEmail = now;
            attempt( "email", () -> notifications.sendEmail( now ) );
        }
    }

    private void dispatchPasses( final WorkNotificationsService notifications ) {
        for ( int pass = 0; pass < MAX_PASSES_PER_CYCLE && !daemon.closed(); pass++ ) {
            final Integer read = attempt( "dispatch", () -> notifications.dispatch( FULL_BATCH ) );
            if ( read == null || read < FULL_BATCH ) {
                return;
            }
        }
    }

    private boolean emailDue( final WorkNotificationsService notifications, final Instant now ) {
        return lastEmail == null
            || !now.isBefore( lastEmail.plus( notifications.settings().emailInterval() ) );
    }

    /** One step; null when it failed (logged, and the next cycle tries again). */
    private Integer attempt( final String what, final Step step ) {
        try {
            return step.run();
        } catch ( final SQLException | RuntimeException e ) {
            if ( daemon.closed() ) {
                // Interrupted by close(): shutting down, not a failure.
                LOG.debug( "Work notification {} step interrupted by shutdown: {}", what, e.getMessage() );
            } else {
                LOG.warn( "Work notification {} step failed; the next cycle will try again: {}", what,
                    e.getMessage(), e );
            }
            return null;
        }
    }

    @FunctionalInterface
    private interface Step {
        int run() throws SQLException;
    }

    /** Stops the thread and waits briefly for it; safe to call more than once. */
    @Override
    public void close() {
        daemon.close();
    }
}
