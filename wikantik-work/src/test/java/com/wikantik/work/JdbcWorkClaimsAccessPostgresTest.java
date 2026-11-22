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

import com.wikantik.jdbc.testing.PostgresTestDb;
import com.wikantik.jdbc.testing.RequiresPostgres;
import org.apache.logging.log4j.Level;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.core.LogEvent;
import org.apache.logging.log4j.core.appender.AbstractAppender;
import org.apache.logging.log4j.core.config.Configurator;
import org.apache.logging.log4j.core.config.Property;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.sql.Connection;
import java.sql.SQLException;
import java.sql.Statement;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.CopyOnWriteArrayList;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * An agent that loses access (member removed or demoted, or retired) can never end up holding an item after that
 * change commits: a claim waits on the agent and member rows and finds nothing. Lock-wait tests park the competing
 * transaction at its commit with {@link CommitGate} and prove the wait with {@code pg_blocking_pids}.
 */
@RequiresPostgres
class JdbcWorkClaimsAccessPostgresTest {

    private static final Duration HOUR = Duration.ofHours( 1 );

    private WorkClaimFixtures fx;
    private WorkClaimStore claims;
    private ExecutorService pool;

    @BeforeEach
    void setUp() throws SQLException {
        PostgresTestDb.truncate( WorkAgentFixtures.TABLES );
        fx = new WorkClaimFixtures( PostgresTestDb.createDataSource() );
        claims = fx.store.claims();
        pool = Executors.newCachedThreadPool();
    }

    @org.junit.jupiter.api.AfterEach
    void tearDown() {
        pool.shutdownNow();
    }

    private List< String > claimTypes( final Item item ) throws SQLException {
        return fx.store.itemEvents( item.id() ).stream().map( WorkEvent::type )
            .filter( t -> t.startsWith( "claim" ) || t.equals( WorkEvent.RELEASED ) ).toList();
    }

    @Test
    void claimWaitingOnMemberRemovalComesBackEmpty() throws Exception {
        final Item held = claims.claim( fx.ready( "x" ), fx.actorA, HOUR ).orElseThrow();
        final CommitGate gate = new CommitGate( fx.ds );
        final Future< ? > removal = pool.submit( () -> {
            new JdbcWorkStore( gate ).removeMember( fx.plat.id(), String.valueOf( fx.a.id() ), "agent",
                WorkClaimFixtures.LENA );
            return null;
        } );
        assertTrue( gate.reached.await( 30, TimeUnit.SECONDS ) );
        final Future< Optional< Item > > waiting = pool.submit( () -> claims.claim( held, fx.actorA, HOUR ) );
        WorkClaimFixtures.awaitBlockedBy( fx.ds, gate.pid );
        gate.open();
        removal.get( 30, TimeUnit.SECONDS );
        assertTrue( waiting.get( 30, TimeUnit.SECONDS ).isEmpty() );
        final Item after = fx.reload( held );
        assertNull( after.claimedByAgent() );
        assertEquals( WorkStatus.READY, after.status() );
        assertEquals( List.of( WorkEvent.CLAIMED, WorkEvent.RELEASED ), claimTypes( held ) );
    }

    @Test
    void renewalWaitingOnRetirementComesBackEmpty() throws Exception {
        final Item held = claims.claim( fx.ready( "x" ), fx.actorA, HOUR ).orElseThrow();
        final CommitGate gate = new CommitGate( fx.ds );
        final Future< ? > retirement = pool.submit( () -> {
            new JdbcWorkStore( gate ).claims().retireAgent( fx.a.id(), Instant.now(), WorkClaimFixtures.LENA );
            return null;
        } );
        assertTrue( gate.reached.await( 30, TimeUnit.SECONDS ) );
        final Future< Optional< Item > > waiting = pool.submit( () -> claims.claim( held, fx.actorA, HOUR ) );
        WorkClaimFixtures.awaitBlockedBy( fx.ds, gate.pid );
        gate.open();
        retirement.get( 30, TimeUnit.SECONDS );
        assertTrue( waiting.get( 30, TimeUnit.SECONDS ).isEmpty() );
        assertNull( fx.reload( held ).claimedByAgent() );
        assertEquals( List.of( WorkEvent.CLAIMED, WorkEvent.RELEASED ), claimTypes( held ) );
    }

