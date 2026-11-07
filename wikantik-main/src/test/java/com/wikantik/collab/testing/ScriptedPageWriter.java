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
package com.wikantik.collab.testing;

import com.wikantik.api.exceptions.WikiException;
import com.wikantik.api.pages.VersionConflictException;
import com.wikantik.collab.CollabPageWriter;
import com.wikantik.collab.PageReadException;
import com.wikantik.collab.PublishReadBackException;
import com.wikantik.collab.model.PageState;
import com.wikantik.collab.model.PublishOutcome;
import com.wikantik.collab.model.PublishRequest;
import com.wikantik.collab.text.CollabText;
import com.wikantik.util.TextUtil;

import java.time.Duration;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.locks.Condition;
import java.util.concurrent.locks.ReentrantLock;
import java.util.function.Consumer;
import java.util.function.UnaryOperator;

/**
 * F4: an in-memory, versioned stand-in for the real save pipeline, with knobs to pause, fail, persist-then-fail
 * and rewrite a publish, and to simulate external writers, renames and deletes. Text is stored the way the real
 * save stores it (CRLF, trailing CRLF; {@code TextUtil.normalizePostData}), so callers must convert with
 * {@code CollabText.toLf}. Version numbers start at 1.
 *
 * <p>Both the threaded style ({@link #publish} blocks at the pause point) and the single-threaded simulator style
 * ({@link #publishAsync} returns a {@link PendingPublish}) go through the same entry path, {@link #enter}.
 */
public final class ScriptedPageWriter implements CollabPageWriter {

    /** An external write that bumped a version, delivered to {@link #onExternalChange}. */
    public record ExternalChange( String pageName, int version, String source ) {
    }

    /** Controls a publish paused at the pause point (after the request is accepted, before anything is stored). */
    public interface PauseHandle {
        /** Waits until a publish has reached the pause point; throws {@link IllegalStateException} on timeout. */
        void awaitEntered( Duration timeout );

        /** Lets the paused publish carry on and save. */
        void release();

        /** Lets the paused publish carry on by failing with {@code e}; nothing is stored. */
        void releaseWith( Exception e );

        boolean entered();
    }

    /** A publish begun with {@link #publishAsync}; the simulator completes it later. */
    public interface PendingPublish {
        /** True once the publish has finished (successfully or not). */
        boolean isDone();

        /** Releases any pause, performs the save and returns the outcome, or throws the publish's failure. */
        PublishOutcome complete() throws WikiException;

        /** Fails the publish with {@code e} (stores nothing), throwing {@code e}. */
        PublishOutcome completeWith( Exception e ) throws WikiException;
    }

    /** One stored version; {@code author} is the author key that wrote it, null when unknown (a seeded version). */
    private record Version( String body, Map< String, Object > meta, String author ) {
    }

    private final ReentrantLock lock = new ReentrantLock();
    private final Map< String, List< Version > > pages = new HashMap<>();
    private final List< Consumer< ExternalChange > > listeners = new CopyOnWriteArrayList<>();
    private UnaryOperator< String > rewrite = UnaryOperator.identity();
    private Handle armedPause;
    private Exception failNext;
    private Exception persistThenFailNext;
    /** The next save persists, then fails to read its text back, reporting the version ({@link #persistThenFailUnread}). */
    private boolean persistThenFailUnreadNext;
    /** The outcome of the last publish whose save returned normally (not thrown); {@link #takeLastReturned}. */
    private PublishOutcome lastReturned;
    private volatile Duration releaseTimeout = DEFAULT_RELEASE_TIMEOUT;
    private volatile boolean readsFail;

    /**
     * The message of the page-gone conflict: a publish with {@code expectedVersion > 0} to a page that does not exist
     * (renamed or deleted away) is refused, never a recreate, mirroring the save adapter (Phase 2 Task 4's
     * {@code VersionConflictException.pageGone}).
     */
    public static final String PAGE_GONE = VersionConflictException.PAGE_GONE;

    /** How long a paused {@link #publish} waits for {@link PauseHandle#release} before giving up. */
    public static final Duration DEFAULT_RELEASE_TIMEOUT = Duration.ofSeconds( 30 );

    // ---- setup / knobs -------------------------------------------------------------------------------------

