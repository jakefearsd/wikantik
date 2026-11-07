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
package com.wikantik.collab.adapter;

import com.wikantik.collab.model.SessionRecord;
import com.wikantik.collab.model.Snapshot;
import com.wikantik.collab.model.StoredMetaOp;
import com.wikantik.collab.model.StoredUpdate;
import com.wikantik.collab.text.ChangeSet;
import com.wikantik.jdbc.Jdbc;
import com.wikantik.jdbc.testing.FaultInjectingDataSource;
import com.wikantik.jdbc.testing.MigrationApplier;
import com.wikantik.jdbc.testing.PostgresTestDb;
import com.wikantik.jdbc.testing.RequiresPostgres;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import javax.sql.DataSource;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.Statement;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Multi-statement writes of {@link JdbcCollabStore} are all-or-nothing, and V066 is safe to re-apply. */
@RequiresPostgres
class JdbcCollabStoreRollbackTest {

    private static final Instant T0 = Instant.parse( "2026-01-01T00:00:00Z" );
    private static final ChangeSet CS = ChangeSet.empty( 0 );

    private DataSource ds;
    private JdbcCollabStore clean;
    private FaultInjectingDataSource faulty;
    private JdbcCollabStore faultyStore;
    private SessionRecord s;

    @BeforeEach
    void setUp() {
        ds = PostgresTestDb.createDataSource();
        PostgresTestDb.truncate( "collab_session", "page_version_contributor" );
        clean =new JdbcCollabStore( new Jdbc( ds ) );
        faulty = new FaultInjectingDataSource( ds );
        faultyStore = new JdbcCollabStore( new Jdbc( faulty ) );
        s = new SessionRecord( UUID.randomUUID(), "PageA", SessionRecord.ACTIVE, 3, "base", Map.of(), "hash-3", 0, 0L,
            false, null, null, null, null, null, "alice", T0, null );
        clean.createSession( s );
    }

    private static StoredUpdate upd( final int version ) {
        return new StoredUpdate( version, StoredUpdate.TEXT, "c1", "alice", CS );
    }

    @Test
    void appendUpdatesRollsBackOnTheSecondStatement() {
        // statements: 1 lock, 2 first insert, 3 second insert, 4 head bump
        faulty.failOn( 3, new RuntimeException( "boom" ) );
        assertThrows( RuntimeException.class, () -> faultyStore.appendUpdates( s.id(), List.of( upd( 1 ), upd( 2 ) ) ) );
        assertTrue( clean.updatesSince( s.id(), 0 ).isEmpty(), "the first insert must have been rolled back" );
        assertEquals( 0, clean.session( s.id() ).orElseThrow().headVersion() );
        assertTrue( faulty.rollbacks() >= 1, "the transaction must be rolled back explicitly" );
    }

    @Test
    void appendUpdatesRollsBackWhenTheHeadBumpFails() {
        faulty.failOn( 4, new RuntimeException( "boom" ) );
        assertThrows( RuntimeException.class, () -> faultyStore.appendUpdates( s.id(), List.of( upd( 1 ), upd( 2 ) ) ) );
        assertTrue( clean.updatesSince( s.id(), 0 ).isEmpty() );
        assertEquals( 0, clean.session( s.id() ).orElseThrow().headVersion() );
    }

    @Test
    void appendMetaOpsRollsBackOnTheSecondStatement() {
        faulty.failOn( 3, new RuntimeException( "boom" ) );
        assertThrows( RuntimeException.class, () -> faultyStore.appendMetaOps( s.id(),
            List.of( new StoredMetaOp( 1, "alice", "title", "a" ), new StoredMetaOp( 2, "alice", "title", "b" ) ) ) );
        assertTrue( clean.metaOpsSince( s.id(), 0 ).isEmpty() );
        assertEquals( 0L, clean.session( s.id() ).orElseThrow().metaSeq() );
    }

