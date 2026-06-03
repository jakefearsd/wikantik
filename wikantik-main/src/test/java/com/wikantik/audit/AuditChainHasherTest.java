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
import static org.junit.jupiter.api.Assertions.*;

class AuditChainHasherTest {

    private AuditEntry entry( String type ) {
        return AuditEntry.builder()
            .eventTime( Instant.parse( "2026-06-03T10:00:00Z" ) )
            .category( AuditCategory.AUTHN ).eventType( type )
            .actorType( "user" ).outcome( AuditOutcome.SUCCESS ).build();
    }

    @Test
    void genesisPrevHashIs64Zeros() {
        assertEquals( "0".repeat( 64 ), AuditChainHasher.GENESIS_PREV_HASH );
    }

    @Test
    void hashIsDeterministicHex64() {
        String h1 = AuditChainHasher.hash( AuditChainHasher.GENESIS_PREV_HASH, entry( "login.ok" ) );
        String h2 = AuditChainHasher.hash( AuditChainHasher.GENESIS_PREV_HASH, entry( "login.ok" ) );
        assertEquals( h1, h2 );
        assertEquals( 64, h1.length() );
        assertTrue( h1.matches( "[0-9a-f]{64}" ) );
    }

    @Test
    void hashDependsOnPrevHash() {
        String a = AuditChainHasher.hash( AuditChainHasher.GENESIS_PREV_HASH, entry( "x" ) );
        String b = AuditChainHasher.hash( "f".repeat( 64 ), entry( "x" ) );
        assertNotEquals( a, b );
    }

    @Test
    void hashDependsOnEntryContent() {
        String a = AuditChainHasher.hash( AuditChainHasher.GENESIS_PREV_HASH, entry( "x" ) );
        String b = AuditChainHasher.hash( AuditChainHasher.GENESIS_PREV_HASH, entry( "y" ) );
        assertNotEquals( a, b );
    }
}
