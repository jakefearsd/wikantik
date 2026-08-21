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
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import javax.sql.DataSource;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.SQLException;
import java.util.Optional;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Guards the transaction boundary of the multi-statement comment writes.
 *
 * <p>Each of {@code createThread}, {@code addComment} and {@code editComment} opens a
 * manual transaction and then calls into {@link MentionService} inside it. The
 * hand-copied skeleton in all three caught only {@link SQLException}, so an unchecked
 * exception from the mention work skipped the rollback and fell through to
 * {@code finally { c.setAutoCommit( true ) }} — and per the JDBC contract, switching
 * auto-commit back on during a transaction COMMITS it. A failed comment write therefore
 * committed its partial rows (thread and comment inserted, mentions missing) instead of
 * discarding them.
 *
 * <p>These tests assert the invariant directly: if anything at all fails mid-write, the
 * transaction is rolled back and never committed.
 */
class CommentStoreTransactionTest {

    private DataSource ds;
    private Connection conn;
    private MentionService mentions;
    private CommentStore store;

    @BeforeEach
    void setUp() throws SQLException {
        ds = mock( DataSource.class );
        conn = mock( Connection.class );
        final PreparedStatement ps = mock( PreparedStatement.class );

        when( ds.getConnection() ).thenReturn( conn );
        when( conn.prepareStatement( anyString() ) ).thenReturn( ps );
        when( ps.executeUpdate() ).thenReturn( 1 );

        mentions = mock( MentionService.class );
        store = new CommentStore( ds );
    }

    private static TextQuoteSelector anchor() {
        return new TextQuoteSelector( "exact", "prefix", "suffix" );
    }

    /**
     * The defect, on the thread-creation path: the thread row and its first comment are
     * already inserted when the mention work blows up unchecked.
     */
    @Test
    void createThread_uncheckedMentionFailure_rollsBackInsteadOfCommitting() throws SQLException {
        doThrow( new IllegalStateException( "mention indexing blew up" ) )
            .when( mentions ).recordCreate( any(), any(), anyString(), anyString(), any() );

        assertThrows( RuntimeException.class, () ->
            store.createThread( "01ABC", anchor(), "alice", "hello", mentions, Optional.empty() ),
            "A failed create must not report success." );

        verify( conn ).rollback();
        verify( conn, never() ).commit();
    }

    @Test
    void addComment_uncheckedMentionFailure_rollsBackInsteadOfCommitting() throws SQLException {
        doThrow( new IllegalStateException( "mention indexing blew up" ) )
            .when( mentions ).recordReply( any(), any(), anyString(), anyString() );

        assertThrows( RuntimeException.class, () ->
            store.addComment( UUID.randomUUID(), "alice", "hello", mentions ),
            "A failed reply must not report success." );

        verify( conn ).rollback();
        verify( conn, never() ).commit();
    }

    @Test
    void editComment_uncheckedMentionFailure_rollsBackInsteadOfCommitting() throws SQLException {
        doThrow( new IllegalStateException( "mention indexing blew up" ) )
            .when( mentions ).recordEdit( any(), any(), anyString(), anyString(), anyString() );

        assertThrows( RuntimeException.class, () ->
            store.editComment( UUID.randomUUID(), "old", "new", "alice", mentions ),
            "A failed edit must not report success." );

        verify( conn ).rollback();
        verify( conn, never() ).commit();
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

        verify( conn ).rollback();
        verify( conn, never() ).commit();
    }

    /**
     * Auto-commit must be restored before the connection goes back to the pool, whichever
     * way the write ended — a pooled connection left in manual-commit mode silently breaks
     * whatever borrows it next.
     */
    @Test
    void uncheckedFailure_stillRestoresAutoCommitForThePool() throws SQLException {
        doThrow( new IllegalStateException( "mention indexing blew up" ) )
            .when( mentions ).recordCreate( any(), any(), anyString(), anyString(), any() );

        assertThrows( RuntimeException.class, () ->
            store.createThread( "01ABC", anchor(), "alice", "hello", mentions, Optional.empty() ) );

        verify( conn ).setAutoCommit( true );
    }
}