    /** Overrides {@link #DEFAULT_RELEASE_TIMEOUT} (tests of the timeout itself). */
    void releaseTimeout( final Duration timeout ) {
        releaseTimeout = Objects.requireNonNull( timeout );
    }

    /** Null-tolerant unmodifiable copy (page meta can hold null values, which {@code Map.copyOf} rejects). */
    private static Map< String, Object > copyMeta( final Map< String, Object > meta ) {
        return Collections.unmodifiableMap( new LinkedHashMap<>( meta ) );
    }

    public void seed( final String pageName, final String body, final Map< String, Object > meta ) {
        lock.lock();
        try {
            final List< Version > v = new ArrayList<>();
            v.add( new Version( TextUtil.normalizePostData( body ), copyMeta( meta ), null ) );
            pages.put( pageName, v );
        } finally {
            lock.unlock();
        }
    }

    public PauseHandle pauseNextPublish() {
        final Handle h = new Handle();
        lock.lock();
        try {
            armedPause = h;
        } finally {
            lock.unlock();
        }
        return h;
    }

    public void failNextPublish( final Exception e ) {
        lock.lock();
        try {
            failNext = Objects.requireNonNull( e );
        } finally {
            lock.unlock();
        }
    }

    /**
     * The next save persists and then throws {@link PublishReadBackException} naming the version it wrote: the
     * production adapter's only way to fail after persisting (a failure in the post-save handling is reported as the
     * version written, a read-back failure as this exception). The caller learns the version, so
     * {@link #takeLastReturned} reports the outcome.
     */
    public void persistThenFailUnread() {
        lock.lock();
        try {
            persistThenFailUnreadNext = true;
        } finally {
            lock.unlock();
        }
    }

    public void persistThenFail( final Exception e ) {
        lock.lock();
        try {
            persistThenFailNext = Objects.requireNonNull( e );
        } finally {
            lock.unlock();
        }
    }

    /** Standing transform applied to every published body before it is normalised and stored (a save filter). */
    public void rewriteOnSave( final UnaryOperator< String > fn ) {
        lock.lock();
        try {
            rewrite = Objects.requireNonNull( fn );
        } finally {
            lock.unlock();
        }
    }

    /**
     * Read-fault hook: while on, {@link #latest} and {@link #version} throw {@link PageReadException}, the way the real
     * adapter answers when the page store cannot tell (never "absent", never an empty body).
     */
    public void failReads( final boolean on ) {
        readsFail = on;
    }

    private void checkReadable( final String pageName ) {
        if( readsFail ) {
            throw new PageReadException( "test: the page store cannot read " + pageName );
        }
    }

    public void onExternalChange( final Consumer< ExternalChange > listener ) {
        listeners.add( Objects.requireNonNull( listener ) );
    }

    // ---- external actors ----------------------------------------------------------------------------------

    /** A write by someone else through the normal pipeline: new version, listeners notified. */
    public int externalSave( final String pageName, final String body, final Map< String, Object > meta,
                             final String authorKey ) {
        final int version;
        lock.lock();
        try {
            final List< Version > v = pages.computeIfAbsent( pageName, k -> new ArrayList<>() );
            v.add( new Version( TextUtil.normalizePostData( body ), copyMeta( meta ), authorKey ) );
            version = v.size();
        } finally {
            lock.unlock();
        }
        final ExternalChange change = new ExternalChange( pageName, version, authorKey );
        for( final Consumer< ExternalChange > l : listeners ) {
            l.accept( change );
        }
        return version;
    }

    /**
     * Changes the latest version's stored body with NO new version and NO notification: the file-watcher shape (A1).
     * Only a content hash can reveal it.
     */
    public void overwriteWithoutVersion( final String pageName, final String body ) {
        lock.lock();
        try {
            final List< Version > v = pages.get( pageName );
            if( v == null || v.isEmpty() ) {
                throw new IllegalStateException( "no such page: " + pageName );
            }
            final Version last = v.get( v.size() - 1 );
            v.set( v.size() - 1, new Version( TextUtil.normalizePostData( body ), last.meta(), last.author() ) );
        } finally {
            lock.unlock();
        }
    }

