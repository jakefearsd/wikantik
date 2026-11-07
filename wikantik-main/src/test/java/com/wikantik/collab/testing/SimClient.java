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
import com.wikantik.collab.testing.SimServer.Joined;
import com.wikantik.collab.testing.SimServer.PushResult;
import com.wikantik.collab.testing.SimServer.Update;
import com.wikantik.collab.text.ChangeSet;
import com.wikantik.collab.text.ChangeSetJson;

import java.util.ArrayList;
import java.util.List;

/**
 * A Java model of the client half of {@code @codemirror/collab} 6.1.1 (F2). Same state ({@code version} + a list of
 * unconfirmed updates) and the same rebase rule as the library's {@code receiveUpdates}; Task 13 replays
 * {@link #trace()} through the real library to keep this model honest.
 *
 * <p>Mapping: {@code collab()}'s field update -> {@link #localEdit}; {@code receiveUpdates} -> {@link #receive};
 * {@code sendableUpdates} -> {@link #sendable}; {@code getSyncedVersion} -> {@link #version};
 * {@code getClientID} -> {@link #clientId}. Effects are not modelled (the wiki shares none).
 */
public final class SimClient {

    /** One protocol-relevant event. {@code type} is local, receive, gap, push, resync. */
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
    private int version;
    private List< Update > unconfirmed = new ArrayList<>();
    private String recoveryText = "";
    private final List< TraceEntry > trace = new ArrayList<>();

    public SimClient( final String name, final Joined joined ) {
        this.name = name;
        adopt( joined );
    }

    private void adopt( final Joined joined ) {
        this.participantId = joined.participantId();
        this.clientId = joined.clientId();
        this.version = joined.version();
        this.doc = joined.doc();
        this.unconfirmed = new ArrayList<>();
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
     * never re-applied. A later resync with nothing unsynced keeps the earlier recovery text.
     */
    public void resync( final Joined fresh ) {
        if ( !unconfirmed.isEmpty() ) {
            recoveryText = doc;
        }
        final JsonObject d = new JsonObject();
        d.addProperty( "version", fresh.version() );
        d.addProperty( "unsynced", unconfirmed.size() );
        trace.add( new TraceEntry( "resync", d ) );
        adopt( fresh );
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
        d.add( "sendable", updatesJson( unconfirmed ) );
        trace.add( new TraceEntry( "sendable", d ) );
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
