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
package com.wikantik.audit;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

/** In-memory AuditRepository for unit tests. Mirrors the chain semantics of the
 *  JDBC impl without Postgres. Not thread-safe beyond the single writer thread. */
public final class InMemoryAuditRepository implements AuditRepository {

    private final List<PersistedAuditEntry> rows = new ArrayList<>();

    @Override
    public synchronized ChainHead chainHead() {
        if ( rows.isEmpty() ) return new ChainHead( 0L, AuditChainHasher.GENESIS_PREV_HASH );
        PersistedAuditEntry last = rows.get( rows.size() - 1 );
        return new ChainHead( last.seq(), last.rowHash() );
    }

    @Override
    public synchronized void append( List<AuditEntry> entries ) {
        ChainHead head = chainHead();
        long seq = head.lastSeq();
        String prev = head.lastHash();
        for ( AuditEntry e : entries ) {
            seq++;
            String rowHash = AuditChainHasher.hash( prev, e );
            rows.add( new PersistedAuditEntry( seq, Instant.parse( "2026-06-03T10:00:00Z" ), prev, rowHash, e ) );
            prev = rowHash;
        }
    }

    @Override
    public synchronized List<PersistedAuditEntry> query( AuditQuery q ) {
        return new ArrayList<>( rows ); // tests only assert ordering/size
    }

    @Override
    public synchronized Optional<Long> verifyChain( long fromSeq, long toSeq ) {
        String prev = AuditChainHasher.GENESIS_PREV_HASH;
        for ( PersistedAuditEntry r : rows ) {
            String expected = AuditChainHasher.hash( prev, r.entry() );
            if ( !expected.equals( r.rowHash() ) ) return Optional.of( r.seq() );
            prev = r.rowHash();
        }
        return Optional.empty();
    }
}
