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

import com.wikantik.collab.model.CollabEvent;
import com.wikantik.collab.model.MetaOp;
import com.wikantik.collab.model.StoredMetaOp;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.List;
import java.util.Map;
import java.util.function.Function;

/**
 * The side streams a pull carries besides text (section 6.2): the recent meta ops and the event stream, each bounded to
 * the last {@code capacity} entries. Meta ops are answered only when every op after the client's cursor is still held
 * (else the pull is a 410, as for text); events are advisory, so a client behind the window gets what is held. Guarded
 * by the session lock.
 */
final class SessionFeed {

    private final int capacity;
    private final Deque< StoredMetaOp > metaOps = new ArrayDeque<>();
    private final Deque< CollabEvent > events = new ArrayDeque<>();
    private long eventSeq;

    SessionFeed( final int capacity, final List< StoredMetaOp > recentMetaOps ) {
        this.capacity = capacity;
        addMetaOps( recentMetaOps );
    }

    void addMetaOps( final List< StoredMetaOp > ops ) {
        for( final StoredMetaOp op : ops ) {
            metaOps.addLast( op );
            while( metaOps.size() > capacity ) {
                metaOps.removeFirst();
            }
        }
    }

    /**
     * The ops after {@code since} up to {@code metaSeq} in wire form, or null when some of them are no longer held. An
     * op a participant authored carries {@code by}, {@code byName} of its author key (the public display name, never
     * the key; null omits it); a server op (no author key) never does.
     */
    List< MetaOp > metaOpsAfter( final long since, final long metaSeq, final Function< String, String > byName ) {
        if( since >= metaSeq ) {
            return List.of();
        }
        if( metaOps.isEmpty() || metaOps.peekFirst().seq() > since + 1 ) {
            return null;
        }
        final List< MetaOp > out = new ArrayList<>();
        for( final StoredMetaOp op : metaOps ) {
            if( op.seq() > since ) {
                out.add( new MetaOp( op.field(), op.value(),
                    op.authorKey() == null ? null : byName.apply( op.authorKey() ) ) );
            }
        }
        return out;
    }

    long eventSeq() {
        return eventSeq;
    }

    CollabEvent emit( final String type, final Map< String, Object > fields ) {
        final CollabEvent e = new CollabEvent( ++eventSeq, type, fields );
        events.addLast( e );
        while( events.size() > capacity ) {
            events.removeFirst();
        }
        return e;
    }

    /** The held events after {@code since}, oldest first. */
    List< CollabEvent > eventsAfter( final long since ) {
        final List< CollabEvent > out = new ArrayList<>();
        for( final CollabEvent e : events ) {
            if( e.seq() > since ) {
                out.add( e );
            }
        }
        return out;
    }
}
