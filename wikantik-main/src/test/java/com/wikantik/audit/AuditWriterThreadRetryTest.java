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
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** A transient persistence failure must not lose the audit batch. */
class AuditWriterThreadRetryTest {

    /** Fails the first {@code failures} appends, then records batches in order. */
    private static final class FlakyRepository implements AuditRepository {
        final AtomicInteger calls = new AtomicInteger();
        final List< List< AuditEntry > > persisted = new ArrayList<>();
        private final int failures;

        FlakyRepository( final int failures ) { this.failures = failures; }

        @Override public ChainHead chainHead() { return new ChainHead( 0L, AuditChainHasher.GENESIS_PREV_HASH ); }

        @Override public void append( final List< AuditEntry > entries ) {
            if ( calls.incrementAndGet() <= failures ) {
                throw new IllegalStateException( "audit append failed" );
            }
            persisted.add( List.copyOf( entries ) );
        }

        @Override public List< PersistedAuditEntry > query( final AuditQuery q ) { return List.of(); }
        @Override public Optional< Long > verifyChain( final long fromSeq, final long toSeq ) { return Optional.empty(); }
    }

    private static AuditEntry entry( final String type ) {
        return AuditEntry.builder().eventTime( Instant.now() ).category( AuditCategory.ADMIN )
                .eventType( type ).actorType( "TEST" ).outcome( AuditOutcome.SUCCESS ).build();
    }

    @Test
    void batchSurvivesATransientFailureAndKeepsItsOrder() {
        final FlakyRepository repo = new FlakyRepository( 1 );
        final AuditWriterThread writer = new AuditWriterThread( new DefaultAuditService( repo, 10 ), repo, 1L );
        final List< AuditEntry > batch = List.of( entry( "a" ), entry( "b" ), entry( "c" ) );

        assertTrue( writer.persist( batch ) );

        assertEquals( 2, repo.calls.get() );
        assertEquals( 1, repo.persisted.size() );
        assertEquals( List.of( "a", "b", "c" ), repo.persisted.get( 0 ).stream().map( AuditEntry::eventType ).toList() );
    }

    @Test
    void batchIsDroppedOnlyAfterBoundedRetries() {
        final FlakyRepository repo = new FlakyRepository( Integer.MAX_VALUE );
        final AuditWriterThread writer = new AuditWriterThread( new DefaultAuditService( repo, 10 ), repo, 1L );

        assertFalse( writer.persist( List.of( entry( "a" ) ) ) );

        assertEquals( AuditWriterThread.MAX_ATTEMPTS, repo.calls.get() );
        assertTrue( repo.persisted.isEmpty() );
    }
}
