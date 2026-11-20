package com.wikantik.pagegraph.references;

import com.wikantik.pagegraph.references.ReferenceSnapshotStore.Snapshot;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.ObjectOutputStream;
import java.io.OutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.TreeSet;
import java.util.Collection;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.ConcurrentModificationException;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;
import java.time.Duration;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTimeoutPreemptively;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ReferenceSnapshotStoreTest {

    private static final long LONG_INTERVAL = TimeUnit.HOURS.toNanos( 1 );

    private static Snapshot graph( final String page, final String target ) {
        final Map< String, Collection< String > > refersTo = new HashMap<>();
        refersTo.put( page, new TreeSet<>( List.of( target ) ) );
        final Map< String, Set< String > > referredBy = new HashMap<>();
        referredBy.put( target, new HashSet<>( Set.of( page ) ) );
        return new Snapshot( refersTo, referredBy, 42L );
    }

    private static ReferenceSnapshotStore store( final Path dir, final long intervalNanos, final AtomicReference< Snapshot > state ) {
        return new ReferenceSnapshotStore( dir, intervalNanos, state::get );
    }

    @Test
    void loadReadsTheSnapshotWrittenByRelease273( @TempDir final Path dir ) throws Exception {
        try ( var fixture = getClass().getResourceAsStream( "/references/refmgr-2.7.3.ser" ) ) {
            Files.copy( fixture, dir.resolve( "refmgr.ser" ), StandardCopyOption.REPLACE_EXISTING );
        }

        final Snapshot snapshot = store( dir, LONG_INTERVAL, new AtomicReference<>() ).load().orElseThrow();

        assertEquals( Set.of( "Beta", "Gamma" ), Set.copyOf( snapshot.refersTo().get( "Alpha" ) ) );
        assertEquals( Set.of( "Gamma" ), Set.copyOf( snapshot.refersTo().get( "Beta" ) ) );
        assertEquals( Set.of( "Alpha", "Beta" ), snapshot.referredBy().get( "Gamma" ) );
        assertTrue( snapshot.savedAt() > 0 );
    }

    @Test
    void aSavedSnapshotLoadsBackEqual( @TempDir final Path dir ) {
        final AtomicReference< Snapshot > state = new AtomicReference<>( graph( "A", "B" ) );
        final ReferenceSnapshotStore store = store( dir, 0L, state );

        store.saveNow();
        final Snapshot loaded = store.load().orElseThrow();

        assertEquals( state.get().refersTo(), loaded.refersTo() );
        assertEquals( state.get().referredBy(), loaded.referredBy() );
    }

    @Test
    void loadIsEmptyWhenThereIsNoFile( @TempDir final Path dir ) {
        assertEquals( Optional.empty(), store( dir, LONG_INTERVAL, new AtomicReference<>() ).load() );
    }

    @Test
    void loadIsEmptyWhenTheFormatVersionDiffers( @TempDir final Path dir ) throws Exception {
        writeRaw( dir, 4L, new HashMap<>(), new HashMap<>() );

        assertEquals( Optional.empty(), store( dir, LONG_INTERVAL, new AtomicReference<>() ).load() );
    }

    @Test
    void loadRejectsAFileHoldingAClassOutsideTheAllowList( @TempDir final Path dir ) throws Exception {
        writeRaw( dir, ReferenceSnapshotStore.FORMAT_VERSION, new ConcurrentHashMap<>( Map.of( "a", 1 ) ), new HashMap<>() );

        assertEquals( Optional.empty(), store( dir, LONG_INTERVAL, new AtomicReference<>() ).load() );
    }

    @Test
    void requestingTwiceWithinTheIntervalWritesOnce( @TempDir final Path dir ) {
        final ReferenceSnapshotStore store = store( dir, LONG_INTERVAL, new AtomicReference<>( graph( "A", "B" ) ) );

        store.requestSave();
        store.requestSave();

        assertEquals( 1, store.writeCount() );
    }

    @Test
    void closeFlushesAPendingSave( @TempDir final Path dir ) {
        final AtomicReference< Snapshot > state = new AtomicReference<>( graph( "A", "B" ) );
        final ReferenceSnapshotStore store = store( dir, LONG_INTERVAL, state );
        store.requestSave();                  // first save writes
        state.set( graph( "C", "D" ) );
        store.requestSave();                  // inside the interval: pending

        store.close();

        assertEquals( 2, store.writeCount() );
        assertTrue( store( dir, LONG_INTERVAL, state ).load().orElseThrow().refersTo().containsKey( "C" ) );
    }

    @Test
    void closeWithNothingPendingDoesNotWrite( @TempDir final Path dir ) {
        final ReferenceSnapshotStore store = store( dir, LONG_INTERVAL, new AtomicReference<>( graph( "A", "B" ) ) );
        store.requestSave();

        store.close();
        store.close();

        assertEquals( 1, store.writeCount() );
    }

    @Test
    void aFailedWriteKeepsThePreviousSnapshot( @TempDir final Path dir ) throws Exception {
        final byte[] previous = { 1, 2, 3 };
        Files.write( dir.resolve( "refmgr.ser" ), previous );
        final ReferenceSnapshotStore store = store( dir.resolve( "refmgr.ser" ), 0L, new AtomicReference<>( graph( "A", "B" ) ) );

        store.saveNow(); // work "directory" is a file: the write fails and is logged, not thrown

        assertArrayEquals( previous, Files.readAllBytes( dir.resolve( "refmgr.ser" ) ) );
    }

    @Test
    void aRuntimeExceptionWhileWritingIsLoggedNotThrownIntoTheCaller( @TempDir final Path dir ) {
        final ReferenceSnapshotStore store = new ReferenceSnapshotStore( dir, 0L, () -> {
            throw new ConcurrentModificationException( "index changed under the snapshot" );
        } );

        assertDoesNotThrow( store::requestSave, "a page save or rename must not fail because the snapshot write did" );
        assertDoesNotThrow( store::saveNow );
        assertFalse( Files.exists( dir.resolve( "refmgr.ser" ) ), "nothing usable was captured: nothing is written" );
    }

    /**
     * The state supplier locks the index it copies (the manager's monitor), and the manager may hold that monitor
     * when it asks for a save. A write that is waiting for the monitor must never make that save wait for the write
     * in turn: here the "holder" thread owns the state lock and requests a save while the writer thread is blocked in
     * the supplier on that lock. The holder's request must return at once, and the write that follows it must carry
     * the holder's newer state.
     */
    @Test
    void aSaveRequestedUnderTheStateLockNeverWaitsForAWriteThatNeedsTheLock( @TempDir final Path dir ) throws Exception {
        final Object stateLock = new Object();
        final AtomicReference< Snapshot > state = new AtomicReference<>( graph( "A", "B" ) );
        final ReferenceSnapshotStore store = new ReferenceSnapshotStore( dir, 0L, () -> {
            synchronized ( stateLock ) {
                return state.get();
            }
        } );
        final Thread writer = new Thread( store::saveNow, "snapshot-writer" );
        writer.setDaemon( true );
        final CountDownLatch holderReturned = new CountDownLatch( 1 );
        final Thread holder = new Thread( () -> {
            synchronized ( stateLock ) {
                writer.start();
                while ( writer.getState() != Thread.State.BLOCKED ) {
                    Thread.onSpinWait();
                }
                state.set( graph( "C", "D" ) );
                store.saveNow();
                holderReturned.countDown();
            }
        }, "state-lock-holder" );
        holder.setDaemon( true );

        holder.start();

        assertTrue( holderReturned.await( 5, TimeUnit.SECONDS ), "deadlock: the save under the state lock waited for the write" );
        writer.join( 5_000 );
        assertFalse( writer.isAlive(), "the write finishes once the state lock is released" );
        assertTrue( store.load().orElseThrow().refersTo().containsKey( "C" ), "the newest state is the one on disk" );
    }

    /**
     * A save requested before {@code close()} began is written by the time {@code close()} returns, even when the
     * requesting thread is still between registering the request and handing the write over; and once
     * {@code close()} has returned that thread writes nothing (the work directory may already be gone). The hook
     * parks the requester in exactly that gap while the test closes the store.
     */
    @Test
    void closeWritesASaveThatWasStillBeingHandedOverWhenCloseBegan( @TempDir final Path dir ) throws Exception {
        final AtomicReference< Snapshot > state = new AtomicReference<>( graph( "A", "B" ) );
        final ReferenceSnapshotStore store = store( dir, 0L, state );
        final CountDownLatch inGap = new CountDownLatch( 1 );
        final CountDownLatch resume = new CountDownLatch( 1 );
        final AtomicBoolean parkedOnce = new AtomicBoolean();
        store.setHandOffHook( () -> {
            if ( parkedOnce.compareAndSet( false, true ) ) {
                inGap.countDown();
                awaitQuietly( resume );
            }
        } );
        state.set( graph( "C", "D" ) );
        final Thread requester = new Thread( store::requestSave, "snapshot-requester" );
        requester.setDaemon( true );
        try {
            requester.start();
            assertTrue( inGap.await( 5, TimeUnit.SECONDS ), "the requester reaches the hand-off" );

            store.close();

            assertTrue( store.load().map( s -> s.refersTo().containsKey( "C" ) ).orElse( false ),
                    "close() must write the save requested before it began" );
            final int writesAtClose = store.writeCount();
            resume.countDown();
            requester.join( 5_000 );
            assertFalse( requester.isAlive() );
            assertEquals( writesAtClose, store.writeCount(), "nothing is written after close() returned" );
        } finally {
            resume.countDown();
        }
    }

    @Test
    void aSaveRequestedAfterCloseWritesNothing( @TempDir final Path dir ) {
        final ReferenceSnapshotStore store = store( dir, 0L, new AtomicReference<>( graph( "A", "B" ) ) );
        store.close();

        assertDoesNotThrow( store::requestSave );
        assertDoesNotThrow( store::saveNow );
        assertDoesNotThrow( store::flush );
        store.close();

        assertEquals( 0, store.writeCount(), "a request after close() is a no-op" );
        assertFalse( Files.exists( dir.resolve( "refmgr.ser" ) ), "a closed store must not write into the work dir" );
    }

    /** A write that never finishes (a hung disk) must not hang shutdown: close() gives up after its bounded wait. */
    @Test
    void closeDoesNotWaitForeverForAWriteThatNeverFinishes( @TempDir final Path dir ) throws Exception {
        final CountDownLatch inWrite = new CountDownLatch( 1 );
        final CountDownLatch release = new CountDownLatch( 1 );
        final ReferenceSnapshotStore store = new ReferenceSnapshotStore( dir, 0L, () -> {
            inWrite.countDown();
            awaitQuietly( release );
            return graph( "A", "B" );
        } );
        store.setCloseWaitNanos( TimeUnit.MILLISECONDS.toNanos( 50 ) );
        final Thread writer = new Thread( store::saveNow, "stuck-writer" );
        writer.setDaemon( true );
        try {
            writer.start();
            assertTrue( inWrite.await( 5, TimeUnit.SECONDS ), "the writer is inside its write" );

            assertTimeoutPreemptively( Duration.ofSeconds( 5 ), store::close, "close() blocked on a write that never ends" );
        } finally {
            release.countDown();
        }
        writer.join( 5_000 );
        assertFalse( writer.isAlive() );
    }

    private static void awaitQuietly( final CountDownLatch latch ) {
        try {
            if ( !latch.await( 10, TimeUnit.SECONDS ) ) {
                throw new IllegalStateException( "test latch was never released" );
            }
        } catch ( final InterruptedException e ) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException( "interrupted while parked", e );
        }
    }

    private static void writeRaw( final Path dir, final long version, final Object first, final Object second ) throws Exception {
        try ( OutputStream raw = Files.newOutputStream( dir.resolve( "refmgr.ser" ) );
              ObjectOutputStream out = new ObjectOutputStream( raw ) ) {
            out.writeLong( version );
            out.writeLong( 1L );
            out.writeObject( first );
            out.writeObject( second );
        }
    }
}
