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
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;

import javax.sql.DataSource;
import java.sql.Connection;
import java.sql.SQLException;
import java.util.Optional;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

/**
 * Drives every {@link SQLException} catch / rollback path in {@link CommentStore}
 * by injecting a {@link DataSource} that fails at connection-acquisition (for the
 * simple catch paths) or mid-transaction (for the createThread rollback path).
 */
class CommentStoreErrorBranchTest {

    /** A DataSource whose getConnection() always throws — exercises the outer catch in every method. */
    private static DataSource failingDataSource() throws SQLException {
        final DataSource ds = mock( DataSource.class );
        when( ds.getConnection() ).thenThrow( new SQLException( "boom" ) );
        return ds;
    }

    private static final TextQuoteSelector ANCHOR = new TextQuoteSelector( "x", null, null );

    /** Mention service that never executes (every CommentStore failure path here
     *  fails BEFORE we get to the mention call, so the service is unused). */
    private static MentionService unusedMentions() {
        return new MentionService( mock( DataSource.class ), s -> false );
    }

    @Test
    void createThread_outerCatch_wrapsInRuntimeException() throws SQLException {
        final CommentStore store = new CommentStore( failingDataSource() );
        final RuntimeException ex = assertThrows( RuntimeException.class,
                () -> store.createThread( "CID1", ANCHOR, "alice", "body",
                        unusedMentions(), Optional.empty() ) );
        assertTrue( ex.getCause() instanceof SQLException );
    }

    @Test
    void createThread_innerFailure_rollsBackAndRethrowsWrapped() throws SQLException {
        // Connection that can begin a transaction but fails on prepareStatement, so the
        // inner try fails, c.rollback() runs, the SQLException is rethrown and caught by
        // the outer catch which wraps it in a RuntimeException.
        final Connection c = mock( Connection.class );
        doNothing().when( c ).setAutoCommit( anyBoolean() );
        when( c.prepareStatement( anyString() ) ).thenThrow( new SQLException( "insert failed" ) );
        final DataSource ds = mock( DataSource.class );
        when( ds.getConnection() ).thenReturn( c );

        final CommentStore store = new CommentStore( ds );
        final RuntimeException ex = assertThrows( RuntimeException.class,
                () -> store.createThread( "CID1", ANCHOR, "alice", "body",
                        unusedMentions(), Optional.empty() ) );
        assertTrue( ex.getCause() instanceof SQLException );
        verify( c ).rollback();
        verify( c ).setAutoCommit( true );
    }

    @Test
    void addComment_catch_wrapsInRuntimeException() throws SQLException {
        final CommentStore store = new CommentStore( failingDataSource() );
        assertThrows( RuntimeException.class,
                () -> store.addComment( UUID.randomUUID(), "alice", "body", unusedMentions() ) );
    }

    @Test
    void editComment_catch_wrapsInRuntimeException() throws SQLException {
        final CommentStore store = new CommentStore( failingDataSource() );
        assertThrows( RuntimeException.class,
                () -> store.editComment( UUID.randomUUID(), "old", "body", "alice", unusedMentions() ) );
    }

    @Test
    void resolve_catch_wrapsInRuntimeException() throws SQLException {
        final CommentStore store = new CommentStore( failingDataSource() );
        assertThrows( RuntimeException.class,
                () -> store.resolve( UUID.randomUUID(), "bob" ) );
    }

    @Test
    void reopen_catch_wrapsInRuntimeException() throws SQLException {
        final CommentStore store = new CommentStore( failingDataSource() );
        assertThrows( RuntimeException.class,
                () -> store.reopen( UUID.randomUUID() ) );
    }

    @Test
    void deleteComment_catch_wrapsInRuntimeException() throws SQLException {
        final CommentStore store = new CommentStore( failingDataSource() );
        assertThrows( RuntimeException.class,
                () -> store.deleteComment( UUID.randomUUID() ) );
    }

    @Test
    void deleteThread_catch_wrapsInRuntimeException() throws SQLException {
        final CommentStore store = new CommentStore( failingDataSource() );
        assertThrows( RuntimeException.class,
                () -> store.deleteThread( UUID.randomUUID() ) );
    }

    @Test
    void findThread_catch_returnsEmpty() throws SQLException {
        final CommentStore store = new CommentStore( failingDataSource() );
        assertTrue( store.findThread( UUID.randomUUID() ).isEmpty() );
    }

    @Test
    void findComment_catch_returnsEmpty() throws SQLException {
        final CommentStore store = new CommentStore( failingDataSource() );
        assertTrue( store.findComment( UUID.randomUUID() ).isEmpty() );
    }

    @Test
    void listByCanonicalId_catch_returnsEmptyList() throws SQLException {
        final CommentStore store = new CommentStore( failingDataSource() );
        assertTrue( store.listByCanonicalId( "CID1", "all" ).isEmpty() );
    }
}
