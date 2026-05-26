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

import com.wikantik.api.comments.Comment;
import com.wikantik.api.comments.CommentThread;
import com.wikantik.api.comments.TextQuoteSelector;
import org.h2.jdbcx.JdbcDataSource;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import javax.sql.DataSource;
import java.sql.Connection;
import java.sql.Statement;
import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;

class CommentStoreTest {

    private DataSource ds;
    private CommentStore store;

    @BeforeEach
    void setUp() throws Exception {
        final JdbcDataSource h2 = new JdbcDataSource();
        h2.setURL( "jdbc:h2:mem:cstore;MODE=PostgreSQL;DB_CLOSE_DELAY=-1" );
        this.ds = h2;
        try ( Connection c = ds.getConnection(); Statement s = c.createStatement() ) {
            s.executeUpdate( """
                CREATE TABLE comment_threads (
                    id UUID PRIMARY KEY,
                    canonical_id TEXT NOT NULL,
                    anchor_exact TEXT NOT NULL,
                    anchor_prefix TEXT,
                    anchor_suffix TEXT,
                    status TEXT NOT NULL DEFAULT 'open',
                    created_by TEXT NOT NULL,
                    created_at TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT CURRENT_TIMESTAMP,
                    resolved_by TEXT,
                    resolved_at TIMESTAMP WITH TIME ZONE
                )""" );
            s.executeUpdate( """
                CREATE TABLE comments (
                    id UUID PRIMARY KEY,
                    thread_id UUID NOT NULL REFERENCES comment_threads(id) ON DELETE CASCADE,
                    author TEXT NOT NULL,
                    body TEXT NOT NULL,
                    created_at TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT CURRENT_TIMESTAMP,
                    edited_at TIMESTAMP WITH TIME ZONE
                )""" );
        }
        this.store = new CommentStore( ds );
    }

    @AfterEach
    void tearDown() throws Exception {
        try ( Connection c = ds.getConnection(); Statement s = c.createStatement() ) {
            s.executeUpdate( "DROP TABLE comments" );
            s.executeUpdate( "DROP TABLE comment_threads" );
        }
    }

    @Test
    void createThread_persists_thread_with_first_comment() {
        final CommentThread t = store.createThread( "CID1",
                new TextQuoteSelector( "hello", "say ", " world" ), "alice", "what does this mean?" );

        assertNotNull( t.id() );
        assertEquals( "CID1", t.canonicalId() );
        assertEquals( CommentThread.OPEN, t.status() );
        assertEquals( "hello", t.anchor().exact() );
        assertEquals( 1, t.comments().size() );
        assertEquals( "alice", t.comments().get( 0 ).author() );
        assertEquals( "what does this mean?", t.comments().get( 0 ).body() );
    }

    @Test
    void listByCanonicalId_returns_threads_with_comments_ordered() {
        final CommentThread t = store.createThread( "CID1",
                new TextQuoteSelector( "x", null, null ), "alice", "first" );
        store.addComment( t.id(), "bob", "reply" );

        final List< CommentThread > threads = store.listByCanonicalId( "CID1", "all" );
        assertEquals( 1, threads.size() );
        assertEquals( 2, threads.get( 0 ).comments().size() );
        assertEquals( "first", threads.get( 0 ).comments().get( 0 ).body() );
        assertEquals( "reply", threads.get( 0 ).comments().get( 1 ).body() );
    }

    @Test
    void listByCanonicalId_filters_by_status() {
        final CommentThread open = store.createThread( "CID1",
                new TextQuoteSelector( "a", null, null ), "alice", "open one" );
        final CommentThread done = store.createThread( "CID1",
                new TextQuoteSelector( "b", null, null ), "alice", "to resolve" );
        store.resolve( done.id(), "bob" );

        assertEquals( 1, store.listByCanonicalId( "CID1", "open" ).size() );
        assertEquals( 1, store.listByCanonicalId( "CID1", "resolved" ).size() );
        assertEquals( 2, store.listByCanonicalId( "CID1", "all" ).size() );
        assertEquals( open.id(), store.listByCanonicalId( "CID1", "open" ).get( 0 ).id() );
    }

    @Test
    void resolve_then_reopen_toggles_status() {
        final CommentThread t = store.createThread( "CID1",
                new TextQuoteSelector( "a", null, null ), "alice", "x" );
        assertTrue( store.resolve( t.id(), "bob" ) );
        assertEquals( CommentThread.RESOLVED, store.findThread( t.id() ).orElseThrow().status() );
        assertTrue( store.reopen( t.id() ) );
        final CommentThread reopened = store.findThread( t.id() ).orElseThrow();
        assertEquals( CommentThread.OPEN, reopened.status() );
        assertNull( reopened.resolvedBy() );
    }

    @Test
    void editComment_sets_body_and_edited_at() {
        final CommentThread t = store.createThread( "CID1",
                new TextQuoteSelector( "a", null, null ), "alice", "typo" );
        final UUID cid = t.comments().get( 0 ).id();
        final Comment edited = store.editComment( cid, "fixed" ).orElseThrow();
        assertEquals( "fixed", edited.body() );
        assertNotNull( edited.editedAt() );
    }

    @Test
    void deleteComment_removes_single_comment() {
        final CommentThread t = store.createThread( "CID1",
                new TextQuoteSelector( "a", null, null ), "alice", "first" );
        final Comment reply = store.addComment( t.id(), "bob", "reply" );
        assertTrue( store.deleteComment( reply.id() ) );
        assertEquals( 1, store.listByCanonicalId( "CID1", "all" ).get( 0 ).comments().size() );
    }

    @Test
    void deleteThread_cascades_comments() {
        final CommentThread t = store.createThread( "CID1",
                new TextQuoteSelector( "a", null, null ), "alice", "first" );
        assertTrue( store.deleteThread( t.id() ) );
        assertTrue( store.listByCanonicalId( "CID1", "all" ).isEmpty() );
    }

    @Test
    void findComment_returns_author_for_permission_checks() {
        final CommentThread t = store.createThread( "CID1",
                new TextQuoteSelector( "a", null, null ), "alice", "first" );
        final UUID cid = t.comments().get( 0 ).id();
        assertEquals( "alice", store.findComment( cid ).orElseThrow().author() );
    }
}