    @Test
    void appendBatchRollsBackOnTheMetaInsert() {
        // statements: 1 lock, 2 update insert, 3 head bump, 4 meta-op insert, 5 meta-seq bump
        faulty.failOn( 4, new RuntimeException( "boom" ) );
        assertThrows( RuntimeException.class, () -> faultyStore.appendBatch( s.id(), List.of( upd( 1 ) ),
            List.of( new StoredMetaOp( 1, "alice", "tags", List.of( "x" ) ) ) ) );
        assertTrue( clean.updatesSince( s.id(), 0 ).isEmpty(), "the text insert must have been rolled back" );
        assertTrue( clean.metaOpsSince( s.id(), 0 ).isEmpty() );
        final SessionRecord after = clean.session( s.id() ).orElseThrow();
        assertEquals( 0, after.headVersion(), "the head bump must have been rolled back" );
        assertEquals( 0L, after.metaSeq() );
        assertTrue( faulty.rollbacks() >= 1, "the transaction must be rolled back explicitly" );
    }

    @Test
    void appendBatchRollsBackWhicheverStatementFails() {
        for( int failing = 2; failing <= 5; failing++ ) {
            faulty.reset();
            faulty.failOn( failing, new RuntimeException( "boom at " + failing ) );
            assertThrows( RuntimeException.class, () -> faultyStore.appendBatch( s.id(), List.of( upd( 1 ) ),
                List.of( new StoredMetaOp( 1, "alice", "title", "t" ) ) ), "statement " + failing );
            assertTrue( clean.updatesSince( s.id(), 0 ).isEmpty(), "statement " + failing );
            assertTrue( clean.metaOpsSince( s.id(), 0 ).isEmpty(), "statement " + failing );
            assertEquals( 0, clean.session( s.id() ).orElseThrow().headVersion(), "statement " + failing );
            assertEquals( 0L, clean.session( s.id() ).orElseThrow().metaSeq(), "statement " + failing );
        }
    }

    @Test
    void appendWithSnapshotRollsBackWhicheverStatementFails() {
        final Snapshot snap = new Snapshot( 1, 1L, 3, "reset", Map.of(), CS );
        // 1 lock, 2 update insert, 3 head bump, 4 meta-op insert, 5 meta-seq bump, 6 snapshot upsert
        for( int failing = 2; failing <= 6; failing++ ) {
            faulty.reset();
            faulty.failOn( failing, new RuntimeException( "boom at " + failing ) );
            assertThrows( RuntimeException.class, () -> faultyStore.appendWithSnapshot( s.id(),
                List.of( new StoredUpdate( 1, StoredUpdate.SERVER, "server", null, CS ) ),
                List.of( new StoredMetaOp( 1, null, "title", "t" ) ), snap ), "statement " + failing );
            assertTrue( clean.updatesSince( s.id(), 0 ).isEmpty(), "statement " + failing );
            assertTrue( clean.metaOpsSince( s.id(), 0 ).isEmpty(), "statement " + failing );
            assertTrue( clean.latestSnapshot( s.id() ).isEmpty(), "statement " + failing );
            assertEquals( 0, clean.session( s.id() ).orElseThrow().headVersion(), "statement " + failing );
            assertEquals( 0L, clean.session( s.id() ).orElseThrow().metaSeq(), "statement " + failing );
            assertTrue( faulty.rollbacks() >= 1, "the transaction must be rolled back explicitly" );
        }
    }

    @Test
    void commitBaseChangeRollsBackWhicheverStatementFails() {
        final Snapshot snap = new Snapshot( 1, 0L, 4, "base@4", Map.of(), CS );
        final SessionRecord updated = s.withBase( 4, "base@4", Map.of(), "hash-4" );
        final SessionRecord before = clean.session( s.id() ).orElseThrow();
        // 1 lock, 2 server-update insert, 3 head bump, 4 snapshot upsert, 5 session fields
        for( int failing = 2; failing <= 5; failing++ ) {
            faulty.reset();
            faulty.failOn( failing, new RuntimeException( "boom at " + failing ) );
            assertThrows( RuntimeException.class, () -> faultyStore.commitBaseChange( s.id(),
                List.of( new StoredUpdate( 1, StoredUpdate.SERVER, "server", null, CS ) ), snap, updated ),
                "statement " + failing );
            assertEquals( before, clean.session( s.id() ).orElseThrow(), "statement " + failing );
            assertTrue( clean.updatesSince( s.id(), 0 ).isEmpty(), "statement " + failing );
            assertTrue( clean.latestSnapshot( s.id() ).isEmpty(), "statement " + failing );
        }
    }

