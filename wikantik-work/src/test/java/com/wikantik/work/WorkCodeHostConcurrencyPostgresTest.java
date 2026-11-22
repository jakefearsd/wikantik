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

import com.wikantik.jdbc.testing.RequiresPostgres;
import com.wikantik.work.WorkCodeHostStore.DirectFacts;
import com.wikantik.work.WorkCodeHostStore.HostWrite;
import com.wikantik.work.WorkCodeHostStore.ReviewFacts;
import com.wikantik.work.WorkCodeHostStore.ReviewStep;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.sql.SQLException;
import java.util.List;
import java.util.Set;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import static com.wikantik.work.CodeEvents.A;
import static com.wikantik.work.CodeEvents.M;
import static com.wikantik.work.CodeEvents.at;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Two writers at the same moment, on two connections: the first transaction is parked at its commit holding every lock
 * it took ({@link CommitGate}), the second is started and seen to wait on it, then the first commits. Whatever the
 * timing, a pull request has one change, one set of events and one counted merge.
 */
@RequiresPostgres
class WorkCodeHostConcurrencyPostgresTest {

    private final ExecutorService pool = Executors.newFixedThreadPool( 2 );
    private CodeHostFixture f;
    private Item item;
    private String branch;
    private CodeHostWho ana;
    private CodeHostWho mia;

    @BeforeEach
    void setUp() throws SQLException {
        f = new CodeHostFixture();
        item = f.task();
        branch = "work/" + item.key() + "-fix";
        ana = new CodeHostWho( "ana", "anagh" );
        mia = new CodeHostWho( "mia", "miagh" );
    }

    @AfterEach
    void tearDown() {
        pool.shutdownNow();
    }

    private ReviewFacts pr( final String hhmm ) {
        return new ReviewFacts( "12", "https://github.com/acme/wk/pull/12", branch, "main", A, at( hhmm ), ana );
    }

    private EffectivePolicy policy() throws SQLException {
        return WorkAutonomy.resolve( f.store.policyChain( item.id() ) );
    }

    private long count( final String sql ) throws SQLException {
        return f.count( sql );
    }

    /** Runs {@code first} on a gated store until it holds its locks, then {@code second} until it waits on them. */
    private HostWrite[] race( final StoreCall first, final StoreCall second ) throws Exception {
        final CommitGate gate = new CommitGate( f.ds );
        final Future< HostWrite > one = pool.submit( () -> first.on( new JdbcWorkStore( gate ).notifyStores().codeHost() ) );
        assertTrue( gate.reached.await( 30, TimeUnit.SECONDS ), "the first transaction reached its commit" );
        final Future< HostWrite > two = pool.submit( () -> second.on( f.store.notifyStores().codeHost() ) );
        WorkClaimFixtures.awaitBlockedBy( f.ds, gate.pid );
        gate.open();
        return new HostWrite[] { one.get( 30, TimeUnit.SECONDS ), two.get( 30, TimeUnit.SECONDS ) };
    }

    @FunctionalInterface
    private interface StoreCall {
        HostWrite on( WorkCodeHostStore store ) throws SQLException;
    }

    @Test
    void theSameDeliveryTwiceAtOnceMakesOneChangeAndOneSetOfEvents() throws Exception {
        final HostWrite[] w = race( s -> s.review( f.product.id(), item, pr( "11:30" ), ReviewStep.OPENED, CodeHostWho.UNKNOWN,
            f.github ), s -> s.review( f.product.id(), item, pr( "11:30" ), ReviewStep.OPENED, CodeHostWho.UNKNOWN, f.github ) );
        assertEquals( CodeHostOutcome.APPLIED, w[ 0 ].outcome() );
        assertEquals( CodeHostOutcome.DUPLICATE, w[ 1 ].outcome() );
        assertEquals( 1, count( "SELECT count(*) FROM work_change" ) );
        assertEquals( 1, count( "SELECT count(*) FROM work_event WHERE type = 'change_opened'" ) );
        assertEquals( 1, count( "SELECT count(*) FROM work_event WHERE type = 'review_requested'" ) );
    }

