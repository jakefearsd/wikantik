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
package com.wikantik.llm.activity;

import java.time.Instant;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.Iterator;
import java.util.List;
import java.util.concurrent.atomic.AtomicLong;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

/**
 * Bounded, in-memory record of recent LLM calls. Thread-safe. Holds at most
 * {@code maxRecords} entries and drops finalized entries older than the configured
 * window. Never persisted — state is lost on restart by design.
 *
 * <p>All public methods are self-protecting: a recorder failure is logged and
 * swallowed so it can never disturb the LLM call being recorded.</p>
 */
public final class LlmActivityLog {

    private static final Logger LOG = LogManager.getLogger( LlmActivityLog.class );

    /** Immutable result of {@link #snapshot}. */
    public record Snapshot( List< LlmCallView > calls, int inFlight, boolean enabled,
                            int windowMinutes, int maxRecords ) {}

    private final boolean enabled;
    private final int windowMinutes;
    private final long windowMillis;
    private final int maxRecords;
    private final int payloadChars;

    private final Deque< LlmCall > buffer = new ArrayDeque<>();
    private final Object lock = new Object();
    private final AtomicLong seq = new AtomicLong();

    public LlmActivityLog( final boolean enabled, final int windowMinutes,
                           final int maxRecords, final int payloadChars ) {
        this.enabled = enabled;
        this.windowMinutes = Math.max( 1, windowMinutes );
        this.windowMillis = this.windowMinutes * 60_000L;
        this.maxRecords = Math.max( 1, maxRecords );
        this.payloadChars = Math.max( 1, payloadChars );
    }

    public boolean enabled()    { return enabled; }
    public int windowMinutes()  { return windowMinutes; }
    public int maxRecords()     { return maxRecords; }

    /** Records the start of a call. Always returns a usable {@link LlmCall}, even on failure. */
    public LlmCall begin( final Subsystem subsystem, final String backend, final String model,
                          final String operation, final String promptPreview ) {
        final LlmCall call = new LlmCall( seq.incrementAndGet(), Instant.now(), System.nanoTime(),
                                          subsystem, backend, model, operation,
                                          truncate( promptPreview ) );
        try {
            synchronized ( lock ) {
                buffer.addLast( call );
                prune();
            }
        } catch ( final RuntimeException e ) {
            LOG.warn( "LlmActivityLog.begin failed: {}", e.getMessage() );
        }
        return call;
    }

    /** Finalizes a call as successful. Emits a DEBUG log line. Never throws. */
    public void succeed( final LlmCall call, final String responsePreview ) {
        try {
            call.finishOk( durationMs( call ), truncate( responsePreview ) );
            LOG.debug( "LLM call ok: {} {} {} {}ms",
                       call.subsystem(), call.model(), call.operation(), call.durationMs() );
        } catch ( final RuntimeException e ) {
            LOG.warn( "LlmActivityLog.succeed failed: {}", e.getMessage() );
        }
    }

    /** Finalizes a call as failed. Emits a WARN log line. Never throws. */
    public void fail( final LlmCall call, final Throwable error ) {
        try {
            final String msg = error == null
                ? "unknown error"
                : error.getClass().getSimpleName() + ": " + error.getMessage();
            call.finishError( durationMs( call ), msg );
            LOG.warn( "LLM call failed: {} {} {} — {}",
                      call.subsystem(), call.model(), call.operation(), msg );
        } catch ( final RuntimeException e ) {
            LOG.warn( "LlmActivityLog.fail failed: {}", e.getMessage() );
        }
    }

    /** Newest-first snapshot, optionally filtered. {@code inFlight} counts ALL in-flight calls. */
    public Snapshot snapshot( final int limit, final Subsystem subsystemFilter,
                              final CallStatus statusFilter ) {
        synchronized ( lock ) {
            prune();
            final List< LlmCallView > out = new ArrayList<>();
            int inFlight = 0;
            final Iterator< LlmCall > it = buffer.descendingIterator();
            while ( it.hasNext() ) {
                final LlmCall c = it.next();
                if ( c.status() == CallStatus.IN_FLIGHT ) {
                    inFlight++;
                }
                if ( subsystemFilter != null && c.subsystem() != subsystemFilter ) {
                    continue;
                }
                if ( statusFilter != null && c.status() != statusFilter ) {
                    continue;
                }
                if ( out.size() < limit ) {
                    out.add( c.toView() );
                }
            }
            return new Snapshot( out, inFlight, enabled, windowMinutes, maxRecords );
        }
    }

    private long durationMs( final LlmCall call ) {
        return Math.max( 0L, ( System.nanoTime() - call.startedNanos() ) / 1_000_000L );
    }

    private String truncate( final String s ) {
        if ( s == null ) {
            return null;
        }
        return s.length() <= payloadChars ? s : s.substring( 0, payloadChars ) + "…";
    }

    /** Caller must hold {@code lock}. Evicts only finalized records; in-flight is never dropped. */
    private void prune() {
        final long cutoff = System.currentTimeMillis() - windowMillis;
        final Iterator< LlmCall > it = buffer.iterator(); // head = oldest
        while ( it.hasNext() ) {
            final LlmCall c = it.next();
            if ( c.status() == CallStatus.IN_FLIGHT ) {
                continue;
            }
            final boolean tooOld = c.startedAt().toEpochMilli() < cutoff;
            final boolean overCap = buffer.size() > maxRecords;
            if ( tooOld || overCap ) {
                it.remove();
            } else {
                break;
            }
        }
    }
}
