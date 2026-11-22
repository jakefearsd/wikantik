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

import javax.sql.DataSource;
import java.sql.SQLException;
import java.time.Instant;
import java.util.List;

/**
 * Deployments and changes with controlled times: each helper moves the record through the store, then sets the time
 * the figures read by SQL (the code uses NOW()). Never the wall clock.
 */
final class DoraFixtures {

    private final WorkStore store;
    private final DataSource ds;
    private final Actor by;
    private int version;

    DoraFixtures( final GateFixture f ) {
        this.ds = f.ds;
        this.store = new JdbcWorkStore( f.ds );
        this.by = f.lena;
    }

    /** A requested production deployment of the product carrying the items, fixing {@code remediates} (or null). */
    long deploy( final String productKey, final List< Item > items, final Long remediates ) throws SQLException {
        return deploy( productKey, "production", items, remediates );
    }

    long deploy( final String productKey, final String environment, final List< Item > items, final Long remediates )
            throws SQLException {
        final Product p = store.product( productKey ).orElseThrow();
        return store.deployments().request( p, environment, "1.0." + ( ++version ), items, remediates, by ).id();
    }

    /** Approves and starts the deployment, and sets when it started. */
    void ran( final long id, final Instant started ) throws SQLException {
        store.deployments().move( id, DeploymentState.APPROVED, null, null, by );
        store.deployments().move( id, DeploymentState.RUNNING, null, null, by );
        exec( "UPDATE work_deployment SET started_at = '" + started + "'::timestamptz WHERE id = " + id );
    }

    void verified( final long id, final Instant at ) throws SQLException {
        store.deployments().move( id, DeploymentState.VERIFIED, "health 200; smoke ok", null, by );
        exec( "UPDATE work_deployment SET verified_at = '" + at + "'::timestamptz WHERE id = " + id );
        exec( "UPDATE work_deployment_event SET at = '" + at + "'::timestamptz WHERE deployment_id = " + id
            + " AND to_state = 'verified'" );
    }

    void failed( final long id, final Instant at ) throws SQLException {
        store.deployments().move( id, DeploymentState.FAILED, null, "smoke red", by );
        exec( "UPDATE work_deployment_event SET at = '" + at + "'::timestamptz WHERE deployment_id = " + id
            + " AND to_state = 'failed'" );
    }

    void rolledBack( final long id, final Instant at ) throws SQLException {
        store.deployments().move( id, DeploymentState.ROLLED_BACK, null, "regression", by );
        exec( "UPDATE work_deployment_event SET at = '" + at + "'::timestamptz WHERE deployment_id = " + id
            + " AND to_state = 'rolled_back'" );
    }

    void rejected( final long id ) throws SQLException {
        store.deployments().move( id, DeploymentState.REJECTED, null, "wrong build", by );
    }

    /** Verified in production at {@code at}, having started an hour earlier. */
    long live( final String productKey, final List< Item > items, final Instant at ) throws SQLException {
        final long id = deploy( productKey, items, null );
        ran( id, at.minusSeconds( 3_600 ) );
        verified( id, at );
        return id;
    }

    /** An open change on the item (branch {@code branch}); {@link #merged} makes it a merge. */
    long change( final GateFixture f, final Item item, final String branch ) throws SQLException {
        return f.svc.changes().open( f.admin, item.key(), new ChangeDraft( branch, null, "abc1234", null, null ) ).id();
    }

    /** Marks the change merged as {@code kind} at {@code mergedAt}, opened at {@code openedAt}. */
    void merged( final long changeId, final String kind, final Instant openedAt, final Instant mergedAt,
                 final Instant firstCommitAt ) throws SQLException {
        exec( "UPDATE work_change SET state = 'merged', merge_kind = '" + kind + "', merged_sha = 'def4567', "
            + "opened_at = '" + openedAt + "'::timestamptz, closed_at = '" + mergedAt + "'::timestamptz, "
            + "first_commit_at = " + ( firstCommitAt == null ? "NULL" : "'" + firstCommitAt + "'::timestamptz" )
            + " WHERE id = " + changeId );
    }

    void exec( final String sql ) throws SQLException {
        WorkAgentFixtures.exec( ds, sql );
    }
}
