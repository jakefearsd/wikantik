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

import com.wikantik.jdbc.testing.PostgresTestDb;
import com.wikantik.jdbc.testing.RequiresPostgres;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import javax.sql.DataSource;
import java.sql.Connection;
import java.sql.Statement;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Covers {@link JdbcAuditRepository#chainHead()}, {@link JdbcAuditRepository#query(AuditQuery)}
 * and {@link JdbcAuditRepository#verifyChain(long, long)} — none of which {@link JdbcAuditRepositoryTest}
 * (a least-privilege-role regression test focused on {@code append}) exercises.
 */
@RequiresPostgres
class JdbcAuditRepositoryQueryTest {

    private DataSource dataSource;
    private JdbcAuditRepository repo;

    @BeforeEach
    void setUp() {
        dataSource = PostgresTestDb.createDataSource();
        PostgresTestDb.truncate( "audit_log" );
        repo = new JdbcAuditRepository( dataSource );
    }

    private static AuditEntry entry( final AuditCategory cat, final String eventType,
                                      final String actorId, final String targetId,
                                      final AuditOutcome outcome ) {
        return AuditEntry.builder()
                .eventTime( Instant.now().truncatedTo( ChronoUnit.MICROS ) )
                .category( cat )
                .eventType( eventType )
                .actorType( "TEST" )
                .actorId( actorId )
                .actorPrincipal( "tester" )
                .targetType( "page" )
                .targetId( targetId )
                .outcome( outcome )
                .detail( "{}" )
                .build();
    }

    @Test
    void chainHead_onEmptyTable_returnsGenesis() {
        final AuditRepository.ChainHead head = repo.chainHead();
        assertEquals( 0L, head.lastSeq() );
        assertEquals( AuditChainHasher.GENESIS_PREV_HASH, head.lastHash() );
    }

    @Test
    void chainHead_afterAppend_reflectsLastEntry() {
        repo.append( List.of(
                entry( AuditCategory.ADMIN, "e1", "alice", "PageA", AuditOutcome.SUCCESS ),
                entry( AuditCategory.ADMIN, "e2", "alice", "PageB", AuditOutcome.SUCCESS ) ) );
        final AuditRepository.ChainHead head = repo.chainHead();
        assertEquals( 2L, head.lastSeq() );
        assertNotEquals( AuditChainHasher.GENESIS_PREV_HASH, head.lastHash() );
    }

    @Test
    void query_filtersByActorCategoryEventTypeTargetAndOutcome() {
        repo.append( List.of(
                entry( AuditCategory.ADMIN, "login", "alice", "PageA", AuditOutcome.SUCCESS ),
                entry( AuditCategory.AUTHN, "login", "bob", "PageB", AuditOutcome.FAILURE ),
                entry( AuditCategory.ADMIN, "delete", "alice", "PageC", AuditOutcome.DENIED ) ) );

        final List< PersistedAuditEntry > byActor = repo.query(
                new AuditQuery( "alice", null, null, null, null, null, null, 1000, Long.MAX_VALUE ) );
        assertEquals( 2, byActor.size() );

        final List< PersistedAuditEntry > byCategory = repo.query(
                new AuditQuery( null, AuditCategory.AUTHN, null, null, null, null, null, 1000, Long.MAX_VALUE ) );
        assertEquals( 1, byCategory.size() );
        assertEquals( "bob", byCategory.get( 0 ).entry().actorId() );

        final List< PersistedAuditEntry > byEventType = repo.query(
                new AuditQuery( null, null, "delete", null, null, null, null, 1000, Long.MAX_VALUE ) );
        assertEquals( 1, byEventType.size() );

        final List< PersistedAuditEntry > byTarget = repo.query(
                new AuditQuery( null, null, null, "PageB", null, null, null, 1000, Long.MAX_VALUE ) );
        assertEquals( 1, byTarget.size() );

        final List< PersistedAuditEntry > byOutcome = repo.query(
                new AuditQuery( null, null, null, null, null, null, AuditOutcome.DENIED, 1000, Long.MAX_VALUE ) );
        assertEquals( 1, byOutcome.size() );
        assertEquals( "delete", byOutcome.get( 0 ).entry().eventType() );
    }

    @Test
    void query_respectsBeforeSeqAndLimitAndOrdersDescending() {
        repo.append( List.of(
                entry( AuditCategory.ADMIN, "e1", "alice", "A", AuditOutcome.SUCCESS ),
                entry( AuditCategory.ADMIN, "e2", "alice", "B", AuditOutcome.SUCCESS ),
                entry( AuditCategory.ADMIN, "e3", "alice", "C", AuditOutcome.SUCCESS ) ) );

        final List< PersistedAuditEntry > all = repo.query( AuditQuery.all() );
        assertEquals( 3, all.size() );
        assertEquals( "e3", all.get( 0 ).entry().eventType(), "must order seq DESC" );
        assertEquals( "e1", all.get( 2 ).entry().eventType() );

        final List< PersistedAuditEntry > beforeThird = repo.query(
                new AuditQuery( null, null, null, null, null, null, null, 1000, all.get( 0 ).seq() ) );
        assertEquals( 2, beforeThird.size() );

        final List< PersistedAuditEntry > limited = repo.query(
                new AuditQuery( null, null, null, null, null, null, null, 1, Long.MAX_VALUE ) );
        assertEquals( 1, limited.size() );
    }

    @Test
    void query_filtersByFromAndToCreatedAt() throws Exception {
        repo.append( List.of( entry( AuditCategory.ADMIN, "e1", "alice", "A", AuditOutcome.SUCCESS ) ) );
        final Instant past = Instant.now().minusSeconds( 3600 );
        final Instant future = Instant.now().plusSeconds( 3600 );

        final List< PersistedAuditEntry > inRange = repo.query(
                new AuditQuery( null, null, null, null, past, future, null, 1000, Long.MAX_VALUE ) );
        assertEquals( 1, inRange.size() );

        final List< PersistedAuditEntry > outOfRange = repo.query(
                new AuditQuery( null, null, null, null, future, null, null, 1000, Long.MAX_VALUE ) );
        assertEquals( 0, outOfRange.size() );

        final List< PersistedAuditEntry > beforePast = repo.query(
                new AuditQuery( null, null, null, null, null, past, null, 1000, Long.MAX_VALUE ) );
        assertEquals( 0, beforePast.size() );
    }

    @Test
    void verifyChain_onIntactChain_returnsEmpty() {
        repo.append( List.of(
                entry( AuditCategory.ADMIN, "e1", "alice", "A", AuditOutcome.SUCCESS ),
                entry( AuditCategory.ADMIN, "e2", "alice", "B", AuditOutcome.SUCCESS ),
                entry( AuditCategory.ADMIN, "e3", "alice", "C", AuditOutcome.SUCCESS ) ) );

        assertEquals( Optional.empty(), repo.verifyChain( 1L, 3L ) );
    }

    @Test
    void verifyChain_onEmptyRange_returnsEmpty() {
        assertEquals( Optional.empty(), repo.verifyChain( 1L, 100L ) );
    }

    @Test
    void verifyChain_detectsTamperedRow() throws Exception {
        repo.append( List.of(
                entry( AuditCategory.ADMIN, "e1", "alice", "A", AuditOutcome.SUCCESS ),
                entry( AuditCategory.ADMIN, "e2", "alice", "B", AuditOutcome.SUCCESS ),
                entry( AuditCategory.ADMIN, "e3", "alice", "C", AuditOutcome.SUCCESS ) ) );

        // Corrupt the middle row's event_type in place — the stored row_hash was computed over the
        // original value, so recomputing the hash from the (now-different) row content must diverge.
        try ( Connection c = dataSource.getConnection(); Statement st = c.createStatement() ) {
            st.execute( "UPDATE audit_log SET event_type = 'tampered' WHERE seq = 2" );
        }

        final Optional< Long > broken = repo.verifyChain( 1L, 3L );
        assertEquals( Optional.of( 2L ), broken );
    }
}
