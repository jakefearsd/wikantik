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

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.wikantik.collab.MetaJson;
import com.wikantik.collab.model.MetaEdit;
import com.wikantik.collab.model.MetaOp;
import com.wikantik.collab.model.Selection;
import com.wikantik.collab.testing.SimServer.Joined;
import com.wikantik.collab.testing.SimServer.PushResult;
import com.wikantik.collab.testing.SimServer.Update;
import com.wikantik.collab.text.ChangeSet;
import com.wikantik.collab.text.ChangeSetJson;
import com.wikantik.collab.text.TextDiff;

import java.util.ArrayList;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * A Java model of the client half of {@code @codemirror/collab} 6.1.1 (F2). Same state ({@code version} + a list of
 * unconfirmed updates) and the same rebase rule as the library's {@code receiveUpdates}; Task 13 replays
 * {@link #trace()} through the real library to keep this model honest.
 *
 * <p>Mapping: {@code collab()}'s field update -> {@link #localEdit}; {@code receiveUpdates} -> {@link #receive};
 * {@code sendableUpdates} -> {@link #sendable}; {@code getSyncedVersion} -> {@link #version};
 * {@code getClientID} -> {@link #clientId}. Effects are not modelled (the wiki shares none).
 *
 * <p><b>Phase 2.</b> A selection ({@link #select}, A12) in the client's own document, mapped through its local edits and
 * the remote changes it receives, and sent with every push and pull as {@link #selection()} (its synced version and its
 * count of unconfirmed updates). A metadata model ({@link MetaState}, the Java mirror of {@code collabMeta.js}). A
 * {@link #frozen()} flag (the page was deleted: no local edits, no pushes) and the event cursor. A rename changes nothing
 * here: the client's requests name its session and participant, never the page, so it keeps going by session id. None
 * of them is in the trace: the F3 corpus is the stub's, which has none of them.</p>
 */
public final class SimClient {

    /**
     * One protocol-relevant event, in the order they happened. Types:
     * <ul>
     * <li>{@code join}: constructor; {@code participantId, clientId, version, doc}. Replay seeds the JS client from it.</li>
     * <li>{@code local}: a local edit; {@code changes} (ChangeSet JSON).</li>
     * <li>{@code receive}: a pull batch; {@code fromVersion} and {@code updates} ({@code clientID}, {@code changes}).
     *     Skip rule (E11), which the JS replay must apply identically: when fromVersion is below the client version the
     *     first {@code version - fromVersion} updates are skipped; fromVersion above the version is a gap and nothing
     *     is applied.</li>
     * <li>{@code gap}: the receive just before it was a gap and was ignored; {@code fromVersion, version}.</li>
     * <li>{@code push}: a push answer; informational only, not replayed.</li>
     * <li>{@code sendable}: snapshot after each step, {@code version}, the {@code doc} and the {@code sendable} updates; the replay's expected value.</li>
     * <li>{@code rejoin}: the same clientID was reissued to a new participant ({@link #reconnect}); {@code participantId,
     *     clientId}; the state is unchanged.</li>
     * <li>{@code resync}: rejoin; {@code version, clientId, participantId, doc, unsynced} (and {@code rebased} when the
 *     unsent edits were kept, see {@link #rejoin}: the {@code local} entries that follow re-apply them). Later updates
 *     carry the new clientId.</li>
     * </ul>
     */
    public record TraceEntry( String type, JsonObject data ) {
        public JsonObject toJson() {
            final JsonObject o = new JsonObject();
            o.addProperty( "type", type );
            data.entrySet().forEach( e -> o.add( e.getKey(), e.getValue() ) );
            return o;
        }
    }

    private final String name;
    private String clientId;
    private String participantId;
    private String doc;
    /** The document at {@link #version}: every server update received applied in order, without the unconfirmed ones. */
    private String synced;
    private int version;
    private List< Update > unconfirmed = new ArrayList<>();
    private String recoveryText = "";
    private final List< TraceEntry > trace = new ArrayList<>();
    /** The selection in {@link #doc}; -1 while there is none. */
    private int selAnchor = -1;
    private int selHead = -1;
    private MetaState meta;
    private long eventSeq;
    private boolean frozen;

    public SimClient( final String name, final Joined joined ) {
        this.name = name;
        adopt( joined );
        this.meta = new MetaState( joined.meta(), joined.metaSeq() );
        final JsonObject d = new JsonObject();
        d.addProperty( "participantId", joined.participantId() );
        d.addProperty( "clientId", joined.clientId() );
        d.addProperty( "version", joined.version() );
        d.addProperty( "doc", joined.doc() );
        trace.add( new TraceEntry( "join", d ) );
    }

    private void adopt( final Joined joined ) {
        this.participantId = joined.participantId();
        this.clientId = joined.clientId();
        this.version = joined.version();
        this.doc = joined.doc();
        this.synced = joined.doc();
        this.unconfirmed = new ArrayList<>();
        this.eventSeq = joined.eventSeq();
        this.frozen = joined.frozen();
        clampSelection();
    }

    private void clampSelection() {
        if( selAnchor >= 0 ) {
            selAnchor = Math.min( selAnchor, doc.length() );
            selHead = Math.min( selHead, doc.length() );
        }
    }

    // ---- Phase 2: selection, metadata, frozen, events ------------------------------------------------------------

    /** Moves the selection (clamped to the document). */
    public void select( final int anchor, final int head ) {
        selAnchor = Math.max( 0, Math.min( anchor, doc.length() ) );
        selHead = Math.max( 0, Math.min( head, doc.length() ) );
    }

    /** The selection as A12 sends it (positions in {@link #doc}, its synced version, its unconfirmed count), or null. */
    public Selection selection() {
        return selAnchor < 0 ? null : new Selection( selAnchor, selHead, version, unconfirmed.size() );
    }

    /** The metadata model. */
    public MetaState meta() {
        return meta;
    }

    /** The event sequence this client has seen (a pull asks for the events after it). */
    public long eventSeq() {
        return eventSeq;
    }

    public void eventsSeen( final long seq ) {
        eventSeq = Math.max( eventSeq, seq );
    }

    /** True once the client was told the page is deleted: it makes no local edits and sends no pushes. */
    public boolean frozen() {
        return frozen;
    }

    public void freeze() {
        frozen = true;
    }

    private void mapSelection( final ChangeSet cs, final int assoc ) {
        if( selAnchor >= 0 ) {
            selAnchor = cs.mapPos( selAnchor, assoc );
            selHead = cs.mapPos( selHead, assoc );
        }
    }

    public String name() {
        return name;
    }

    public String clientId() {
        return clientId;
    }

    public String participantId() {
        return participantId;
    }

    public String doc() {
        return doc;
    }

    public int version() {
        return version;
    }

    /** The confirmed document (at {@link #version()}, without the unconfirmed local edits). */
    public String syncedDoc() {
        return synced;
    }

    public String recoveryText() {
        return recoveryText;
    }

    /** Unconfirmed updates in send order (a copy). */
    public List< Update > sendable() {
        return List.copyOf( unconfirmed );
    }

    /** A local transaction: applies to the document and joins the unconfirmed list. Empty edits are dropped, as the library does. */
    public void localEdit( final ChangeSet cs ) {
        final JsonObject d = new JsonObject();
        d.add( "changes", ChangeSetJson.toJson( cs ) );
        trace.add( new TraceEntry( "local", d ) );
        if ( !cs.empty() ) {
            doc = cs.apply( doc );
            unconfirmed.add( new Update( clientId, cs ) );
            mapSelection( cs, 1 );
        }
        snapshot();
    }

    /**
     * {@code receiveUpdates}. {@code fromVersion} is the version the batch starts after. Updates at or below
     * {@link #version()} are dropped (E11); a batch starting beyond it is a gap and is not applied.
     */
    public void receive( final List< Update > updates, final int fromVersion ) {
        final JsonObject d = new JsonObject();
        d.addProperty( "fromVersion", fromVersion );
        d.add( "updates", updatesJson( updates ) );
        trace.add( new TraceEntry( "receive", d ) );
        if ( fromVersion > version ) {
            final JsonObject g = new JsonObject();
            g.addProperty( "fromVersion", fromVersion );
            g.addProperty( "version", version );
            trace.add( new TraceEntry( "gap", g ) );
            snapshot();
            return;
        }
        final int skip = version - fromVersion;
        if ( skip < updates.size() ) {
            apply( updates.subList( skip, updates.size() ) );
        }
        snapshot();
    }

    private void apply( final List< Update > updates ) {
        version += updates.size();
        for ( final Update u : updates ) {
            synced = u.changes().apply( synced );
        }
        ChangeSet changes = null;
        int own = 0;
        for ( final Update update : updates ) {
            final Update ours = own < unconfirmed.size() ? unconfirmed.get( own ) : null;
            if ( ours != null && ours.clientId().equals( update.clientId() ) ) {
                if ( changes != null ) {
                    changes = changes.map( ours.changes(), true );
                }
                own++;
            } else {
                changes = changes != null ? changes.compose( update.changes() ) : update.changes();
            }
        }
        if ( own > 0 ) {
            unconfirmed = new ArrayList<>( unconfirmed.subList( own, unconfirmed.size() ) );
        }
        if ( changes != null && !unconfirmed.isEmpty() ) {
            final List< Update > rebased = new ArrayList<>();
            for ( final Update u : unconfirmed ) {
                final ChangeSet mapped = u.changes().map( changes );
                changes = changes.map( u.changes(), true );
                rebased.add( new Update( clientId, mapped ) );
            }
            unconfirmed = rebased;
        }
        if ( changes != null ) {
            doc = changes.apply( doc );
            mapSelection( changes, -1 );
        }
    }

    /**
     * The push answer. Acceptance is confirmed only by the pull that carries the client's own update back (E9), so
     * nothing changes here beyond the trace.
     */
    public void onPushResult( final PushResult r ) {
        final JsonObject d = new JsonObject();
        d.addProperty( "status", r.status() );
        d.addProperty( "version", r.version() );
        trace.add( new TraceEntry( "push", d ) );
    }

    /**
     * A10: the participant was lost; rejoin from fresh state. Unsynced text goes to {@link #recoveryText()} and is
     * never re-applied. Every resync replaces the recovery text (empty when nothing is unsynced).
     */
    public void resync( final Joined fresh ) {
        recoveryText = unconfirmed.isEmpty() ? "" : doc;
        final JsonObject d = new JsonObject();
        d.addProperty( "version", fresh.version() );
        d.addProperty( "clientId", fresh.clientId() );
        d.addProperty( "participantId", fresh.participantId() );
        d.addProperty( "doc", fresh.doc() );
        d.addProperty( "unsynced", unconfirmed.size() );
        trace.add( new TraceEntry( "resync", d ) );
        adopt( fresh );
        // ruling Q4: carried meta edits go to the recovery panel, never resent
        meta.adopt( fresh.meta(), fresh.metaSeq(), false );
        snapshot();
    }

    /**
     * Section 7.2 "expired participants": the server dropped this participant (409 {@code rejoin}) and the client joined
     * again under a fresh participant and client id. The unconfirmed edits are rebased onto the fresh document (mapped
     * through {@code TextDiff.changeSet(syncedDoc, fresh.doc)}, the same rebase loop as {@code receiveUpdates}) and kept,
     * now under the new client id; nothing goes to the recovery panel.
     *
     * <p><b>Only sound when none of the unconfirmed edits was ever sent</b>: a sent one may have been accepted with its
     * answer lost, and the fresh document would then already hold it. The caller decides (it knows what it sent) and uses
     * {@link #resync} otherwise (A10).</p>
     *
     * <p>Trace: a {@code resync} entry ({@code unsynced} 0, {@code rebased} n) that re-seeds the replay at the fresh
     * state, followed by one {@code local} entry per rebased edit.</p>
     */
    public void rejoin( final Joined fresh ) {
        ChangeSet changes = TextDiff.changeSet( synced, fresh.doc() );
        final List< ChangeSet > rebased = new ArrayList<>();
        for ( final Update u : unconfirmed ) {
            rebased.add( u.changes().map( changes ) );
            changes = changes.map( u.changes(), true );
        }
        final JsonObject d = new JsonObject();
        d.addProperty( "version", fresh.version() );
        d.addProperty( "clientId", fresh.clientId() );
        d.addProperty( "participantId", fresh.participantId() );
        d.addProperty( "doc", fresh.doc() );
        d.addProperty( "unsynced", 0 );
        d.addProperty( "rebased", rebased.size() );
        trace.add( new TraceEntry( "resync", d ) );
        adopt( fresh );
        // like the text: only what was never sent is kept (an edit in flight may already be in the fresh metadata)
        meta.adopt( fresh.meta(), fresh.metaSeq(), true );
        snapshot();
        rebased.forEach( this::localEdit );
    }

    /**
     * Section 7.2 "same clientID": the server dropped this participant but reissued the client's clientID to the new
     * one, so nothing about the collab state changes: the client keeps its version and unconfirmed updates and pulls on
     * from its own version, where updates of its own that were accepted (an answer lost, E9) are recognised by clientID.
     * Trace: a {@code rejoin} entry ({@code participantId}, {@code clientId}) the replay checks against the library's
     * clientID and otherwise ignores.
     *
     * @throws IllegalArgumentException when the fresh participant carries another clientID
     */
    public void reconnect( final Joined fresh ) {
        if( !clientId.equals( fresh.clientId() ) ) {
            throw new IllegalArgumentException( "reconnect needs the same clientID: " + clientId + " vs " + fresh.clientId() );
        }
        participantId = fresh.participantId();
        eventSeq = fresh.eventSeq();
        frozen = fresh.frozen();
        final JsonObject d = new JsonObject();
        d.addProperty( "participantId", fresh.participantId() );
        d.addProperty( "clientId", fresh.clientId() );
        trace.add( new TraceEntry( "rejoin", d ) );
        snapshot();
    }

    public List< TraceEntry > traceEntries() {
        return List.copyOf( trace );
    }

    /** Each entry as compact JSON. */
    public List< String > trace() {
        return trace.stream().map( t -> t.toJson().toString() ).toList();
    }

    private void snapshot() {
        final JsonObject d = new JsonObject();
        d.addProperty( "version", version );
        d.addProperty( "doc", doc );
        d.add( "sendable", updatesJson( unconfirmed ) );
        trace.add( new TraceEntry( "sendable", d ) );
    }

    /**
     * The Java mirror of {@code createMetaState} in {@code wikantik-frontend/src/collab/collabMeta.js} (A14), held to it by
     * {@code CollabMetaCasesTest}, which replays the shared {@code collab-meta-cases.json}. The server's metadata plus the
     * edits it has not confirmed, replayed on top for display. A sent edit leaves the pending list only when a pull has
     * delivered {@code metaSeq >= its acked metaSeq}, so a pending add cannot flicker away between the push ack and the
     * next pull. Pulled ops are resolved {@code {field, value}} assignments. The rules are the JS ones, deliberately: a
     * REMOVE drops the first equal item (the server drops every one, ruling Q6; with set semantics there is at most one),
     * a scalar string is a one-item list, anything else not a list is an empty one.
     *
     * <p>Each {@link Entry} carries the wire {@link Entry#edit} and the {@link Entry#intent} it was made as; they are the
     * same edit, which the scheduler's invariant 9 ledger relies on only through the intent (so a mutant that sends
     * something other than what was meant is caught).</p>
     */
    public static final class MetaState {

        /** Where an edit is: not yet sent, on a push without an answer, or acked and waiting for a pull to reach it. */
        public enum State { UNSENT, INFLIGHT, ACKED }

        /** One pending edit. */
        public static final class Entry {
            private final MetaEdit edit;
            private final MetaEdit intent;
            private State state = State.UNSENT;
            private long ackedSeq = -1;

            Entry( final MetaEdit intent ) {
                this.intent = intent;
                this.edit = intent;
            }

            public MetaEdit edit() {
                return edit;
            }

            public MetaEdit intent() {
                return intent;
            }

            public State state() {
                return state;
            }
        }

        private Map< String, Object > server;
        private long serverSeq;
        private List< Entry > entries = new ArrayList<>();

        public MetaState( final Map< String, Object > meta, final long metaSeq ) {
            this.server = new LinkedHashMap<>( MetaJson.normalise( meta == null ? Map.of() : meta ) );
            this.serverSeq = metaSeq;
        }

        /** A (re)join's metadata replaces the server's; the pending edits go unless {@code keepUnsent} keeps the unsent. */
        void adopt( final Map< String, Object > meta, final long metaSeq, final boolean keepUnsent ) {
            server = new LinkedHashMap<>( MetaJson.normalise( meta == null ? Map.of() : meta ) );
            serverSeq = metaSeq;
            entries = keepUnsent ? new ArrayList<>( entries.stream().filter( e -> e.state == State.UNSENT ).toList() )
                : new ArrayList<>();
        }

        /** Replaces a field's value (null removes it); an unsent SET of the same field is coalesced into this one. */
        public void edit( final String field, final Object value ) {
            final Entry e = new Entry( MetaEdit.set( field, value == null ? null : MetaJson.normaliseValue( value ) ) );
            for( int i = 0; i < entries.size(); i++ ) {
                final Entry x = entries.get( i );
                if( x.state == State.UNSENT && x.edit.field().equals( field ) && x.edit.kind() == MetaEdit.Kind.SET ) {
                    entries.set( i, e );
                    return;
                }
            }
            entries.add( e );
        }

        public void add( final String field, final String item ) {
            entries.add( new Entry( MetaEdit.add( field, item ) ) );
        }

        public void remove( final String field, final String item ) {
            entries.add( new Entry( MetaEdit.remove( field, item ) ) );
        }

        /** The edits for the next push, marked in flight; give the same entries back to {@link #acked} or {@link #failed}. */
        public List< Entry > takeSendable() {
            final List< Entry > out = new ArrayList<>();
            for( final Entry e : entries ) {
                if( e.state == State.UNSENT ) {
                    e.state = State.INFLIGHT;
                    out.add( e );
                }
            }
            return out;
        }

        /** True when there is something to send. */
        public boolean hasUnsent() {
            return entries.stream().anyMatch( e -> e.state == State.UNSENT );
        }

        /** The push failed: these in-flight edits (null: every one) go out again; edits are idempotent. */
        public void failed( final List< Entry > edits ) {
            for( final Entry e : entries ) {
                if( e.state == State.INFLIGHT && ( edits == null || edits.contains( e ) ) ) {
                    e.state = State.UNSENT;
                }
            }
        }

        /** The push was accepted at {@code seq} (null: the seq already reached, so they retire at once). */
        public void acked( final List< Entry > edits, final Long seq ) {
            final long at = seq == null ? serverSeq : seq;
            for( final Entry e : entries ) {
                if( edits.contains( e ) && e.state != State.UNSENT ) {
                    e.state = State.ACKED;
                    e.ackedSeq = at;
                }
            }
            retire();
        }

        /** A pull's resolved ops, in server order, and the {@code metaSeq} it reached (null: none given). */
        public void applyRemote( final List< MetaOp > ops, final Long seq ) {
            for( final MetaOp op : ops ) {
                apply( server, MetaEdit.set( op.field(), op.value() ) );
            }
            if( seq != null ) {
                serverSeq = Math.max( serverSeq, seq );
            }
            retire();
        }

        private void retire() {
            entries.removeIf( e -> e.state == State.ACKED && e.ackedSeq <= serverSeq );
        }

        /** The display metadata: the server's, with every unconfirmed edit replayed on top. */
        public Map< String, Object > view() {
            final Map< String, Object > out = new LinkedHashMap<>( MetaJson.normalise( server ) );
            entries.forEach( e -> apply( out, e.edit ) );
            return out;
        }

        /** The server's metadata as this client has pulled it. */
        public Map< String, Object > server() {
            return new LinkedHashMap<>( server );
        }

        public List< MetaEdit > pending() {
            return entries.stream().map( e -> e.edit ).toList();
        }

        /** The edits the server has not confirmed (unsent or in flight). */
        public List< MetaEdit > unconfirmed() {
            return entries.stream().filter( e -> e.state != State.ACKED ).map( e -> e.edit ).toList();
        }

        /** Forgets every local edit (eviction for lost edit rights, A13). */
        public void discardPending() {
            entries = new ArrayList<>();
        }

        public long serverSeq() {
            return serverSeq;
        }

        /** {@code applyEdit} of collabMeta.js: one edit applied in place, as the server resolves it. */
        private static void apply( final Map< String, Object > meta, final MetaEdit e ) {
            final String field = e.field();
            if( e.kind() == MetaEdit.Kind.SET ) {
                if( e.value() == null ) {
                    meta.remove( field );
                } else {
                    meta.put( field, MetaJson.normaliseValue( e.value() ) );
                }
                return;
            }
            final List< Object > list = toList( meta.get( field ) );
            if( e.kind() == MetaEdit.Kind.ADD ) {
                if( !list.contains( e.value() ) ) {
                    list.add( e.value() );
                }
            } else {
                final Iterator< Object > it = list.iterator();
                while( it.hasNext() ) {
                    if( Objects.equals( it.next(), e.value() ) ) {
                        it.remove();
                        break;
                    }
                }
            }
            meta.put( field, list );
        }

        private static List< Object > toList( final Object v ) {
            if( v instanceof List< ? > l ) {
                return new ArrayList<>( l );
            }
            final List< Object > out = new ArrayList<>();
            if( v instanceof String s && !s.isEmpty() ) {
                out.add( s );
            }
            return out;
        }
    }


    private static JsonArray updatesJson( final List< Update > updates ) {
        final JsonArray arr = new JsonArray();
        for ( final Update u : updates ) {
            final JsonObject o = new JsonObject();
            o.addProperty( "clientID", u.clientId() );
            o.add( "changes", ChangeSetJson.toJson( u.changes() ) );
            arr.add( o );
        }
        return arr;
    }
}
