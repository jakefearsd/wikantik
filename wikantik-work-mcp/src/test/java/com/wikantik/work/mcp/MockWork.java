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
package com.wikantik.work.mcp;

import com.wikantik.work.Actor;
import com.wikantik.work.Autonomy;
import com.wikantik.work.DoneMeans;
import com.wikantik.work.Item;
import com.wikantik.work.WorkClaimsService;
import com.wikantik.work.WorkDirectory;
import com.wikantik.work.WorkKind;
import com.wikantik.work.WorkLevel;
import com.wikantik.work.WorkRegistryService;
import com.wikantik.work.WorkService;
import com.wikantik.work.WorkService.ItemView;
import com.wikantik.work.WorkStatus;

import java.time.Instant;
import java.util.List;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/** Mockito scaffolding for the write tools' unit tests: a service whose facades are mocks, and sample items. */
final class MockWork {

    final WorkService svc = mock( WorkService.class );
    final WorkClaimsService claims = mock( WorkClaimsService.class );
    final WorkRegistryService registry = mock( WorkRegistryService.class );
    final com.wikantik.work.WorkDeploymentsService deployments = mock( com.wikantik.work.WorkDeploymentsService.class );
    final com.wikantik.work.WorkChangesService changes = mock( com.wikantik.work.WorkChangesService.class );
    final WorkDirectory directory = mock( WorkDirectory.class );
    final Actor agent = Actor.agent( 7, "builder", com.wikantik.work.Actor.human( "admin", java.util.Set.of(), true ) );

    MockWork() throws java.sql.SQLException {
        when( svc.claims() ).thenReturn( claims );
        when( svc.registry() ).thenReturn( registry );
        when( registry.listAgents( any() ) ).thenReturn( List.of() );
        when( registry.listQueues( any() ) ).thenReturn( List.of() );
        when( svc.events( any(), any() ) ).thenReturn( List.of() );
        when( svc.changes() ).thenReturn( changes );
        when( svc.deployments() ).thenReturn( deployments );
        when( svc.policyOf( any(), any() ) ).thenReturn( new com.wikantik.work.ItemPolicyView(
            new com.wikantik.work.EffectivePolicy( Autonomy.DELEGATED, false, DoneMeans.MERGED ), Autonomy.DELEGATED,
            com.wikantik.work.Ack.NONE ) );
        when( directory.displayName( any() ) ).thenReturn( "Someone" );
    }

    static Item item( final String key, final WorkStatus status, final int version, final Long claimedBy,
                      final Instant expires ) {
        return new Item( 1, 1, key, WorkLevel.TASK, null, null, WorkKind.SOFTWARE, "A title", "A body", status, 3, 1.0,
            null, null, "lena", Autonomy.SUPERVISED, DoneMeans.MERGED, null, version, Instant.EPOCH, Instant.EPOCH, null,
            "implementer", claimedBy, null, expires, null );
    }

    static ItemView view( final Item item, final String cluster ) {
        return new ItemView( item, "<p>x</p>", "PLAT", null, null, List.of(), List.of(), List.of(), false, null, false,
            cluster );
    }

    static ItemView view( final Item item ) {
        return view( item, null );
    }

}
