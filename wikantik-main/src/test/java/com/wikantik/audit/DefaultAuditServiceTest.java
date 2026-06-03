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
import java.util.concurrent.TimeUnit;
import static org.junit.jupiter.api.Assertions.*;

class DefaultAuditServiceTest {

    private AuditEntry e( String type ) {
        return AuditEntry.builder().eventTime( Instant.parse( "2026-06-03T10:00:00Z" ) )
            .category( AuditCategory.AUTHN ).eventType( type )
            .actorType( "user" ).outcome( AuditOutcome.SUCCESS ).build();
    }

    @Test
    void recordedEntriesAreWrittenInOrderWithValidChain() throws Exception {
        InMemoryAuditRepository repo = new InMemoryAuditRepository();
        DefaultAuditService svc = new DefaultAuditService( repo, 1000 );
        AuditWriterThread writer = new AuditWriterThread( svc, repo );
        writer.start();
        try {
            for ( int i = 0; i < 5; i++ ) svc.record( e( "evt-" + i ) );
            // Wait for the writer to drain.
            long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos( 5 );
            while ( repo.query( AuditQuery.all() ).size() < 5 && System.nanoTime() < deadline ) {
                Thread.sleep( 10 );
            }
        } finally {
            writer.shutdownWriter();
            writer.join( 2000 );
        }
        List<PersistedAuditEntry> rows = repo.query( AuditQuery.all() );
        assertEquals( 5, rows.size() );
        assertEquals( "evt-0", rows.get( 0 ).entry().eventType() );
        assertEquals( "evt-4", rows.get( 4 ).entry().eventType() );
        assertTrue( repo.verifyChain( 1, Long.MAX_VALUE ).isEmpty(), "chain intact" );
    }

    @Test
    void recordNeverThrowsWhenQueueFull() {
        InMemoryAuditRepository repo = new InMemoryAuditRepository();
        DefaultAuditService svc = new DefaultAuditService( repo, 1 ); // tiny queue, no writer draining
        // Flood past capacity — must not throw; overflow is dropped + counted.
        for ( int i = 0; i < 100; i++ ) svc.record( e( "x" ) );
        assertTrue( svc.droppedCount() > 0, "overflow should be counted as dropped" );
    }
}