    public void rename( final String from, final String to ) {
        lock.lock();
        try {
            final List< Version > v = pages.remove( from );
            if( v == null ) {
                throw new IllegalStateException( "no such page: " + from );
            }
            pages.put( to, v );
        } finally {
            lock.unlock();
        }
    }

    public void delete( final String pageName ) {
        lock.lock();
        try {
            pages.remove( pageName );
        } finally {
            lock.unlock();
        }
    }

    /** Stored (CRLF) body of every version, oldest first; empty for an unknown page. */
    public List< String > history( final String pageName ) {
        lock.lock();
        try {
            final List< Version > v = pages.get( pageName );
            if( v == null ) {
                return List.of();
            }
            final List< String > out = new ArrayList<>();
            for( final Version x : v ) {
                out.add( x.body() );
            }
            return out;
        } finally {
            lock.unlock();
        }
    }

    // ---- CollabPageWriter ---------------------------------------------------------------------------------

    @Override
    public Optional< PageState > latest( final String pageName ) {
        checkReadable( pageName );
        lock.lock();
        try {
            final List< Version > v = pages.get( pageName );
            return v == null || v.isEmpty() ? Optional.empty() : Optional.of( state( v, v.size() ) );
        } finally {
            lock.unlock();
        }
    }

    @Override
    public Optional< PageState > version( final String pageName, final int version ) {
        checkReadable( pageName );
        lock.lock();
        try {
            final List< Version > v = pages.get( pageName );
            return v == null || version < 1 || version > v.size() ? Optional.empty() : Optional.of( state( v, version ) );
        } finally {
            lock.unlock();
        }
    }

    private static PageState state( final List< Version > v, final int version ) {
        final Version x = v.get( version - 1 );
        return new PageState( version, x.body(), x.meta(), CollabText.contentHash( x.body() ), x.author() );
    }

    @Override
    public PublishOutcome publish( final PublishRequest request ) throws WikiException {
        final Handle pause = enter();
        if( pause != null ) {
            pause.awaitRelease( releaseTimeout );
        }
        return store( request, pause );
    }

    /** Simulator style: runs up to the pause point; if paused, returns a pending publish to complete later. */
    public PendingPublish publishAsync( final PublishRequest request ) {
        final Handle pause = enter();
        return new Pending( request, pause );
    }

    /** The one entry path: consumes the armed pause (if any) and marks it entered. */
    private Handle enter() {
        final Handle h;
        lock.lock();
        try {
            h = armedPause;
            armedPause = null;
        } finally {
            lock.unlock();
        }
        if( h != null ) {
            h.markEntered();
        }
        return h;
    }

    private PublishOutcome store( final PublishRequest request, final Handle pause ) throws WikiException {
        if( pause != null && pause.failure != null ) {
            throw (WikiException) asWiki( pause.failure );
        }
        final Exception persistFail;
        final PublishOutcome outcome;
        lock.lock();
        try {
            if( failNext != null ) {
                final Exception e = failNext;
                failNext = null;
                throw asWiki( e );
            }
            final List< Version > existing = pages.get( request.pageName() );
            final int current = existing == null ? 0 : existing.size();
            // A11: 0 means "the page must not exist"; a positive version on a missing page is page-gone (renamed or
            // deleted away under the publish), never a recreate
            if( request.expectedVersion() > 0 && current == 0 ) {
                throw VersionConflictException.pageGone( request.expectedVersion() );
            }
            final boolean conflict = request.expectedVersion() == 0
                ? current > 0
                : request.expectedVersion() != current;
            if( conflict ) {
                throw new VersionConflictException( request.pageName(), current, request.expectedVersion(), false );
            }
            final List< Version > v = existing == null
                ? pages.computeIfAbsent( request.pageName(), k -> new ArrayList<>() ) : existing;
            final String stored = TextUtil.normalizePostData( rewrite.apply( request.body() ) );
            if( current > 0 && v.get( current - 1 ).body().equals( stored )
                    && v.get( current - 1 ).meta().equals( request.meta() ) ) {
                lastReturned = new PublishOutcome( current, stored, request.meta(), true );
                return lastReturned;
            }
            v.add( new Version( stored, copyMeta( request.meta() ), request.authorKey() ) );
            outcome = new PublishOutcome( v.size(), stored, request.meta(), false );
            if( persistThenFailUnreadNext ) {
                persistThenFailUnreadNext = false;
                lastReturned = outcome;
                throw new PublishReadBackException( "injected: page '" + request.pageName() + "' version " + v.size()
                    + " was saved but could not be read back", v.size(), null );
            }
            persistFail = persistThenFailNext;
            persistThenFailNext = null;
        } finally {
            lock.unlock();
        }
        if( persistFail != null ) {
            throw asWiki( persistFail );
        }
        lock.lock();
        try {
            lastReturned = outcome;
        } finally {
            lock.unlock();
        }
        return outcome;
    }

