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
import com.wikantik.collab.model.Selection;

import java.time.Instant;
import java.util.Collection;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * The participants of one session (section 7.1) and the ids it issues. Participant ids are {@code <tag>p<n>}; a new
 * clientID is {@code <tag>c<n>}, but a join gets its prior clientID back when a participant of the same author held it
 * and left and no live participant holds it (section 7.2 "same clientID"). The tag is unique per session load, so ids
 * never repeat across restarts. Guarded by the session lock.
 */
final class Roster {

    private static final String[] COLORS = { "#e6194b", "#3cb44b", "#4363d8", "#f58231", "#911eb4", "#46a3a3",
        "#f032e6", "#808000" };

    private final String idTag;
    private final Map< String, Participant > participants = new LinkedHashMap<>();
    /** clientID to author key of participants that left. */
    private final Map< String, String > retiredClients = new HashMap<>();
    private int next = 1;

    Roster( final String idTag ) {
        this.idTag = idTag;
    }

    Participant add( final JoinRequest r, final Instant now, final int head, final int pushesPerSecond ) {
        final int n = next++;
        final Participant p = new Participant( idTag + "p" + n, r, clientIdFor( r, n ), COLORS[ ( n - 1 ) % COLORS.length ],
            now, head, pushesPerSecond );
        participants.put( p.id, p );
        return p;
    }

    private String clientIdFor( final JoinRequest r, final int n ) {
        final String prior = r.priorClientId();
        if( prior != null && r.authorKey().equals( retiredClients.get( prior ) )
                && participants.values().stream().noneMatch( p -> p.clientId.equals( prior ) ) ) {
            retiredClients.remove( prior );
            return prior;
        }
        return idTag + "c" + n;
    }

    Participant get( final String participantId ) {
        return participants.get( participantId );
    }

    /** Removes the participant, remembering its clientID for a rejoin by the same author; null when unknown. */
    Participant remove( final String participantId ) {
        final Participant p = participants.remove( participantId );
        if( p != null ) {
            retiredClients.put( p.clientId, p.authorKey );
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

    /** A10's cut: the lowest version a live participant confirmed, or {@code head} when nobody is here. */
    int lowestConfirmed( final int head ) {
        int cut = head;
        for( final Participant p : participants.values() ) {
            cut = Math.min( cut, p.confirmed() );
        }
        return cut;
    }
}