    @Test
    void truncateRollsBackWhenTheSnapshotDeleteFails() {
        clean.appendUpdates( s.id(), List.of( upd( 1 ), upd( 2 ), upd( 3 ) ) );
        clean.writeSnapshot( s.id(), new Snapshot( 1, 0L, 3, "b1", Map.of(), CS ) );
        clean.writeSnapshot( s.id(), new Snapshot( 2, 0L, 3, "b2", Map.of(), CS ) );
        // 1 lock, 2 floor lookup, 3 delete updates, 4 delete snapshots
        faulty.failOn( 4, new RuntimeException( "boom" ) );
        assertThrows( RuntimeException.class, () -> faultyStore.truncateUpdatesBelow( s.id(), 2 ) );
        assertEquals( 3, clean.updatesSince( s.id(), 0 ).size(), "the update delete must have been rolled back" );
    }

    @Test
    void aMigrationReapplyIsANoOp() throws Exception {
        clean.appendUpdates( s.id(), List.of( upd( 1 ) ) );
        final String sql = MigrationApplier.substituteAppUser( Files.readString(
            MigrationApplier.findRepoRoot().resolve( "bin/db/migrations/V066__collab_sessions.sql" ) ), "test" );
        for( int i = 0; i < 2; i++ ) {
            try( Connection c = ds.getConnection(); Statement st = c.createStatement() ) {
                st.execute( sql );
            }
        }
        assertEquals( 1, clean.updatesSince( s.id(), 0 ).size(), "re-applying must not touch existing data" );
        assertEquals( 1, clean.session( s.id() ).orElseThrow().headVersion() );
    }

    @Test
    void recordContributorsRollsBackOnTheInsert() {
        clean.recordContributors( "cid-1", 1, Set.of( "alice" ), true );
        clean.recordContributors( "cid-1", 2, Set.of( "bob" ), false );
        final Map< Integer, List< String > > before = clean.contributorsByVersion( "cid-1" );
        // created: 1 delete every row of the canonical id, 2 first insert, 3 second insert
        for( int failing = 2; failing <= 3; failing++ ) {
            faulty.reset();
            faulty.failOn( failing, new RuntimeException( "boom at " + failing ) );
            assertThrows( RuntimeException.class, () -> faultyStore.recordContributors( "cid-1", 1,
                Set.of( "carol", "dave" ), true ), "statement " + failing );
            assertEquals( before, clean.contributorsByVersion( "cid-1" ), "the old rows survive, statement " + failing );
            assertTrue( faulty.rollbacks() >= 1, "the transaction must be rolled back explicitly" );
        }
        // not created: 1 delete the version's rows, 2 first insert
        faulty.reset();
        faulty.failOn( 2, new RuntimeException( "boom" ) );
        assertThrows( RuntimeException.class, () -> faultyStore.recordContributors( "cid-1", 2, Set.of( "erin" ), false ) );
        assertEquals( before, clean.contributorsByVersion( "cid-1" ) );
    }

