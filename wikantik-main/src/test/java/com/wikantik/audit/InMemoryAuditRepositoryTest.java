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

import org.junit.jupiter.api.Test;
import java.time.Instant;
import java.util.List;
import static org.junit.jupiter.api.Assertions.*;

class InMemoryAuditRepositoryTest {

    private AuditEntry e( String type ) {
        return AuditEntry.builder().eventTime( Instant.parse( "2026-06-03T10:00:00Z" ) )
            .category( AuditCategory.AUTHN ).eventType( type )
            .actorType( "user" ).outcome( AuditOutcome.SUCCESS ).build();
    }

    @Test
    void appendAssignsContiguousSeqAndChains() {
        InMemoryAuditRepository repo = new InMemoryAuditRepository();
        repo.append( List.of( e( "a" ), e( "b" ) ) );
        repo.append( List.of( e( "c" ) ) );

        List<PersistedAuditEntry> all = repo.query( AuditQuery.all() );
        assertEquals( 3, all.size() );
        assertEquals( 1L, all.get( 0 ).seq() );
        assertEquals( 3L, all.get( 2 ).seq() );
        // chain: each prev_hash equals the previous row_hash; first is genesis.
        assertEquals( AuditChainHasher.GENESIS_PREV_HASH, all.get( 0 ).prevHash() );
        assertEquals( all.get( 0 ).rowHash(), all.get( 1 ).prevHash() );
        assertEquals( all.get( 1 ).rowHash(), all.get( 2 ).prevHash() );
    }

    @Test
    void chainHeadReflectsLastRow() {
        InMemoryAuditRepository repo = new InMemoryAuditRepository();
        assertEquals( AuditChainHasher.GENESIS_PREV_HASH, repo.chainHead().lastHash() );
        assertEquals( 0L, repo.chainHead().lastSeq() );
        repo.append( List.of( e( "a" ) ) );
        assertEquals( 1L, repo.chainHead().lastSeq() );
    }
}
