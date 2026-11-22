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
package com.wikantik.work;

import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class WorkCodeHostRejectedTest {

    @Test
    void aRejectedDeliveryIsCountedAsDeliveryRejectedAndByItsReason() {
        final List< String > seen = new ArrayList<>();
        final WorkListener recording = new WorkListener() {
            @Override
            public void codeHost( final String kind, final String outcome ) {
                seen.add( kind + "/" + outcome );
            }

            @Override
            public void codeHostRejected( final HookRejection reason ) {
                seen.add( "reason/" + reason.wire() );
            }
        };
        final FeatureDeps deps = new FeatureDeps( null, new GuardedListener( recording ), null, null, null, null, null );
        new WorkCodeHostService( mock( WorkStore.class ), null, Clock.systemUTC(), deps ).rejected( HookRejection.SIGNATURE );
        assertEquals( List.of( "delivery/rejected", "reason/signature" ), seen );
    }

    @Test
    void everyReasonHasAFixedLowerCaseWireName() {
        for ( final HookRejection r : HookRejection.values() ) {
            assertTrue( r.wire().matches( "[a-z_]+" ), r.wire() );
        }
    }

    @Test
    void onlyAWellFormedSignatureHeaderIsWorthReadingABodyFor() {
        assertTrue( HookSignature.wellFormed( "sha256=" + "aB".repeat( 32 ) ) );
        assertFalse( HookSignature.wellFormed( null ) );
        assertFalse( HookSignature.wellFormed( "" ) );
        assertFalse( HookSignature.wellFormed( "sha1=" + "ab".repeat( 20 ) ) );
        assertFalse( HookSignature.wellFormed( "sha256=" + "ab".repeat( 31 ) ) );
        assertFalse( HookSignature.wellFormed( "sha256=" + "zz".repeat( 32 ) ) );
    }

    /**
     * An unknown product and a known product's wrong signature must not be told apart by the work done: both look up
     * a hook (the unknown one under an id no product has) and both compute one HMAC, the unknown one under the fixed
     * dummy key.
     */
    @Test
    void anUnknownProductDoesTheSameLookupsAsAWrongSignature() throws Exception {
        final WorkStore store = mock( WorkStore.class );
        final WorkNotifyStores holder = mock( WorkNotifyStores.class );
        final WorkCodeHostStore hosts = mock( WorkCodeHostStore.class );
        when( store.notifyStores() ).thenReturn( holder );
        when( holder.codeHost() ).thenReturn( hosts );
        final Product wk = new Product( 7, "wk", "Wk", "https://github.com/acme/wk", "main", null, Autonomy.AUTONOMOUS,
            DeployGate.HUMAN, null );
        when( store.product( "wk" ) ).thenReturn( Optional.of( wk ) );
        when( store.product( "nope" ) ).thenReturn( Optional.empty() );
        when( hosts.hook( anyLong() ) ).thenReturn( Optional.empty() );
        when( hosts.hook( 7L ) ).thenReturn( Optional.of( new WorkCodeHostStore.Hook( 7L, "s".repeat( 64 ), "admin",
            Instant.EPOCH, null, null ) ) );
        final FeatureDeps deps = new FeatureDeps( null, new GuardedListener( new WorkListener() { } ), null, null, null,
            null, null );
        final WorkCodeHostService svc = new WorkCodeHostService( store, null, Clock.systemUTC(), deps );
        final byte[] body = "{}".getBytes( StandardCharsets.UTF_8 );
        final String wrong = "sha256=" + "ab".repeat( 32 );
        assertTrue( svc.authenticate( "wk", "github", body, wrong ).isEmpty() );
        assertTrue( svc.authenticate( "nope", "github", body, wrong ).isEmpty() );
        verify( hosts, times( 2 ) ).hook( anyLong() );
    }
}
