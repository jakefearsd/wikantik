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
package com.wikantik.pagegraph.references;

import com.wikantik.util.AtomicFileWriter;
import org.apache.commons.lang3.time.StopWatch;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import java.io.BufferedInputStream;
import java.io.IOException;
import java.io.ObjectInputFilter;
import java.io.ObjectInputStream;
import java.io.ObjectOutputStream;
import java.nio.file.Files;
import java.nio.file.NoSuchFileException;
import java.nio.file.Path;
import java.util.Collection;
import java.util.HashMap;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Supplier;

/**
 * The debounced on-disk snapshot of the reference index ({@code refmgr.ser} in the work directory).
 *
 * <p>Contract: {@link #requestSave()} marks the snapshot dirty and writes it at once unless the previous write is
 * younger than the interval, in which case one deferred write is scheduled for when the interval has elapsed, so a
 * burst of updates costs a handful of writes. {@link #flush()} writes a pending save now and {@link #close()}
 * flushes and stops further deferred writes. A write that fails is logged and the pending flag restored for the
 * deferred path; the previous file is never truncated ({@link AtomicFileWriter}). {@link #load()} never throws: a
 * missing, stale-format, corrupt or disallowed file is an empty result and the caller rebuilds the index.
 *
 * <p>The file format is private and unchanged since 2.7.3: a {@code long} format version, a {@code long}
 * timestamp, then the {@code refersTo} and {@code referredBy} maps as plain {@link HashMap}s. Reading goes through
 * {@link #SAFE_DESERIALIZE_FILTER}, an allow-list of JDK collection and value types.
 */
final class ReferenceSnapshotStore implements AutoCloseable {

    /**
     * Version stamp at the head of the file. Bumped 4 to 5 when Mermaid diagram {@code click} targets began feeding
     * the reference graph: a mismatch makes {@link #load()} return empty, so the manager rescans every page once at
     * the first startup after deploy and existing pages pick up their diagram links.
     */
    static final long FORMAT_VERSION = 5L;

    private static final Logger LOG = LogManager.getLogger( ReferenceSnapshotStore.class );
    private static final String FILE_NAME = "refmgr.ser";

    /** One daemon thread for every store's deferred write (a write is at most one interval late). */
    private static final ScheduledExecutorService FLUSHER = Executors.newSingleThreadScheduledExecutor( r -> {
        final Thread t = new Thread( r, "refmgr-deferred-serialization" );
        t.setDaemon( true );
        return t;
    } );

    /**
     * Set of classes that are safe to deserialize from the reference manager cache.
     * Only standard JDK collection and value types used by the reference data structures are allowed.
     */
    private static final Set< String > SAFE_DESERIALIZE_CLASSES = Set.of(
        "java.util.HashMap", "java.util.LinkedHashMap", "java.util.TreeMap",
        "java.util.ArrayList", "java.util.HashSet", "java.util.LinkedHashSet", "java.util.TreeSet",
        "java.lang.String", "java.lang.Integer", "java.lang.Long",
        "java.lang.Boolean", "java.lang.Float", "java.lang.Double",
        "java.lang.Number", "java.lang.Enum", "java.util.Date"
    );

    /**
     * ObjectInputFilter whitelist for reference manager deserialization. Rejects
     * any class not in the safe set, blocking arbitrary class instantiation attacks.
     */
    static final ObjectInputFilter SAFE_DESERIALIZE_FILTER = filterInfo -> {
        final Class< ? > clazz = filterInfo.serialClass();
        if ( clazz == null ) {
            return ObjectInputFilter.Status.UNDECIDED;
        }
        if ( clazz.isArray() ) {
            final Class< ? > component = clazz.getComponentType();
            if ( component.isPrimitive() || SAFE_DESERIALIZE_CLASSES.contains( component.getName() ) ) {
                return ObjectInputFilter.Status.ALLOWED;
            }
            if ( component.getName().startsWith( "java.util." ) ) {
                return ObjectInputFilter.Status.ALLOWED;
            }
            return ObjectInputFilter.Status.REJECTED;
        }
        if ( SAFE_DESERIALIZE_CLASSES.contains( clazz.getName() ) ) {
            return ObjectInputFilter.Status.ALLOWED;
        }
        return ObjectInputFilter.Status.REJECTED;
    };

    /**
     * What the file holds.
     *
     * @param refersTo   page name to the names it links to
     * @param referredBy page name to the names linking to it
     * @param savedAt    epoch milliseconds the snapshot was taken; a warm start rescans pages modified after it
     */
    record Snapshot( Map< String, Collection< String > > refersTo, Map< String, Set< String > > referredBy, long savedAt ) {
    }

    private final Path file;
    private final Supplier< Snapshot > state;
    private volatile long intervalNanos;
    private final AtomicBoolean pending = new AtomicBoolean();
    private final AtomicBoolean flushScheduled = new AtomicBoolean();
    private final AtomicInteger writes = new AtomicInteger();
    /** {@link System#nanoTime()} of the last write; meaningless until {@link #writtenOnce}. */
    private volatile long lastWriteNanos;
    private volatile boolean writtenOnce;
    private volatile boolean closed;