    @Test
    void twoDeliveriesOfOnePullRequestAtOnceMakeOneChange() throws Exception {
        final HostWrite[] w = race( s -> s.review( f.product.id(), item, pr( "11:30" ), ReviewStep.OPENED, CodeHostWho.UNKNOWN,
            f.github ), s -> s.review( f.product.id(), item, pr( "11:45" ), ReviewStep.APPROVED, mia, f.github ) );
        assertEquals( CodeHostOutcome.APPLIED, w[ 1 ].outcome() );
        final Change c = f.only( item );
        assertEquals( "mia", c.reviewedBy() );
        assertTrue( c.approvedForHead() );
        assertEquals( 1, count( "SELECT count(*) FROM work_event WHERE type = 'change_opened'" ) );
    }

    private DirectFacts directPush() {
        return new DirectFacts( "main", M, M, at( "11:19" ), Set.of( M ), Set.of(), ana, at( "11:21" ) );
    }

    private void assertOneCountedMerge() throws SQLException {
        assertEquals( 1, count( "SELECT count(*) FROM work_change WHERE state = 'merged' AND superseded_by IS NULL" ),
            "merged changes that count" );
        assertEquals( 1, count( "SELECT count(*) FROM work_change WHERE state = 'merged' AND review_number = '12'" ) );
        assertEquals( 0, count( "SELECT count(*) FROM work_change WHERE state = 'merged' AND superseded_by IS NULL "
            + "AND merge_kind = 'direct'" ), "the direct record, if any, is superseded" );
    }

    @Test
    void aDirectPushAndThePullRequestMergeOfOneCommitAtOnceCountOnce() throws Exception {
        f.hooks.apply( f.delivery( "github" ), List.of( CodeEvents.opened( branch, "12", A, at( "11:00" ) ) ) );
        final EffectivePolicy p = policy();
        race( s -> s.direct( f.product.id(), item, directPush(), "direct", f.github ),
            s -> s.merge( f.product.id(), item, pr( "11:20" ), M, ana, p, f.github ) );
        assertOneCountedMerge();
        assertEquals( 1, count( "SELECT count(*) FROM work_event WHERE type = 'change_superseded'" ) );
    }

    @Test
    void thePullRequestMergeAndADirectPushOfOneCommitAtOnceCountOnce() throws Exception {
        f.hooks.apply( f.delivery( "github" ), List.of( CodeEvents.opened( branch, "12", A, at( "11:00" ) ) ) );
        final EffectivePolicy p = policy();
        final HostWrite[] w = race( s -> s.merge( f.product.id(), item, pr( "11:20" ), M, ana, p, f.github ),
            s -> s.direct( f.product.id(), item, directPush(), "direct", f.github ) );
        assertEquals( CodeHostOutcome.DUPLICATE, w[ 1 ].outcome(), "the push is the merge already recorded" );
        assertOneCountedMerge();
        assertEquals( 0, count( "SELECT count(*) FROM work_change WHERE merge_kind = 'direct'" ) );
    }

    @Test
    void anAgentsMergeCommittingWhileTheHostReportsItIsNotRecordedTwice() throws Exception {
        final Change mine = f.svc.changes().open( f.author, item.key(), new ChangeDraft( branch, null, A, null, null ) );
        f.hooks.apply( f.delivery( "github" ), List.of( CodeEvents.opened( branch, "12", A, at( "11:00" ) ) ) );
        assertEquals( mine.id(), f.only( item ).id(), "the pull request adopted the agent's change" );
        final EffectivePolicy p = policy();
        final CommitGate gate = new CommitGate( f.ds );
        final Future< Change > agent = pool.submit( () -> new JdbcWorkStore( gate ).changes().merge( item, mine.id(), M,
            locked -> MergeKind.UNREVIEWED, f.mia ) );
        assertTrue( gate.reached.await( 30, TimeUnit.SECONDS ) );
        final Future< HostWrite > host = pool.submit( () -> f.store.notifyStores().codeHost().merge( f.product.id(), item,
            pr( "11:20" ), M, ana, p, f.github ) );
        WorkClaimFixtures.awaitBlockedBy( f.ds, gate.pid );
        gate.open();
        assertEquals( ChangeState.MERGED, agent.get( 30, TimeUnit.SECONDS ).state() );
        assertEquals( CodeHostOutcome.DUPLICATE, host.get( 30, TimeUnit.SECONDS ).outcome() );
        final Change c = f.only( item );
        assertEquals( "mia", c.mergedBy(), "the person's merge is the record" );
        assertNull( c.policyFlag(), "and nothing flags it" );
        assertEquals( 1, count( "SELECT count(*) FROM work_event WHERE type = 'change_merged'" ) );
    }
}