    @Test
    void v067IsANoOpOnReapply() throws Exception {
        // a snapshot row written the pre-V067 way, without the contributors column
        new Jdbc( ds ).update( "INSERT INTO collab_snapshot ( session_id, version, meta_seq, base_page_version, body, meta,"
            + " local_changes ) VALUES ( ?, 0, 0, 3, 'old', '{}'::jsonb, '[]'::jsonb )", ps -> ps.setObject( 1, s.id() ) );
        clean.recordContributors( "cid-1", 1, Set.of( "alice" ), true );
        final String sql = MigrationApplier.substituteAppUser( Files.readString(
            MigrationApplier.findRepoRoot().resolve( "bin/db/migrations/V067__collab_snapshot_contributors.sql" ) ), "test" );
        for( int i = 0; i < 2; i++ ) {
            new Jdbc( ds ).execute( sql );
        }
        final Jdbc jdbc = new Jdbc( ds );
        assertEquals( 1, jdbc.queryOne( "SELECT count(*) FROM information_schema.columns WHERE table_name = 'collab_snapshot'"
            + " AND column_name = 'contributors'", ps -> { }, rs -> rs.getInt( 1 ) ).orElseThrow(), "the column exists once" );
        assertEquals( "[]", jdbc.queryOne( "SELECT contributors::text FROM collab_snapshot WHERE session_id = ?",
            ps -> ps.setObject( 1, s.id() ), rs -> rs.getString( 1 ) ).orElseThrow(), "an old row reads []" );
        assertTrue( clean.latestSnapshot( s.id() ).orElseThrow().contributors().isEmpty() );
        assertEquals( Map.of( 1, List.of( "alice" ) ), clean.contributorsByVersion( "cid-1" ),
            "re-applying must not touch existing data" );
    }

    @Test
    void v068IsANoOpOnReapply() throws Exception {
        clean.updateSession( s.withPending( 4, 3, "alice" ).withPublishSaved( 4, "cid-1" ) );
        final String sql = MigrationApplier.substituteAppUser( Files.readString(
            MigrationApplier.findRepoRoot().resolve( "bin/db/migrations/V068__collab_publish_saved_version.sql" ) ),
            "test" );
        for( int i = 0; i < 2; i++ ) {
            new Jdbc( ds ).execute( sql );
        }
        final Jdbc jdbc = new Jdbc( ds );
        assertEquals( 2, jdbc.queryOne( "SELECT count(*) FROM information_schema.columns WHERE table_name = 'collab_session'"
            + " AND column_name IN ( 'pending_publish_saved_version', 'pending_publish_canonical_id' )", ps -> { },
            rs -> rs.getInt( 1 ) ).orElseThrow(), "each column exists once" );
        final SessionRecord back = clean.session( s.id() ).orElseThrow();
        assertEquals( 4, back.pendingPublishSavedVersion(), "re-applying must not touch existing data" );
        assertEquals( "cid-1", back.pendingPublishCanonicalId() );
    }

    @Test
    void metaRoundTripsThroughJsonWithNullsAndNestedValues() {
        final Map< String, Object > meta = new LinkedHashMap<>();
        meta.put( "tags", List.of( "a", "b" ) );
        meta.put( "empty", null );
        meta.put( "n", 4 );
        meta.put( "x", 1.5 );
        meta.put( "nested", Map.of( "k", true ) );
        final SessionRecord withMeta = new SessionRecord( UUID.randomUUID(), "PageM", SessionRecord.ACTIVE, 1, "b", meta,
            "h", 0, 0L, false, null, null, null, null, null, "alice", T0, T0 );
        clean.createSession( withMeta );
        final Map< String, Object > back = clean.session( withMeta.id() ).orElseThrow().baseMeta();
        assertEquals( List.of( "a", "b" ), back.get( "tags" ) );
        assertTrue( back.containsKey( "empty" ) );
        assertNull( back.get( "empty" ) );
        assertEquals( 4L, back.get( "n" ), "integral numbers come back as Long" );
        assertEquals( 1.5, back.get( "x" ) );
        assertEquals( Map.of( "k", true ), back.get( "nested" ) );
        assertNull( clean.session( s.id() ).orElseThrow().lastEditAt() );
    }

    @Test
    void aNullMetaOpValueAndAStructuredOneRoundTrip() {
        final List< StoredMetaOp > ops = new ArrayList<>();
        ops.add( new StoredMetaOp( 1, null, "summary", null ) );
        ops.add( new StoredMetaOp( 2, "alice", "tags", List.of( "x" ) ) );
        clean.appendMetaOps( s.id(), ops );
        assertEquals( ops, clean.metaOpsSince( s.id(), 0 ) );
    }
}