    /**
     * @param workDir       directory holding {@code refmgr.ser}
     * @param intervalNanos minimum spacing between two debounced writes (0 writes on every request)
     * @param state         called at each write for the live maps; the store copies them, so the supplier may return
     *                      the live instances
     */
    ReferenceSnapshotStore( final Path workDir, final long intervalNanos, final Supplier< Snapshot > state ) {
        this.file = workDir.resolve( FILE_NAME );
        this.intervalNanos = intervalNanos;
        this.state = state;
    }

    /** Test seam: the minimum spacing between debounced writes. */
    void setIntervalNanos( final long intervalNanos ) {
        this.intervalNanos = intervalNanos;
    }

    /** Count of writes so far; lets tests prove a bulk pass writes once. */
    int writeCount() {
        return writes.get();
    }

    /**
     * Reads the snapshot back.
     *
     * @return the snapshot, or empty when the file is missing, was written in another format version, is corrupt, or
     *         holds a class outside the allow-list (all but the first logged)
     */
    synchronized Optional< Snapshot > load() {
        final StopWatch sw = StopWatch.createStarted();
        try ( ObjectInputStream in = new ObjectInputStream( new BufferedInputStream( Files.newInputStream( file ) ) ) ) {
            in.setObjectInputFilter( SAFE_DESERIALIZE_FILTER );
            final long version = in.readLong();
            if ( version != FORMAT_VERSION ) {
                LOG.info( "Reference snapshot {} has format version {}, expected {}; the references will be recalculated",
                          file, version, FORMAT_VERSION );
                return Optional.empty();
            }
            final long savedAt = in.readLong();
            @SuppressWarnings( "unchecked" )
            final Map< String, Collection< String > > refersTo = ( Map< String, Collection< String > > ) in.readObject();
            @SuppressWarnings( "unchecked" )
            final Map< String, Set< String > > referredBy = ( Map< String, Set< String > > ) in.readObject();
            LOG.debug( "Read serialized data successfully in {}", sw );
            return Optional.of( new Snapshot( refersTo, referredBy, savedAt ) );
        } catch ( final NoSuchFileException e ) {
            LOG.debug( "No reference snapshot at {}", file );
            return Optional.empty();
        } catch ( final IOException | ClassNotFoundException | RuntimeException e ) {
            LOG.warn( "Unable to read reference snapshot {}; the references will be recalculated: {}", file, e.getMessage(), e );
            return Optional.empty();
        }
    }

    /** Marks the snapshot dirty and writes it now unless the last write is younger than the interval. */
    void requestSave() {
        pending.set( true );
        final long sinceLast = System.nanoTime() - lastWriteNanos;
        if ( !writtenOnce || sinceLast >= intervalNanos ) {
            flush();
        } else if ( flushScheduled.compareAndSet( false, true ) ) {
            // bound the delay: with no further update and no shutdown the pending write would otherwise wait forever
            FLUSHER.schedule( this::deferredFlush, intervalNanos - sinceLast, TimeUnit.NANOSECONDS );
        }
    }

    private void deferredFlush() {
        flushScheduled.set( false );
        if ( closed ) {
            return; // close() already flushed; the work dir may be gone
        }
        try {
            flush();
        } catch ( final RuntimeException e ) {
            LOG.warn( "Deferred reference-database write failed; it is retried on the next update: {}", e.getMessage(), e );
            pending.set( true );
        }
    }

    /** Writes the snapshot if a save is pending. */
    void flush() {
        if ( pending.getAndSet( false ) ) {
            saveNow();
        }
    }

    /**
     * Writes the snapshot unconditionally (a bulk pass that has just rebuilt the index), clearing any pending save.
     * An I/O failure is logged and leaves the previous file intact. The live maps are copied into plain
     * {@link HashMap}s: a {@code ConcurrentHashMap}'s serial form carries {@code Segment}/{@code ReentrantLock}
     * internals that {@link #SAFE_DESERIALIZE_FILTER} rejects, which silently turned every warm start into a full
     * rebuild.
     */
    synchronized void saveNow() {
        writes.incrementAndGet();
        pending.set( false );
        lastWriteNanos = System.nanoTime();
        writtenOnce = true;
        try {
            final StopWatch sw = StopWatch.createStarted();
            final Snapshot snapshot = state.get();
            AtomicFileWriter.write( file, raw -> {
                try ( ObjectOutputStream out = new ObjectOutputStream( raw ) ) {
                    out.writeLong( FORMAT_VERSION );
                    out.writeLong( snapshot.savedAt() );
                    out.writeObject( new HashMap<>( snapshot.refersTo() ) );
                    out.writeObject( new HashMap<>( snapshot.referredBy() ) );
                }
            } );
            LOG.debug( "serialization done - took {}", sw );
        } catch ( final IOException ioe ) {
            LOG.error( "Unable to serialize!", ioe );
        }
    }

    /** Flushes a pending save and stops deferred writes; waits for a write already in progress. Idempotent. */
    @Override
    public synchronized void close() {
        closed = true;
        flush();
    }
}
