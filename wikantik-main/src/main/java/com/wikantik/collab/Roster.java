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
 * and left within the retention (the presence timeout) and no live participant holds it (section 7.2 "same clientID").
 * The tag is unique per session load, so ids never repeat across restarts. Display names come from
 * {@link CollabIdentity} (the join's name only when it has none); colours from a fixed palette by join order within the
 * session, never from the login (section 8.3). Guarded by the session lock.
 */
final class Roster {

    private static final String[] COLORS = { "#1b6ec2", "#c2410c", "#15803d", "#7e22ce", "#b91c1c", "#0f766e",
        "#a16207", "#be185d" };

    private record Retired( String authorKey, Instant at ) {
    }

    private final String idTag;
    private final CollabIdentity identity;
    private final Duration retention;
    private final Map< String, Participant > participants = new LinkedHashMap<>();
    /** clientID of a participant that left, to its author and the time it left. */
    private final Map< String, Retired > retiredClients = new LinkedHashMap<>();
    private int next = 1;

    Roster( final String idTag, final CollabIdentity identity, final Duration retention ) {
        this.idTag = idTag;
        this.identity = identity;
        this.retention = retention;
    }

    Participant add( final JoinRequest r, final Instant now, final int head, final int pushesPerSecond ) {
        prune( now );
        final int n = next++;
        final String name = identity.displayName( r.authorKey() );
        final Participant p = new Participant( idTag + "p" + n, r, clientIdFor( r, n ), name == null || name.isBlank()
            ? r.displayName() : name, COLORS[ ( n - 1 ) % COLORS.length ], now, head, pushesPerSecond );
        participants.put( p.id, p );
        return p;
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

    /** Forgets the clientIDs retired longer ago than the retention, so the map stays bounded. */
    private void prune( final Instant now ) {
        final Instant horizon = now.minus( retention );
        retiredClients.values().removeIf( r -> r.at().isBefore( horizon ) );
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

    /** The presence map for {@code participantId}: every other participant that has a selection. */
    Map< String, PresenceEntry > presenceExcept( final String participantId ) {
        final Map< String, PresenceEntry > out = new LinkedHashMap<>();
        for( final Participant p : participants.values() ) {
            if( !p.id.equals( participantId ) && p.selection() != null ) {
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