    @Test
    void retireAfterAnUncommittedClaimReleasesIt() throws Exception {
        final Item item = fx.ready( "x" );
        final CommitGate gate = new CommitGate( fx.ds );
        final Future< Optional< Item > > claim = pool.submit(
            () -> new JdbcWorkStore( gate ).claims().claim( item, fx.actorA, HOUR ) );
        assertTrue( gate.reached.await( 30, TimeUnit.SECONDS ) );
        final Future< ? > retire = pool.submit( () -> {
            claims.retireAgent( fx.a.id(), Instant.now(), WorkClaimFixtures.LENA );
            return null;
        } );
        WorkClaimFixtures.awaitBlockedBy( fx.ds, gate.pid );
        gate.open();
        assertTrue( claim.get( 30, TimeUnit.SECONDS ).isPresent() );
        retire.get( 30, TimeUnit.SECONDS );
        final Item after = fx.reload( item );
        assertNull( after.claimedByAgent() );
        assertEquals( WorkStatus.READY, after.status() );
        assertEquals( List.of( WorkEvent.CLAIMED, WorkEvent.RELEASED ), claimTypes( item ) );
        assertEquals( "agent retired", fx.events( item, WorkEvent.RELEASED ).get( 0 ).note() );
    }

    @Test
    void claimByRetiredAgentIsEmpty() throws SQLException {
        final Item item = fx.ready( "x" );
        claims.retireAgent( fx.a.id(), Instant.now(), WorkClaimFixtures.LENA );
        assertTrue( claims.claim( item, fx.actorA, HOUR ).isEmpty() );
        assertEquals( item, fx.reload( item ) );
    }

    @Test
    void claimByNonMemberAgentIsEmpty() throws SQLException {
        final Agent outsider = fx.agent( "outsider", List.of( "implementer" ) );
        final Item item = fx.ready( "x" );
        assertTrue( claims.claim( item, Actor.agent( outsider.id(), outsider.name() ), HOUR ).isEmpty() );
        assertEquals( item, fx.reload( item ) );
    }

    @Test
    void claimByViewerAgentIsEmpty() throws SQLException {
        fx.store.putMember( fx.plat.id(), String.valueOf( fx.a.id() ), "agent", WorkRole.VIEWER,
            WorkClaimFixtures.LENA );
        final Item item = fx.ready( "x" );
        assertTrue( claims.claim( item, fx.actorA, HOUR ).isEmpty() );
        assertEquals( item, fx.reload( item ) );
    }

    @Test
    void holderRenewsThoughItemBecameBlocked() throws SQLException {
        final Item held = claims.claim( fx.ready( "x" ), fx.actorA, HOUR ).orElseThrow();
        final Item blocker = fx.create( fx.plat, "blocker", WorkStatus.IN_PROGRESS, 3, null, null, null );
        fx.store.addLink( held, "blocked_by", blocker.key(), WorkClaimFixtures.LENA );
        assertTrue( fx.store.isBlocked( held.id() ) );
        assertTrue( claims.claim( fx.reload( held ), fx.actorA, Duration.ofHours( 2 ) ).isPresent() );
        assertEquals( 1, fx.events( held, WorkEvent.CLAIM_RENEWED ).size() );
        // A fresh take of a blocked item is still refused.
        final Item other = fx.ready( "y" );
        fx.store.addLink( other, "blocked_by", blocker.key(), WorkClaimFixtures.LENA );
        assertTrue( claims.claim( other, fx.actorB, HOUR ).isEmpty() );
    }

    @Test
    void holderRenewsThoughItNoLongerServesTheQueue() throws SQLException {
        final Item held = claims.claim( fx.ready( "x" ), fx.actorA, HOUR ).orElseThrow();
        fx.store.updateAgent( fx.a.id(), "agent-a", "", "", List.of( "reviewer" ) );
        final Item again = claims.claim( fx.reload( held ), fx.actorA, Duration.ofHours( 2 ) ).orElseThrow();
        assertEquals( fx.a.id(), again.claimedByAgent() );
        assertEquals( 1, fx.events( held, WorkEvent.CLAIM_RENEWED ).size() );
        // Having left the queue it cannot take a new item from it.
        assertTrue( claims.claim( fx.ready( "y" ), fx.actorA, HOUR ).isEmpty() );
    }

    @Test
    void claimRejectsAMissingOrNonPositiveExpiry() throws SQLException {
        final Item item = fx.ready( "x" );
        for ( final Duration d : new Duration[] { null, Duration.ZERO, Duration.ofSeconds( -5 ) } ) {
            assertThrows( IllegalArgumentException.class, () -> claims.claim( item, fx.actorA, d ) );
        }
        assertEquals( item, fx.reload( item ) );
    }

