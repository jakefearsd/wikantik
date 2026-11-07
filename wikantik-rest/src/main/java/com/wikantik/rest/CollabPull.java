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
package com.wikantik.rest;

import com.wikantik.collab.CollabSession;
import com.wikantik.collab.PullWaiter;
import com.wikantik.collab.model.PullCursor;
import com.wikantik.collab.model.PullResponse;
import com.wikantik.rest.CollabWire.Answer;
import jakarta.servlet.AsyncContext;
import jakarta.servlet.AsyncEvent;
import jakarta.servlet.AsyncListener;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.apache.logging.log4j.ThreadContext;

import java.io.IOException;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.locks.ReentrantLock;
import java.util.function.Consumer;
import java.util.function.Function;

/**
 * One parked long-poll (section 7.5): the {@link PullWaiter} the session completes and the {@link AsyncListener} the
 * container calls. Whatever happens first answers it, and it is written and {@link AsyncContext#complete() completed}
 * exactly once (E32): a wake by another participant's push, the session's own timeout (the registry tick), the
 * container's timeout, a displacement by the participant's next pull, or an I/O error.
 *
 * <p>The session may complete it before the servlet thread has started async mode (it is parked by the time
 * {@code pullOrPark} returns, and a concurrent push may wake it at once): such an answer is kept and written by
 * {@link #start}.</p>
 *
 * <p><b>Writes never block the waker.</b> An answer from the session is handed off ({@link Dispatch}: in production
 * {@link AsyncContext#start}, the container's pool), so a client that stops reading its socket holds up only its own
 * write, never the pusher that woke it, the coalescing scheduler or another participant's answer.</p>
 *
 * <p><b>Coalescing</b> (A8): a 200 answer that wakes a parked pull is handed off {@code coalesceMs} later, so a burst of
 * keystrokes reaches the other editors as one or two pulls rather than one per keystroke (the remaining updates are
 * fetched by the client's next pull at once). Presence wakes are coalesced the same way; the scheduler only schedules.
 * Refusals are handed off at once.</p>
 *
 * <p><b>The container timeout</b> ({@link #onTimeout}, on a container thread) writes the answer itself when no write has
 * begun: the empty answer, or the held one when a coalesced answer was still waiting. When a write is already in flight
 * on another thread it waits (bounded by {@value #IN_FLIGHT_WAIT_SECONDS} s) until that write has completed the request,
 * because returning from {@code onTimeout} with the request incomplete makes the container error-dispatch a response
 * that is still being written.</p>
 *
 * <p><b>A removed participant.</b> The session answers a removed participant's parked pull itself: 403
 * {@code evicted} with the eviction's reason as {@code detail} (its tombstone answers every later call the same way),
 * 409 {@code rejoin} for a timeout, and the presence map for its own leave. This waiter writes what it is given.</p>
 *
 * <p>Every write restores this request's log context (MDC), whatever thread it runs on, so the access and correlation
 * logs name the right request.</p>
 */
final class CollabPull implements PullWaiter, AsyncListener {

    private static final Logger LOG = LogManager.getLogger( CollabPull.class );
    /** How long a container timeout waits for a write already in flight on another thread. */
    static final long IN_FLIGHT_WAIT_SECONDS = 30;

    /** Runs {@code task} after {@code millis}; production uses a scheduler, tests run it inline or by hand. */
    interface Delayer {
        void after( long millis, Runnable task );
    }

    /** Runs a response write off the waking thread; production {@link AsyncContext#start}, tests inline or a pool. */
    interface Dispatch {
        void run( AsyncContext ac, Runnable write );
    }

    /** How answers leave the waking thread: the coalescing delay and the hand-off that runs the write. */
    record Handoff( Delayer delayer, Dispatch dispatch, long coalesceMs ) {
    }

    private final CollabSession session;
    private final String participantId;
    private final PullCursor cursor;
    private final HttpServletResponse response;
    private final Delayer delayer;
    private final Dispatch dispatch;
    private final long coalesceMs;
    private final Consumer< PullResponse > observe;
    private final Map< String, String > mdc;
    private final ReentrantLock lock = new ReentrantLock();
    /** Claimed by whoever begins the one write (or the completion without one). */
    private final AtomicBoolean written = new AtomicBoolean();
    /** Opened once the request is complete. */
    private final CountDownLatch completed = new CountDownLatch( 1 );
    /** Guarded by {@code lock}. */
    private AsyncContext async;
    /** Guarded by {@code lock}: an answer has been chosen. */
    private boolean answered;
    /** Guarded by {@code lock}: the chosen answer. */
    private Answer answer;

    CollabPull( final CollabSession session, final String participantId, final PullCursor cursor,
                final HttpServletResponse response, final Handoff handoff,
                final Consumer< PullResponse > observe ) {
        this.session = session;
        this.participantId = participantId;
        this.cursor = cursor;
        this.response = response;
        this.delayer = handoff.delayer();
        this.dispatch = handoff.dispatch();
        this.coalesceMs = handoff.coalesceMs();
        this.observe = observe;
        this.mdc = ThreadContext.getImmutableContext();
    }

