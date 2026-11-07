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
import com.wikantik.collab.CollabStore;
import com.wikantik.collab.model.PageState;
import com.wikantik.collab.model.PublishOutcome;
import com.wikantik.collab.model.PublishRequest;
import com.wikantik.collab.model.SessionRecord;
import com.wikantik.collab.model.Snapshot;
import com.wikantik.collab.model.StoredUpdate;
import com.wikantik.collab.text.ChangeSet;
import com.wikantik.collab.text.CollabText;
import com.wikantik.collab.text.TextDiff;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;

/**
 * The Phase 0 stand-in for the real {@code CollabSession}: one page, the section 7.2 push, section 7.5 pull with
 * parking and section 7.6 ring (with A10's fall-through to the store), and a deliberately naive publish. There are no
 * external merges, meta ops, presence, frozen state or permission checks; those land in Phase 1/2.
 *
 * <p>Decisions: an unknown (never joined or left) participant gets 409 from push, pull and publish (stands for the
 * {@code rejoin} answer of 7.2). Publish is naive (requires {@code version == head}, no pending-publish
 * bookkeeping); if the writer's filters rewrote the text, {@code doc} and the saved base differ, so the stub appends a
 * server update {@code TextDiff.changeSet(doc, base)} to converge everyone; Phase 1 does the full 7.3 mapping. A
 * writer exception maps to 409 for a version conflict and 422 otherwise. A store failure during publish leaves the
 * page saved and the session behind (503); the real session's pending-publish rule covers that, the stub does not.</p>
 */
public final class StubCollabAuthority implements SimServer {

    private static final Logger LOG = LogManager.getLogger( StubCollabAuthority.class );
    /** The long-poll timeout of section 7.5. */
    static final Duration POLL_TIMEOUT = Duration.ofSeconds( 25 );
    static final String SERVER_CLIENT = "server";

    private record Participant( String authorKey, String clientId ) {
    }

    private record Waiter( int since, Instant deadline ) {
    }

    private final String pageName;
    private final CollabStore store;
    private final CollabPageWriter writer;
    private final Clock clock;
    private final long maxPageBytes;
    private final int ringSize;
    private final UUID id = UUID.randomUUID();

    private String doc;
    private int head;
    private String baseBody;
    private int basePageVersion;
    private Map< String, Object > baseMeta;
    private ChangeSet local;
    private final Deque< StoredUpdate > ring = new ArrayDeque<>();

    private final Map< String, Participant > participants = new LinkedHashMap<>();
    private final Map< String, Waiter > waiters = new LinkedHashMap<>();
    private final List< Wakeup > wakeups = new ArrayList<>();
    private int nextParticipant = 1;

    public StubCollabAuthority( final String pageName, final CollabStore store, final CollabPageWriter writer,
                                final Clock clock, final long maxPageBytes, final int ringSize ) {
        this.pageName = Objects.requireNonNull( pageName );
        this.store = Objects.requireNonNull( store );
        this.writer = Objects.requireNonNull( writer );
        this.clock = Objects.requireNonNull( clock );
        this.maxPageBytes = maxPageBytes;
        if( ringSize < 1 ) {
            throw new IllegalArgumentException( "ringSize must be >= 1: " + ringSize );
        }
        this.ringSize = ringSize;
        final PageState latest = writer.latest( pageName ).orElse( null );
        doc = latest == null ? "" : CollabText.toLf( latest.body() );
        baseBody = doc;
        basePageVersion = latest == null ? 0 : latest.version();
        baseMeta = latest == null ? Map.of() : latest.meta();
        local = ChangeSet.empty( doc.length() );
        final Instant now = clock.instant();
        store.createSession( new SessionRecord( id, pageName, SessionRecord.ACTIVE, basePageVersion, baseBody, baseMeta, 0,
            0, false, null, null, "stub", now, now ) );
        store.writeSnapshot( id, new Snapshot( 0, 0, doc, baseMeta, local ) );
    }

    @Override
    public synchronized Joined join( final String authorKey ) {
        final int n = nextParticipant++;
        final Participant p = new Participant( Objects.requireNonNull( authorKey ), "c" + n );
        participants.put( "p" + n, p );
        return new Joined( "p" + n, p.clientId(), head, doc );
    }

    @Override
    public synchronized PushResult push( final String participantId, final int version, final List< Update > updates ) {
        final Participant p = participants.get( participantId );
        if( p == null ) {
            return new PushResult( 409, head );
        }
        if( version != head ) {
            return new PushResult( 409, head );
        }
        // validate the whole chain before touching anything
        String cur = doc;
        final List< StoredUpdate > accepted = new ArrayList<>();
        for( final Update u : updates ) {
            if( !p.clientId().equals( u.clientId() ) ) {
                LOG.warn( "push from {} names clientId {} (bound to {}): refused", participantId, u.clientId(), p.clientId() );
                return new PushResult( 400, head );
            }
            final ChangeSet cs = u.changes();
            if( cs.length() != cur.length() ) {
                LOG.warn( "push from {}: change set length {} does not match document length {}", participantId, cs.length(),
                    cur.length() );
                return new PushResult( 400, head );
            }
            final String next = cs.apply( cur );
            if( CollabText.splitsSurrogatePair( cur, cs ) || CollabText.hasLoneSurrogate( next ) ) {
                LOG.warn( "push from {}: update would leave a lone surrogate", participantId );
                return new PushResult( 400, head );
            }
            if( utf8( next ) > Math.max( maxPageBytes, utf8( cur ) ) ) {
                return new PushResult( 413, head );
            }
            accepted.add( new StoredUpdate( head + accepted.size() + 1, "text", p.clientId(), p.authorKey(), cs ) );
            cur = next;
        }
        if( accepted.isEmpty() ) {
            return new PushResult( 200, head );
        }
        try {
            store.appendUpdates( id, accepted );
        } catch( final RuntimeException e ) {
            LOG.warn( "push from {}: store append failed, nothing applied", participantId, e );
            return new PushResult( 503, head );
        }
        ChangeSet l = local;
        for( final StoredUpdate u : accepted ) {
            l = l.compose( u.changes() );
            remember( u );
        }
        local = l;
        doc = cur;
        head += accepted.size();
        wakeWaiters();
        return new PushResult( 200, head );
    }