    /** A FOR KEY SHARE lock on the item (what a child or comment insert takes) must not block an ending. */
    @Test
    void endingsAreNotBlockedByAnUncommittedReferenceToTheItem() throws Exception {
        final Item toRelease = claims.claim( fx.ready( "one" ), fx.actorA, HOUR ).orElseThrow();
        final Item toRetire = claims.claim( fx.ready( "two" ), fx.actorB, HOUR ).orElseThrow();
        try ( Connection c = fx.ds.getConnection() ) {
            c.setAutoCommit( false );
            try ( Statement s = c.createStatement() ) {
                s.execute( "INSERT INTO work_comment (item_id, author, body) VALUES (" + toRelease.id()
                    + ", 'x', 'y')" );
                s.execute( "INSERT INTO work_comment (item_id, author, body) VALUES (" + toRetire.id()
                    + ", 'x', 'y')" );
            }
            assertTrue( pool.submit( () -> claims.release( toRelease, fx.actorA, "n" ) )
                .get( 20, TimeUnit.SECONDS ).isPresent() );
            pool.submit( () -> {
                claims.retireAgent( fx.b.id(), Instant.now(), WorkClaimFixtures.LENA );
                return null;
            } ).get( 20, TimeUnit.SECONDS );
            c.rollback();
        }
        assertNull( fx.reload( toRelease ).claimedByAgent() );
        assertNull( fx.reload( toRetire ).claimedByAgent() );
    }

    @Test
    void claimWithAMismatchedProjectIdIsEmpty() throws Exception {
        final Project ops = fx.store.createProject( "OPS", "Operations", "" );
        fx.store.putMember( ops.id(), String.valueOf( fx.a.id() ), "agent", WorkRole.MEMBER, WorkClaimFixtures.LENA );
        final Item real = fx.ready( "in PLAT" );
        // a copy that names a project the agent belongs to, for an item that lives in another one
        final Item wrongCopy = new Item( real.id(), ops.id(), real.key(), real.level(), real.parentId(),
            real.productId(), real.kind(), real.title(), real.body(), real.status(), real.priority(), real.rank(),
            real.dueOn(), real.assignee(), real.reporter(), real.autonomy(), real.doneMeans(), real.allowDirect(),
            real.version(), real.createdAt(), real.updatedAt(), real.closedAt(), real.queue(), real.claimedByAgent(),
            real.claimedAt(), real.claimExpiresAt(), real.reporterAgentId(), null, null, null, null );
        assertTrue( claims.claim( wrongCopy, fx.actorA, HOUR ).isEmpty() );
        assertEquals( real, fx.reload( real ) );
        assertTrue( fx.events( real, WorkEvent.CLAIMED ).isEmpty() );
    }

    @Test
    void nonNumericAgentPrincipalIsWarnedAboutAndLeavesClaimsAlone() throws Exception {
        final Item held = claims.claim( fx.ready( "x" ), fx.actorA, HOUR ).orElseThrow();
        final String logger = JdbcWorkClaims.class.getName();
        final List< String > warnings = new CopyOnWriteArrayList<>();
        final var core = ( org.apache.logging.log4j.core.Logger ) LogManager.getLogger( logger );
        final AbstractAppender capture = new AbstractAppender( "claims-capture", null, null, true,
            Property.EMPTY_ARRAY ) {
            @Override
            public void append( final LogEvent e ) {
                if ( e.getLevel() == Level.WARN ) {
                    warnings.add( e.getMessage().getFormattedMessage() );
                }
            }
        };
        capture.start();
        final Level before = core.getLevel();
        Configurator.setLevel( logger, Level.WARN );
        core.addAppender( capture );
        try {
            fx.store.putMember( fx.plat.id(), "not-an-id", "agent", WorkRole.MEMBER, WorkClaimFixtures.LENA );
            fx.store.putMember( fx.plat.id(), "not-an-id", "agent", WorkRole.VIEWER, WorkClaimFixtures.LENA );
            fx.store.removeMember( fx.plat.id(), "not-an-id", "agent", WorkClaimFixtures.LENA );
        } finally {
            core.removeAppender( capture );
            capture.stop();
            Configurator.setLevel( logger, before );
        }
        assertEquals( 2, warnings.size(), warnings.toString() );
        assertTrue( warnings.get( 0 ).contains( String.valueOf( fx.plat.id() ) ) );
        assertTrue( warnings.get( 0 ).contains( "not-an-id" ) );
        assertEquals( held, fx.reload( held ) );
        assertTrue( fx.events( held, WorkEvent.RELEASED ).isEmpty() );
    }

    // ---- I1: archiving and claiming are serialised on the project row

    private static final Actor ARCHIVER = Actor.human( "root", java.util.Set.of(), true );

