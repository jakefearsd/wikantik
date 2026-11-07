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

import com.wikantik.collab.model.StoredUpdate;
import com.wikantik.collab.model.Update;
import com.wikantik.collab.text.ChangeSet;
import com.wikantik.collab.text.CollabText;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import java.util.ArrayList;
import java.util.List;

/**
 * Section 7.2's per-update checks, run over the whole batch before anything is stored: the clientID is the
 * participant's (E14), the change set fits the document (E15), no surrogate pair is split and no lone surrogate results
 * (E2), and the document never grows past {@code max(maxPageBytes, current size)} (413, the oversize rule). Either
 * {@code refusal} is set, or {@code accepted} holds the batch as log entries from {@code head + 1} and {@code next} the
 * resulting document.
 */
record PushCheck( Refusal refusal, List< StoredUpdate > accepted, String next ) {

    private static final Logger LOG = LogManager.getLogger( PushCheck.class );

    record Refusal( int status, String reason ) {
    }

    private static PushCheck refuse( final int status, final String reason ) {
        return new PushCheck( new Refusal( status, reason ), List.of(), null );
    }

    static PushCheck of( final Participant p, final List< Update > updates, final String doc, final int head,
                         final long maxPageBytes ) {
        String cur = doc;
        final List< StoredUpdate > accepted = new ArrayList<>( updates.size() );
        for( final Update u : updates ) {
            if( !p.clientId.equals( u.clientId() ) ) {
                LOG.warn( "push from {} names clientID {} (bound to {}): refused", p.id, u.clientId(), p.clientId );
                return refuse( 400, CollabSession.REJECTED );
            }
            final ChangeSet cs = u.changes();
            if( cs.length() != cur.length() ) {
                LOG.warn( "push from {}: change set length {} does not match document length {}", p.id, cs.length(),
                    cur.length() );
                return refuse( 400, CollabSession.REJECTED );
            }
            final String next = cs.apply( cur );
            if( CollabText.splitsSurrogatePair( cur, cs ) || CollabText.hasLoneSurrogate( next ) ) {
                LOG.warn( "push from {}: the update would leave a lone surrogate", p.id );
                return refuse( 400, CollabSession.REJECTED );
            }
            if( Oversize.utf8( next ) > Math.max( maxPageBytes, Oversize.utf8( cur ) ) ) {
                return refuse( 413, "oversize" );
            }
            accepted.add( new StoredUpdate( head + accepted.size() + 1, StoredUpdate.TEXT, p.clientId, p.authorKey, cs ) );
            cur = next;
        }
        return new PushCheck( null, accepted, cur );
    }
}