    private void remember( final StoredUpdate u ) {
        ring.addLast( u );
        while( ring.size() > ringSize ) {
            ring.removeFirst();
        }
    }

    @Override
    public synchronized PullResult pull( final String participantId, final int since ) {
        if( !participants.containsKey( participantId ) ) {
            return new PullResult( 409, List.of(), head, false );
        }
        if( since < 0 || since > head ) {
            return new PullResult( 400, List.of(), head, false );
        }
        if( since == head ) {
            waiters.put( participantId, new Waiter( since, clock.instant().plus( POLL_TIMEOUT ) ) );
            return new PullResult( 200, List.of(), head, true );
        }
        return slice( since );
    }

    private PullResult slice( final int since ) {
        final List< StoredUpdate > source;
        if( !ring.isEmpty() && ring.peekFirst().version() <= since + 1 ) {
            source = ring.stream().filter( u -> u.version() > since ).toList();
        } else {
            source = store.updatesSince( id, since );
            if( source.isEmpty() || source.get( 0 ).version() != since + 1 || source.get( source.size() - 1 ).version() != head ) {
                return new PullResult( 410, List.of(), head, false );
            }
        }
        final List< Update > out = new ArrayList<>();
        for( final StoredUpdate u : source ) {
            out.add( new Update( u.clientId(), u.changes() ) );
        }
        return new PullResult( 200, out, head, false );
    }

    private void wakeWaiters() {
        for( final Map.Entry< String, Waiter > e : new ArrayList<>( waiters.entrySet() ) ) {
            wakeups.add( new Wakeup( e.getKey(), slice( e.getValue().since() ) ) );
        }
        waiters.clear();
    }

    @Override
    public synchronized void tick() {
        final Instant now = clock.instant();
        for( final Map.Entry< String, Waiter > e : new ArrayList<>( waiters.entrySet() ) ) {
            if( !now.isBefore( e.getValue().deadline() ) ) {
                waiters.remove( e.getKey() );
                wakeups.add( new Wakeup( e.getKey(), new PullResult( 200, List.of(), head, false ) ) );
            }
        }
    }

    @Override
    public synchronized void leave( final String participantId ) {
        participants.remove( participantId );
        if( waiters.remove( participantId ) != null ) {
            wakeups.add( new Wakeup( participantId, new PullResult( 200, List.of(), head, false ) ) );
        }
    }

    @Override
    public synchronized List< Wakeup > drainWakeups() {
        final List< Wakeup > out = List.copyOf( wakeups );
        wakeups.clear();
        return out;
    }

    @Override
    public synchronized PublishResult publish( final String participantId, final int version, final String note ) {
        final Participant p = participants.get( participantId );
        if( p == null || version != head ) {
            return new PublishResult( 409, basePageVersion );
        }
        if( local.empty() ) {
            return new PublishResult( 200, basePageVersion );
        }
        if( doc.isEmpty() ) {
            return new PublishResult( 400, basePageVersion );
        }
        final PublishOutcome out;
        try {
            out = writer.publish( new PublishRequest( pageName, doc, baseMeta, basePageVersion, p.authorKey(),
                note == null ? "" : note ) );
        } catch( final VersionConflictException e ) {
            LOG.warn( "publish of {} refused: {}", pageName, e.getMessage() );
            return new PublishResult( 409, basePageVersion );
        } catch( final WikiException e ) {
            LOG.warn( "publish of {} refused: {}", pageName, e.getMessage() );
            return new PublishResult( 422, basePageVersion );
        }
        final String newBase = CollabText.toLf( out.savedBody() );
        final List< StoredUpdate > extra = new ArrayList<>();
        if( !newBase.equals( doc ) ) {
            extra.add( new StoredUpdate( head + 1, "server", SERVER_CLIENT, null, TextDiff.changeSet( doc, newBase ) ) );
        }
        final int newHead = head + extra.size();
        try {
            if( !extra.isEmpty() ) {
                store.appendUpdates( id, extra );
            }
            store.writeSnapshot( id, new Snapshot( newHead, 0, newBase, out.savedMeta(), ChangeSet.empty( newBase.length() ) ) );
            final SessionRecord rec = store.session( id ).orElseThrow();
            store.updateSession( rec.withBase( out.version(), newBase, out.savedMeta() ) );
        } catch( final RuntimeException e ) {
            LOG.warn( "publish of {} saved page version {} but the store failed; session not advanced", pageName,
                out.version(), e );
            return new PublishResult( 503, basePageVersion );
        }
        extra.forEach( this::remember );
        head = newHead;
        doc = newBase;
        baseBody = newBase;
        basePageVersion = out.version();
        baseMeta = out.savedMeta();
        local = ChangeSet.empty( newBase.length() );
        if( !extra.isEmpty() ) {
            wakeWaiters();
        }
        return new PublishResult( 200, out.version() );
    }

    @Override
    public synchronized InvariantView view() {
        return new InvariantView( id, doc, head, baseBody, local, basePageVersion, List.copyOf( ring ),
            utf8( doc ) > maxPageBytes, maxPageBytes, store, writer, pageName, false, false );
    }

    private static long utf8( final String s ) {
        return s.getBytes( StandardCharsets.UTF_8 ).length;
    }
}
