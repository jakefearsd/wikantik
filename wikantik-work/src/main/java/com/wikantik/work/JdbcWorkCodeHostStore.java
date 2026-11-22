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

import com.wikantik.jdbc.Jdbc;

import java.sql.SQLException;
import java.time.Instant;
import java.util.Map;
import java.util.Optional;

/**
 * The PostgreSQL {@link WorkCodeHostStore}: a thin class that delegates each method to the {@code Jdbc*} class that
 * owns its SQL (hooks and identities here; live changes and merges in the classes later tasks add).
 */
final class JdbcWorkCodeHostStore implements WorkCodeHostStore {

    private final JdbcWorkHooks hooks;
    private final JdbcWorkCodeHost live;
    private final JdbcWorkCodeHostMerges merges;
    private final JdbcWorkChangeFlags flags;

    JdbcWorkCodeHostStore( final Jdbc jdbc, final JdbcWorkCatalog catalog ) {
        this.hooks = new JdbcWorkHooks( jdbc, catalog );
        final JdbcCodeHostRows rows = new JdbcCodeHostRows( jdbc, catalog );
        this.live = new JdbcWorkCodeHost( rows );
        this.merges = new JdbcWorkCodeHostMerges( rows );
        this.flags = new JdbcWorkChangeFlags( jdbc, catalog );
    }

    @Override
    public java.util.List< Item > flagged( final java.util.Set< Long > projectIds ) throws SQLException {
        return flags.flagged( projectIds );
    }

    @Override
    public Change acknowledgeFlag( final Item item, final long changeId, final Actor by ) throws SQLException {
        return flags.acknowledge( item, changeId, by );
    }

    @Override
    public HostWrite merge( final long productId, final Item item, final ReviewFacts facts, final String mergedSha,
                            final CodeHostWho merger, final EffectivePolicy policy, final Actor by )
            throws SQLException {
        return merges.merge( productId, item, facts, mergedSha, merger, policy, by );
    }

    @Override
    public HostWrite direct( final long productId, final Item item, final DirectFacts facts, final String flagOrNull,
                             final Actor by ) throws SQLException {
        return merges.direct( productId, item, facts, flagOrNull, by );
    }

    @Override
    public HostWrite branchPush( final long productId, final Item item, final BranchFacts facts, final Actor by )
            throws SQLException {
        return live.branchPush( productId, item, facts, by );
    }

    @Override
    public HostWrite review( final long productId, final Item item, final ReviewFacts facts, final ReviewStep step,
                             final CodeHostWho reviewer, final Actor by ) throws SQLException {
        return live.review( productId, item, facts, step, reviewer, by );
    }

    @Override
    public Optional< Hook > hook( final long productId ) throws SQLException {
        return hooks.hook( productId );
    }

    @Override
    public Map< Long, Hook > hooks() throws SQLException {
        return hooks.hooks();
    }

    @Override
    public boolean putHook( final Product product, final String secret, final Instant at, final Actor by )
            throws SQLException {
        return hooks.putHook( product, secret, at, by );
    }

    @Override
    public boolean disableHook( final Product product, final Instant at, final Actor by ) throws SQLException {
        return hooks.disableHook( product, at, by );
    }

    @Override
    public void received( final long productId, final Instant at ) throws SQLException {
        hooks.received( productId, at );
    }

    @Override
    public Optional< String > learned( final String host, final String username ) throws SQLException {
        return hooks.learned( host, username );
    }

    @Override
    public void learn( final String host, final String username, final String login ) throws SQLException {
        hooks.learn( host, username, login );
    }
}