    /**
     * {@code GET /updates}: parses the cursors (400 {@code bad-since}), builds the waiter with {@code make}, and either
     * writes the session's immediate answer or parks the pull in async mode for {@code timeoutMillis}.
     */
    static void serve( final HttpServletRequest request, final HttpServletResponse response,
                       final Function< PullCursor, CollabPull > make, final String authorKey, final long timeoutMillis )
            throws IOException {
        final PullCursor cursor;
        try {
            cursor = CollabRequests.cursor( request );
        } catch( final NumberFormatException e ) {
            LOG.debug( "collab: bad pull cursor: {}", e.getMessage() );
            CollabWire.write( response, CollabWire.reason( 400, "bad-since" ) );
            return;
        }
        final CollabPull waiter = make.apply( cursor );
        final PullResponse now = waiter.session.pullOrPark( waiter.participantId, authorKey, cursor, null, waiter );
        if( now != null ) {
            waiter.observe.accept( now );
            CollabWire.write( response, CollabWire.pulled( now ) );
            return;
        }
        final AsyncContext ac;
        try {
            ac = request.startAsync();
        } catch( final IllegalStateException e ) {
            // a filter on the chain is not async-supported (ApiFilterChainAsyncTest guards web.xml); the session still
            // holds the waiter, which its timeout answers into nothing
            LOG.error( "collab: cannot park the long-poll of {}: {}", waiter.participantId, e.getMessage(), e );
            CollabWire.write( response, CollabWire.reason( 500, "async-unsupported" ) );
            return;
        }
        waiter.start( ac, timeoutMillis );
    }

    /** The session's answer (exactly once per park, from any thread, outside the session lock). */
    @Override
    public void complete( final PullResponse r ) {
        if( isAnswered() ) {
            return;   // the container timed it out or it failed: nothing to observe
        }
        observe.accept( r );
        final Answer a = CollabWire.pulled( r );
        final AsyncContext ac;
        final boolean delay;
        lock.lock();
        try {
            if( answered ) {
                return;
            }
            answered = true;
            answer = a;
            ac = async;
            if( ac == null ) {
                return;   // not in async mode yet: start() writes it
            }
            delay = coalesceMs > 0 && a.status() == 200;
        } finally {
            lock.unlock();
        }
        if( delay ) {
            delayer.after( coalesceMs, () -> handOff( ac, a ) );
        } else {
            handOff( ac, a );
        }
    }

    private boolean isAnswered() {
        lock.lock();
        try {
            return answered;
        } finally {
            lock.unlock();
        }
    }

    /** Runs the write off this thread; if the hand-off is refused, the container timeout still answers (logged). */
    private void handOff( final AsyncContext ac, final Answer a ) {
        try {
            dispatch.run( ac, () -> write( a ) );
        } catch( final RuntimeException e ) {
            LOG.warn( "collab: could not hand off the long-poll answer of {}; its timeout answers it: {}", participantId,
                e.toString() );
        }
    }

    /** Async mode has started on the request thread: arms the listener and writes an answer that came first. */
    void start( final AsyncContext ac, final long timeoutMillis ) {
        ac.setTimeout( timeoutMillis );
        final Answer early;
        lock.lock();
        try {
            async = ac;   // before the listener: a timeout or error may arrive as soon as it is registered
            early = answer;
        } finally {
            lock.unlock();
        }
        ac.addListener( this );
        if( early != null ) {
            write( early );
        }
    }

    @Override
    public void onTimeout( final AsyncEvent event ) {
        final Answer a;
        lock.lock();
        try {
            if( !answered ) {
                answered = true;
                answer = CollabWire.emptyPull( cursor );
            }
            a = answer;
        } finally {
            lock.unlock();
        }
        if( written.compareAndSet( false, true ) ) {
            doWrite( a );
        } else {
            awaitInFlight( "timeout" );
        }
    }

    @Override
    public void onError( final AsyncEvent event ) {
        LOG.warn( "collab: the long-poll of {} failed: {}", participantId,
            event.getThrowable() == null ? "unknown error" : event.getThrowable().toString() );
        lock.lock();
        try {
            answered = true;
        } finally {
            lock.unlock();
        }
        if( written.compareAndSet( false, true ) ) {
            completeAsync();
            completed.countDown();
        } else {
            awaitInFlight( "error" );
        }
    }

    @Override
    public void onComplete( final AsyncEvent event ) {
        written.set( true );
        completed.countDown();
    }

    /** A container callback found another thread's write in flight: wait until it completes the request (bounded). */
    private void awaitInFlight( final String why ) {
        try {
            if( !completed.await( IN_FLIGHT_WAIT_SECONDS, TimeUnit.SECONDS ) ) {
                LOG.warn( "collab: the long-poll answer of {} was still being written {} s after the {}; giving up",
                    participantId, IN_FLIGHT_WAIT_SECONDS, why );
            }
        } catch( final InterruptedException e ) {
            Thread.currentThread().interrupt();
            LOG.warn( "collab: interrupted waiting for the long-poll answer of {} to finish: {}", participantId,
                e.getMessage() );
        }
    }

    @Override
    public void onStartAsync( final AsyncEvent event ) {
        // a collab long-poll never restarts async mode
    }

    private void write( final Answer a ) {
        if( written.compareAndSet( false, true ) ) {
            doWrite( a );
        }
    }

    /** The one write, by the thread that claimed it; completes the request whatever happens. */
    private void doWrite( final Answer a ) {
        final Map< String, String > outer = ThreadContext.getImmutableContext();
        ThreadContext.clearMap();
        ThreadContext.putAll( mdc );
        try {
            CollabWire.write( response, a );
        } catch( final IOException | RuntimeException e ) {
            LOG.warn( "collab: writing the long-poll answer of {} failed: {}", participantId, e.toString() );
        } finally {
            completeAsync();
            completed.countDown();
            ThreadContext.clearMap();
            ThreadContext.putAll( outer );
        }
    }

    private void completeAsync() {
        final AsyncContext ac;
        lock.lock();
        try {
            ac = async;
        } finally {
            lock.unlock();
        }
        try {
            ac.complete();
        } catch( final IllegalStateException e ) {
            LOG.warn( "collab: completing the long-poll of {} failed (already completed by the container?): {}",
                participantId, e.toString() );
        }
    }
}
