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
package com.wikantik.collab;

import com.wikantik.collab.model.JoinRequest;
import com.wikantik.collab.model.JoinResponse;
import com.wikantik.collab.model.PresenceEntry;
import com.wikantik.collab.model.Selection;

import java.time.Duration;
import java.time.Instant;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * The participants of one session (section 7.1) and the ids it issues. Participant ids are {@code <tag>p<n>}; a new
 * clientID is {@code <tag>c<n>}, but a join gets its prior clientID back when a participant of the same author held it
 * and left within the client retention (the idle-eviction period, A13) and no live participant holds it (section
 * 7.2 "same clientID"). The tag is unique per session load, so ids never repeat across restarts. Display names come from
 * {@link CollabIdentity} (the join's name only when it has none, a neutral {@code "Editor <n>"} when neither has one,
 * never the login); colours from a fixed palette by join order within the
 * session, never from the login (section 8.3). Guarded by the session lock.
 *
 * <p><b>Eviction tombstones.</b> A participant removed by the server for a reason ({@code lost-edit},
 * {@code signed-out}, {@code account-deleted}) leaves a tombstone with its author and reason, so its later requests
 * answer 403 {@code evicted} with that reason rather than 409 {@code rejoin}. Tombstones are kept for the retention
 * (the presence timeout) and at most {@value #MAX_TOMBSTONES} of them; after that the id is simply unknown.</p>
 */
final class Roster {

    private static final String[] COLORS = { "#1b6ec2", "#c2410c", "#15803d", "#7e22ce", "#b91c1c", "#0f766e",
        "#a16207", "#be185d" };

    /** At most this many eviction tombstones are kept (oldest dropped first). */
    static final int MAX_TOMBSTONES = 1024;
    /** At most this many retired clientIDs are kept (oldest dropped first), like the tombstones. */
    static final int MAX_RETIRED_CLIENTS = 1024;

    private record Retired( String authorKey, Instant at ) {
    }

    /** An evicted participant: its author, why, and when. */
    record Tombstone( String authorKey, String reason, Instant at ) {
    }

    private final String idTag;
    private final CollabIdentity identity;
    private final Duration retention;
    /** How long a retired clientID is kept: at least the idle-eviction period, so an A13 re-login reclaims it. */
    private final Duration clientRetention;
    private final Map< String, Participant > participants = new LinkedHashMap<>();
    /** clientID of a participant that left, to its author and the time it left; oldest first, bounded. */
    private final Map< String, Retired > retiredClients = new LinkedHashMap<>();
    /** Evicted participant id to its tombstone, oldest first. */
    private final Map< String, Tombstone > tombstones = new LinkedHashMap<>();
    /** The {@code "Editor <n>"} number each nameless author got first, so a rejoin keeps its label in the session. */
    private final Map< String, Integer > editorNumbers = new LinkedHashMap<>();
    private int next = 1;

    /**
     * @param retention       how long a tombstone is kept (the presence timeout)
     * @param clientRetention how long a retired clientID is kept for its author's rejoin (the session's idle-eviction
     *                        period: a signed-out user's SSO re-login can take minutes, A13); never below
     *                        {@code retention}
     */
    Roster( final String idTag, final CollabIdentity identity, final Duration retention, final Duration clientRetention ) {
        this.idTag = idTag;
        this.identity = identity;
        this.retention = retention;
        this.clientRetention = clientRetention.compareTo( retention ) > 0 ? clientRetention : retention;
    }

    Participant add( final JoinRequest r, final Instant now, final int head, final int pushesPerSecond ) {
        prune( now );
        final int n = next++;
        final Participant p = new Participant( idTag + "p" + n, r, clientIdFor( r, n ), displayName( r, n ),
            COLORS[ ( n - 1 ) % COLORS.length ], now, head, pushesPerSecond );
        participants.put( p.id, p );
        return p;
    }

    /**
     * The public name: {@link CollabIdentity}'s, else the join's, else the neutral {@code "Editor <n>"}, where n is the
     * join number of the author's first nameless join in this session (a rejoin keeps its label). Never the author key:
     * logins are private, the display name is the public identity.
     */
    private String displayName( final JoinRequest r, final int n ) {
        final String fromIdentity = identity.displayName( r.authorKey() );
        if( fromIdentity != null && !fromIdentity.isBlank() ) {
            return fromIdentity;
        }
        if( r.displayName() != null && !r.displayName().isBlank() ) {
            return r.displayName();
        }
        return "Editor " + editorNumbers.computeIfAbsent( r.authorKey(), k -> n );
    }

    /**
     * The public name of {@code authorKey} for a meta op's {@code by}: a live participant's display name, else
     * {@link CollabIdentity}'s; null when neither has one. Never the key itself.
     */
    String nameOf( final String authorKey ) {
        for( final Participant p : participants.values() ) {
            if( p.authorKey.equals( authorKey ) ) {
                return p.displayName;
            }
        }
        final String name = identity.displayName( authorKey );
        return name == null || name.isBlank() ? null : name;
    }

    private String clientIdFor( final JoinRequest r, final int n ) {
        final String prior = r.priorClientId();
        final Retired retired = prior == null ? null : retiredClients.get( prior );
        if( retired != null && r.authorKey().equals( retired.authorKey() )
                && participants.values().stream().noneMatch( p -> p.clientId.equals( prior ) ) ) {
            retiredClients.remove( prior );
            return prior;
        }
        return idTag + "c" + n;
    }

    /** Forgets the clientIDs and tombstones older than their retentions, so both stay bounded. */
    private void prune( final Instant now ) {
        final Instant clients = now.minus( clientRetention );
        retiredClients.values().removeIf( r -> r.at().isBefore( clients ) );
        final Instant horizon = now.minus( retention );
        tombstones.values().removeIf( t -> t.at().isBefore( horizon ) );
    }

    /** Removes the participant and lays its tombstone; null (and no tombstone) when it is unknown. */
    Participant evict( final String participantId, final String reason, final Instant now ) {
        final Participant p = remove( participantId, now );
        if( p != null ) {
            tombstones.put( participantId, new Tombstone( p.authorKey, reason, now ) );
            while( tombstones.size() > MAX_TOMBSTONES ) {
                tombstones.remove( tombstones.keySet().iterator().next() );
            }
        }
        return p;
    }

    /** The tombstone of an evicted participant still within the retention, or null. */
    Tombstone tombstone( final String participantId, final Instant now ) {
        prune( now );
        return tombstones.get( participantId );
    }

    /**
     * The live participant a rejoin replaces: the one of the same author holding the join's prior clientID (its own
     * stale entry, not yet timed out, A13), or null.
     */
    Participant staleEntryOf( final JoinRequest r ) {
        final String prior = r.priorClientId();
        if( prior == null ) {
            return null;
        }
        for( final Participant p : participants.values() ) {
            if( p.clientId.equals( prior ) && p.authorKey.equals( r.authorKey() ) ) {
                return p;
            }
        }
        return null;
    }

    Participant get( final String participantId ) {
        return participants.get( participantId );
    }

    /** Removes the participant, remembering its clientID for a rejoin by the same author; null when unknown. */
    Participant remove( final String participantId, final Instant now ) {
        prune( now );
        final Participant p = participants.remove( participantId );
        if( p != null ) {
            retiredClients.put( p.clientId, new Retired( p.authorKey, now ) );
            while( retiredClients.size() > MAX_RETIRED_CLIENTS ) {
                retiredClients.remove( retiredClients.keySet().iterator().next() );
            }
        }
        return p;
    }

    Collection< Participant > all() {
        return participants.values();
    }

    int size() {
        return participants.size();
    }

    List< JoinResponse.Peer > peers() {
        return participants.values().stream().map( p -> new JoinResponse.Peer( p.id, p.displayName, p.color ) ).toList();
    }

    /** The last selections of everyone but {@code participantId} (null: everyone). */
    Map< String, Selection > selectionsExcept( final String participantId ) {
        final Map< String, Selection > out = new LinkedHashMap<>();
        for( final Participant p : participants.values() ) {
            if( !p.id.equals( participantId ) && p.selection() != null ) {
                out.put( p.id, p.selection() );
            }
        }
        return out;
    }

    /** The presence map for {@code participantId}: every other live participant, with or without a selection. */
    Map< String, PresenceEntry > presenceExcept( final String participantId ) {
        final Map< String, PresenceEntry > out = new LinkedHashMap<>();
        for( final Participant p : participants.values() ) {
            if( !p.id.equals( participantId ) ) {
                out.put( p.id, PresenceEntry.of( p.displayName, p.color, p.selection() ) );
            }
        }
        return out;
    }

    /** A10's cut: the lowest version a live participant confirmed, or {@code head} when nobody is here. */
    int lowestConfirmed( final int head ) {
        int cut = head;
        for( final Participant p : participants.values() ) {
            cut = Math.min( cut, p.confirmed() );
        }
        return cut;
    }
}
