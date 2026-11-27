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

import java.time.Instant;
import java.util.List;

/** Items, projects and products for tests of the policy resolver and the gates; only the policy fields matter. */
final class PolicyItems {

    private PolicyItems() {
    }

    static Item item( final WorkLevel level, final Autonomy autonomy, final Boolean allowDirect,
                      final DoneMeans doneMeans ) {
        return new Item( 1, 1, "PLAT-1", level, null, null, WorkKind.SOFTWARE, "t", "", WorkStatus.READY, 2, 1.0,
            null, null, "alice", autonomy, doneMeans, allowDirect, 1, Instant.EPOCH, Instant.EPOCH, null, null, null,
            null, null, null, null, null, null, null );
    }

    static Item task( final Autonomy autonomy ) {
        return item( WorkLevel.TASK, autonomy, null, null );
    }

    static Project project( final Autonomy defaultAutonomy ) {
        return new Project( 1, "PLAT", "Platform", "", defaultAutonomy, Instant.EPOCH, null, null );
    }

    static Product product( final Autonomy ceiling ) {
        return new Product( 1, "wikantik", "Wikantik", null, "main", null, ceiling, DeployGate.HUMAN, null );
    }

    static PolicyChain chain( final Item item, final List< Item > ancestors, final Autonomy projectDefault,
                              final Autonomy ceilingOrNull ) {
        return new PolicyChain( item, ancestors, project( projectDefault ),
            ceilingOrNull == null ? null : product( ceilingOrNull ) );
    }

    static EffectivePolicy policy( final Autonomy level, final boolean allowDirect ) {
        return new EffectivePolicy( level, allowDirect, DoneMeans.MERGED );
    }
}