    private static void assertArchived( final Throwable t ) {
        final Throwable cause = t instanceof java.util.concurrent.ExecutionException ? t.getCause() : t;
        assertTrue( cause instanceof WorkConflictException, "an archived refusal, got " + cause );
        assertEquals( WorkConflictException.ARCHIVED, ( ( WorkConflictException ) cause ).reason() );
    }

    @Test
    void aClaimInAnArchivedProjectIsRefusedAsArchivedAndWritesNothing() throws Exception {
        final Item item = fx.ready( "x" );
        WorkAgentFixtures.exec( fx.ds, "UPDATE work_project SET archived_at = now() WHERE id = " + fx.plat.id() );
        final long events = WorkAgentFixtures.scalar( fx.ds, "SELECT COUNT(*) FROM work_event" );
        assertArchived( assertThrows( WorkConflictException.class, () -> claims.claim( item, fx.actorA, HOUR ) ) );
        assertEquals( item, fx.reload( item ) );
        assertEquals( events, WorkAgentFixtures.scalar( fx.ds, "SELECT COUNT(*) FROM work_event" ) );
    }

    @Test
    void aRenewalBlockedBehindTheArchiveDoesNotRetakeTheItem() throws Exception {
        final Item held = claims.claim( fx.ready( "x" ), fx.actorA, HOUR ).orElseThrow();
        final CommitGate gate = new CommitGate( fx.ds );
        final Future< Integer > archive = pool.submit( () -> new JdbcWorkStore( gate ).archiveProject( fx.plat.id(),
            Instant.now(), ARCHIVER ) );
        assertTrue( gate.reached.await( 30, TimeUnit.SECONDS ) );
        final Future< Optional< Item > > renewal = pool.submit( () -> claims.claim( held, fx.actorA, HOUR ) );
        WorkClaimFixtures.awaitBlockedBy( fx.ds, gate.pid );
        gate.open();
        assertEquals( 1, archive.get( 30, TimeUnit.SECONDS ) );
        assertArchived( assertThrows( java.util.concurrent.ExecutionException.class,
            () -> renewal.get( 30, TimeUnit.SECONDS ) ) );
        assertNull( fx.reload( held ).claimedByAgent(), "the archived project holds no claim" );
        assertEquals( List.of( WorkEvent.CLAIMED, WorkEvent.RELEASED ), claimTypes( held ) );
    }

    @Test
    void aFreshTakeThatLandsBeforeTheArchiveIsReleasedByIt() throws Exception {
        final Item free = fx.ready( "x" );
        final CommitGate gate = new CommitGate( fx.ds );
        final Future< Optional< Item > > take = pool.submit( () -> new JdbcWorkStore( gate ).claims().claim( free,
            fx.actorA, HOUR ) );
        assertTrue( gate.reached.await( 30, TimeUnit.SECONDS ) );
        final Future< Integer > archive = pool.submit( () -> fx.store.archiveProject( fx.plat.id(), Instant.now(),
            ARCHIVER ) );
        WorkClaimFixtures.awaitBlockedBy( fx.ds, gate.pid );
        gate.open();
        assertTrue( take.get( 30, TimeUnit.SECONDS ).isPresent() );
        assertEquals( 1, archive.get( 30, TimeUnit.SECONDS ), "the archive waited for the take and released it" );
        assertNull( fx.reload( free ).claimedByAgent() );
        assertEquals( List.of( WorkEvent.CLAIMED, WorkEvent.RELEASED ), claimTypes( free ) );
    }

    @Test
    void aFreshTakeThatLandsAfterTheArchiveIsRefused() throws Exception {
        final Item free = fx.ready( "x" );
        final CommitGate gate = new CommitGate( fx.ds );
        final Future< Integer > archive = pool.submit( () -> new JdbcWorkStore( gate ).archiveProject( fx.plat.id(),
            Instant.now(), ARCHIVER ) );
        assertTrue( gate.reached.await( 30, TimeUnit.SECONDS ) );
        final Future< Optional< Item > > take = pool.submit( () -> claims.claim( free, fx.actorA, HOUR ) );
        WorkClaimFixtures.awaitBlockedBy( fx.ds, gate.pid );
        gate.open();
        assertEquals( 0, archive.get( 30, TimeUnit.SECONDS ) );
        assertArchived( assertThrows( java.util.concurrent.ExecutionException.class,
            () -> take.get( 30, TimeUnit.SECONDS ) ) );
        assertNull( fx.reload( free ).claimedByAgent() );
        assertTrue( claimTypes( free ).isEmpty() );
    }
}
