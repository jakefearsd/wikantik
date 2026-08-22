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
import com.wikantik.comments.mentions.MentionService;
import com.wikantik.jdbc.testing.PostgresTestDb;
import com.wikantik.jdbc.testing.RequiresPostgres;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import javax.sql.DataSource;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;

@RequiresPostgres
class CommentStoreTest {

    private DataSource ds;
    private CommentStore store;
    private MentionService mentions;

    @BeforeEach
    void setUp() {
        this.ds = PostgresTestDb.createDataSource();
        PostgresTestDb.truncate( "comment_mentions", "comments", "comment_threads" );
        this.store = new CommentStore( ds );
        // user-exists predicate returns false for everything -> mention writes
        // are effective no-ops, isolating these tests from mention-row noise.
        this.mentions = new MentionService( ds, s -> false );
    }

    @Test
    void createThread_persists_thread_with_first_comment() {
        final CommentThread t = store.createThread( "CID1",
                new TextQuoteSelector( "hello", "say ", " world" ), "alice", "what does this mean?",
                mentions, Optional.empty() );

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
                new TextQuoteSelector( "x", null, null ), "alice", "first",
                mentions, Optional.empty() );
        store.addComment( t.id(), "bob", "reply", mentions );

        final List< CommentThread > threads = store.listByCanonicalId( "CID1", "all" );
        assertEquals( 1, threads.size() );
        assertEquals( 2, threads.get( 0 ).comments().size() );
        assertEquals( "first", threads.get( 0 ).comments().get( 0 ).body() );
        assertEquals( "reply", threads.get( 0 ).comments().get( 1 ).body() );
    }

    @Test
    void listByCanonicalId_filters_by_status() {
        final CommentThread open = store.createThread( "CID1",
                new TextQuoteSelector( "a", null, null ), "alice", "open one",
                mentions, Optional.empty() );
        final CommentThread done = store.createThread( "CID1",
                new TextQuoteSelector( "b", null, null ), "alice", "to resolve",
                mentions, Optional.empty() );
        store.resolve( done.id(), "bob" );

        assertEquals( 1, store.listByCanonicalId( "CID1", "open" ).size() );
        assertEquals( 1, store.listByCanonicalId( "CID1", "resolved" ).size() );
        assertEquals( 2, store.listByCanonicalId( "CID1", "all" ).size() );
        assertEquals( open.id(), store.listByCanonicalId( "CID1", "open" ).get( 0 ).id() );
    }

    @Test
    void resolve_then_reopen_toggles_status() {
        final CommentThread t = store.createThread( "CID1",
                new TextQuoteSelector( "a", null, null ), "alice", "x",
                mentions, Optional.empty() );
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
                new TextQuoteSelector( "a", null, null ), "alice", "typo",
                mentions, Optional.empty() );
        final UUID cid = t.comments().get( 0 ).id();
        final String oldBody = store.findComment( cid ).orElseThrow().body();
        final Comment edited = store.editComment( cid, oldBody, "fixed", "alice", mentions ).orElseThrow();
        assertEquals( "fixed", edited.body() );
        assertNotNull( edited.editedAt() );
    }

    @Test
    void deleteComment_removes_single_comment() {
        final CommentThread t = store.createThread( "CID1",
                new TextQuoteSelector( "a", null, null ), "alice", "first",
                mentions, Optional.empty() );
        final Comment reply = store.addComment( t.id(), "bob", "reply", mentions );
        assertTrue( store.deleteComment( reply.id() ) );
        assertEquals( 1, store.listByCanonicalId( "CID1", "all" ).get( 0 ).comments().size() );
    }

    @Test
    void deleteThread_cascades_comments() {
        final CommentThread t = store.createThread( "CID1",
                new TextQuoteSelector( "a", null, null ), "alice", "first",
                mentions, Optional.empty() );
        assertTrue( store.deleteThread( t.id() ) );
        assertTrue( store.listByCanonicalId( "CID1", "all" ).isEmpty() );
    }

    @Test
    void findComment_returns_author_for_permission_checks() {
        final CommentThread t = store.createThread( "CID1",
                new TextQuoteSelector( "a", null, null ), "alice", "first",
                mentions, Optional.empty() );
        final UUID cid = t.comments().get( 0 ).id();
        assertEquals( "alice", store.findComment( cid ).orElseThrow().author() );
    }
}
