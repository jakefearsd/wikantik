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

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import java.time.Duration;
import java.util.concurrent.TimeUnit;

/**
 * One daemon thread that runs a cycle every {@code interval} until closed: the loop, the timed wait, the handler for
 * a throwable that escapes the loop and the bounded join, shared by the claim sweeper and the notification
 * dispatcher. The cycle itself catches its own failures; whatever escapes it ends the thread.
 */
final class WorkDaemon implements AutoCloseable {

    private static final Logger DEFAULT_LOG = LogManager.getLogger( WorkDaemon.class );
    private static final long JOIN_MILLIS = 5_000L;

    private final long intervalNanos;
    private final Runnable cycle;
    private final String stoppedMessage;
    private final Object lock = new Object();
    private final Logger log;
    private final Thread thread;
    private boolean closed;

    WorkDaemon( final String threadName, final Duration interval, final Runnable cycle,
                final String stoppedMessage ) {
        this( threadName, interval, cycle, stoppedMessage, DEFAULT_LOG );
    }

    /** As above, reporting a stopped thread and a slow close through the owner's logger. */
    WorkDaemon( final String threadName, final Duration interval, final Runnable cycle,
                final String stoppedMessage, final Logger log ) {
        this.log = log;
        this.intervalNanos = interval.toNanos();
        this.cycle = cycle;
        this.stoppedMessage = stoppedMessage;
        this.thread = new Thread( this::run, threadName );
        this.thread.setDaemon( true );
        this.thread.setUncaughtExceptionHandler( this::stopped );
    }

    /**
     * Anything that escapes the loop (an {@link Error}; the cycle catches exceptions per step) ends the thread: say so
     * loudly, then hand it on to the JVM's default handler as an uncaught throwable would be.
     */
    private void stopped( final Thread t, final Throwable e ) {
        // LOG.error justified: the work stops until a restart, which an operator must see and act on.
        log.error( "{}: {}", stoppedMessage, e.toString(), e );
        final Thread.UncaughtExceptionHandler fallback = Thread.getDefaultUncaughtExceptionHandler();
        if ( fallback != null ) {
            fallback.uncaughtException( t, e );
        }
    }

    /** Starts the thread. */
    void start() {
        thread.start();
    }

    /** Whether the thread is running (false once closed or stopped by an uncaught throwable). */
    boolean alive() {
        return thread.isAlive();
    }

    /** Whether {@link #close()} has been called; a cycle checks it between steps. */
    boolean closed() {
        synchronized ( lock ) {
            return closed;
        }
    }

    private void run() {
        while ( !closed() ) {
            cycle.run();
            if ( !pause() ) {
                return;
            }
        }
    }

    /** Sleeps one interval; false when closed meanwhile. */
    private boolean pause() {
        final long deadline = System.nanoTime() + intervalNanos;
        synchronized ( lock ) {
            while ( !closed ) {
                final long remaining = deadline - System.nanoTime();
                if ( remaining <= 0 ) {
                    return true;
                }
                try {
                    TimeUnit.NANOSECONDS.timedWait( lock, remaining );
                } catch ( final InterruptedException e ) {
                    Thread.currentThread().interrupt();
                    return false;
                }
            }
            return false;
        }
    }

    /** Stops the thread and waits briefly for it; safe to call more than once. */
    @Override
    public void close() {
        synchronized ( lock ) {
            closed = true;
            lock.notifyAll();
        }
        if ( Thread.currentThread() == thread ) {
            return;
        }
        try {
            thread.join( JOIN_MILLIS );
            if ( thread.isAlive() ) {
                // A cycle stuck in the database: interrupt it rather than leave the thread behind.
                thread.interrupt();
                thread.join( JOIN_MILLIS );
            }
            if ( thread.isAlive() ) {
                log.warn( "The {} thread did not stop within {} ms", thread.getName(), 2 * JOIN_MILLIS );
            }
        } catch ( final InterruptedException e ) {
            Thread.currentThread().interrupt();
        }
    }
}
