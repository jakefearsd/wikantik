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
package com.wikantik.comments;

import com.wikantik.api.comments.TextQuoteSelector;
import com.wikantik.comments.mentions.MentionService;
import com.wikantik.jdbc.testing.FaultInjectingDataSource;
import com.wikantik.jdbc.testing.PostgresTestDb;
import com.wikantik.jdbc.testing.RequiresPostgres;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import javax.sql.DataSource;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.Optional;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doNothing;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Guards the transaction boundary of the multi-statement comment writes.
 *
 * <p>Each of {@code createThread}, {@code addComment} and {@code editComment} opens a
 * transaction ({@link com.wikantik.jdbc.Jdbc#inTransaction}) and then calls into
 * {@link MentionService} inside it. Before 2.4.19 the hand-copied skeleton in all three
 * caught only {@link SQLException}, so an unchecked exception from the mention work skipped
 * the rollback and fell through to {@code finally { c.setAutoCommit( true ) }} — and per the
 * JDBC contract, switching auto-commit back on during a transaction COMMITS it. A failed
 * comment write therefore committed its partial rows (thread and comment inserted, mentions
 * missing) instead of discarding them.
 *
 * <p>These tests assert the invariant directly against a real Postgres transaction: if
 * anything at all fails mid-write, the transaction is rolled back and never committed, and no
 * partial rows are left behind.
 */
@RequiresPostgres
class CommentStoreTransactionTest {

    private DataSource ds;
    private MentionService mentions;
    private CommentStore store;

    @BeforeEach
    void setUp() {
        ds = PostgresTestDb.createDataSource();
        PostgresTestDb.truncate( "comment_mentions", "comments", "comment_threads" );
        mentions = mock( MentionService.class );
        store = new CommentStore( ds );
    }

    private static TextQuoteSelector anchor() {
        return new TextQuoteSelector( "exact", "prefix", "suffix" );
    }

    /** Inserts a thread + its first comment directly (bypassing CommentStore/MentionService,
     *  which the tests below need to control independently), returning the comment id. */
    private UUID seedThreadWithComment() throws SQLException {
        final UUID threadId = UUID.randomUUID();
        final UUID commentId = UUID.randomUUID();
        try ( Connection c = ds.getConnection() ) {
            try ( PreparedStatement ps = c.prepareStatement(
                    "INSERT INTO comment_threads (id, canonical_id, anchor_exact, status, created_by) " +
                    "VALUES (?, 'CID-SEED', 'seed-anchor', 'open', 'alice')" ) ) {
                ps.setObject( 1, threadId );
                ps.executeUpdate();
            }
            try ( PreparedStatement ps = c.prepareStatement(
                    "INSERT INTO comments (id, thread_id, author, body) VALUES (?, ?, 'alice', 'seed')" ) ) {
                ps.setObject( 1, commentId );
                ps.setObject( 2, threadId );
                ps.executeUpdate();
            }
        }
        return commentId;
    }

    private int countRows( final String table ) throws SQLException {
        try ( Connection c = ds.getConnection();
              PreparedStatement ps = c.prepareStatement( "SELECT COUNT(*) FROM " + table );
              ResultSet rs = ps.executeQuery() ) {
            rs.next();
            return rs.getInt( 1 );
        }
    }

    /**
     * The defect, on the thread-creation path: the thread row and its first comment would
     * already be inserted when the mention work blows up unchecked.
     */
    @Test
    void createThread_uncheckedMentionFailure_rollsBackInsteadOfCommitting() throws SQLException {
        doThrow( new IllegalStateException( "mention indexing blew up" ) )
            .when( mentions ).recordCreate( any(), any(), anyString(), anyString(), any() );

        assertThrows( RuntimeException.class, () ->
            store.createThread( "01ABC", anchor(), "alice", "hello", mentions, Optional.empty() ),
            "A failed create must not report success." );

        assertEquals( 0, countRows( "comment_threads" ), "no partial thread row must survive the rollback" );
        assertEquals( 0, countRows( "comments" ), "no partial comment row must survive the rollback" );
    }

    @Test
    void addComment_uncheckedMentionFailure_rollsBackInsteadOfCommitting() throws SQLException {
        final UUID seedCommentId = seedThreadWithComment();
        doThrow( new IllegalStateException( "mention indexing blew up" ) )
            .when( mentions ).recordReply( any(), any(), anyString(), anyString() );
        // addComment needs a real thread to attach the reply to (comments.thread_id is FK'd).
        final UUID threadId = threadIdOf( seedCommentId );

        assertThrows( RuntimeException.class, () ->
            store.addComment( threadId, "alice", "hello", mentions ),
            "A failed reply must not report success." );

        assertEquals( 1, countRows( "comments" ), "only the seed comment must remain; the reply must not persist" );
    }

    @Test
    void editComment_uncheckedMentionFailure_rollsBackInsteadOfCommitting() throws SQLException {
        final UUID commentId = seedThreadWithComment();
        doThrow( new IllegalStateException( "mention indexing blew up" ) )
            .when( mentions ).recordEdit( any(), any(), anyString(), anyString(), anyString() );

        assertThrows( RuntimeException.class, () ->
            store.editComment( commentId, "seed", "new", "alice", mentions ),
            "A failed edit must not report success." );

        assertEquals( "seed", bodyOf( commentId ), "the body update must not persist once the mention work fails" );
    }

    /**
     * The checked-exception path must keep behaving as it already did — this is the
     * regression guard for the case the original code did handle.
     */
    @Test
    void createThread_sqlFailure_stillRollsBack() throws SQLException {
        doThrow( new SQLException( "constraint violation" ) )
            .when( mentions ).recordCreate( any(), any(), anyString(), anyString(), any() );

        assertThrows( RuntimeException.class, () ->
            store.createThread( "01ABC", anchor(), "alice", "hello", mentions, Optional.empty() ) );

        assertEquals( 0, countRows( "comment_threads" ) );
    }

    /**
     * The standard Wave-1 rollback harness: fail at the JDBC level (not via a mocked
     * collaborator) partway through the transaction and prove no partial rows are visible —
     * the thread insert (1st statement) must not survive even though it succeeded before the
     * fault on the comment insert (2nd statement).
     */
    @Test
    void createThread_faultInjectedMidTransaction_rollsBackNoPartialRows() throws SQLException {
        final FaultInjectingDataSource faulty = new FaultInjectingDataSource( ds );
        faulty.failOn( 2, new RuntimeException( "simulated mid-transaction failure" ) );
        final CommentStore faultyStore = new CommentStore( faulty );
        final MentionService realMentions = new MentionService( faulty, login -> false );

        assertThrows( RuntimeException.class, () ->
            faultyStore.createThread( "CID-FAULT", anchor(), "alice", "hello", realMentions, Optional.empty() ) );

        assertEquals( 0, countRows( "comment_threads" ), "the thread insert must not survive the rollback" );
        assertEquals( 0, countRows( "comments" ) );
    }

    /**
     * Auto-commit must be restored before the connection goes back to the pool, whichever
     * way the write ended — a pooled connection left in manual-commit mode silently breaks
     * whatever borrows it next. Verified against a mocked Connection since this is about the
     * exact sequence of JDBC calls, not observable row state.
     */
    @Test
    void uncheckedFailure_stillRestoresAutoCommitForThePool() throws SQLException {
        final DataSource mockDs = mock( DataSource.class );
        final Connection conn = mock( Connection.class );
        final PreparedStatement ps = mock( PreparedStatement.class );
        when( mockDs.getConnection() ).thenReturn( conn );
        when( conn.getAutoCommit() ).thenReturn( true );
        doNothing().when( conn ).setAutoCommit( anyBoolean() );
        when( conn.prepareStatement( anyString() ) ).thenReturn( ps );
        when( ps.executeUpdate() ).thenReturn( 1 );
        final MentionService mockMentions = mock( MentionService.class );
        doThrow( new IllegalStateException( "mention indexing blew up" ) )
            .when( mockMentions ).recordCreate( any(), any(), anyString(), anyString(), any() );
        final CommentStore mockStore = new CommentStore( mockDs );

        assertThrows( RuntimeException.class, () ->
            mockStore.createThread( "01ABC", anchor(), "alice", "hello", mockMentions, Optional.empty() ) );

        verify( conn ).setAutoCommit( true );
    }

    private UUID threadIdOf( final UUID commentId ) throws SQLException {
        try ( Connection c = ds.getConnection();
              PreparedStatement ps = c.prepareStatement( "SELECT thread_id FROM comments WHERE id = ?" ) ) {
            ps.setObject( 1, commentId );
            try ( ResultSet rs = ps.executeQuery() ) {
                rs.next();
                return (UUID) rs.getObject( 1 );
            }
        }
    }

    private String bodyOf( final UUID commentId ) throws SQLException {
        try ( Connection c = ds.getConnection();
              PreparedStatement ps = c.prepareStatement( "SELECT body FROM comments WHERE id = ?" ) ) {
            ps.setObject( 1, commentId );
            try ( ResultSet rs = ps.executeQuery() ) {
                rs.next();
                return rs.getString( 1 );
            }
        }
    }
}