    /**
     * The outcome the last publish's save returned to its caller (so the caller knows the version it wrote, or that it
     * stored nothing new), cleared by this call; null when no save returned since (none ran, or the save threw).
     */
    public PublishOutcome takeLastReturned() {
        lock.lock();
        try {
            final PublishOutcome o = lastReturned;
            lastReturned = null;
            return o;
        } finally {
            lock.unlock();
        }
    }

    private static WikiException asWiki( final Exception e ) {
        return e instanceof WikiException w ? w : new WikiException( e.getMessage(), e );
    }

    // ---- pause handle / pending publish -------------------------------------------------------------------

    private static final class Handle implements PauseHandle {
        private final ReentrantLock hl = new ReentrantLock();
        private final Condition changed = hl.newCondition();
        private boolean entered;
        private boolean released;
        private volatile Exception failure;

        void markEntered() {
            hl.lock();
            try {
                entered = true;
                changed.signalAll();
            } finally {
                hl.unlock();
            }
        }

        /** Bounded, so a test that forgets to release fails instead of hanging the build. */
        void awaitRelease( final Duration timeout ) {
            long nanos = timeout.toNanos();
            hl.lock();
            try {
                while( !released ) {
                    if( nanos <= 0 ) {
                        throw new IllegalStateException( "the paused publish was not released within " + timeout );
                    }
                    nanos = changed.awaitNanos( nanos );
                }
            } catch( final InterruptedException e ) {
                Thread.currentThread().interrupt();
                throw new IllegalStateException( "interrupted waiting for the paused publish to be released", e );
            } finally {
                hl.unlock();
            }
        }

        @Override
        public void awaitEntered( final Duration timeout ) {
            long nanos = timeout.toNanos();
            hl.lock();
            try {
                while( !entered ) {
                    if( nanos <= 0 ) {
                        throw new IllegalStateException( "no publish reached the pause point within " + timeout );
                    }
                    nanos = changed.awaitNanos( nanos );
                }
            } catch( final InterruptedException e ) {
                Thread.currentThread().interrupt();
                throw new IllegalStateException( "interrupted waiting for the pause point", e );
            } finally {
                hl.unlock();
            }
        }

        @Override
        public void release() {
            hl.lock();
            try {
                released = true;
                changed.signalAll();
            } finally {
                hl.unlock();
            }
        }

        @Override
        public void releaseWith( final Exception e ) {
            failure = Objects.requireNonNull( e );
            release();
        }

        @Override
        public boolean entered() {
            hl.lock();
            try {
                return entered;
            } finally {
                hl.unlock();
            }
        }
    }

    private final class Pending implements PendingPublish {
        private final PublishRequest request;
        private final Handle pause;
        private boolean done;
        private PublishOutcome outcome;
        private WikiException failure;

        Pending( final PublishRequest request, final Handle pause ) {
            this.request = request;
            this.pause = pause;
            if( pause == null ) {
                run();
            }
        }

        private void run() {
            try {
                outcome = store( request, pause );
            } catch( final WikiException e ) {
                failure = e;
            }
            done = true;
        }

        @Override
        public boolean isDone() {
            return done;
        }

        @Override
        public PublishOutcome complete() throws WikiException {
            if( !done ) {
                pause.release();
                run();
            }
            if( failure != null ) {
                throw failure;
            }
            return outcome;
        }

        @Override
        public PublishOutcome completeWith( final Exception e ) throws WikiException {
            if( !done ) {
                if( pause != null ) {
                    pause.releaseWith( e );
                }
                run();
            }
            if( failure != null ) {
                throw failure;
            }
            return outcome;
        }
    }
}
