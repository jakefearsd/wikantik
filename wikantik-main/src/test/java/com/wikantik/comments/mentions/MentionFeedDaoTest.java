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
package com.wikantik.comments.mentions;

import com.wikantik.api.comments.MentionFeedItem;
import org.h2.jdbcx.JdbcDataSource;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import javax.sql.DataSource;
import java.sql.Connection;
import java.sql.Statement;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;

class MentionFeedDaoTest {

    private DataSource ds;
    private MentionFeedDao dao;

    @BeforeEach
    void setUp() throws Exception {
        final JdbcDataSource h2 = new JdbcDataSource();
        h2.setURL( "jdbc:h2:mem:mfd;MODE=PostgreSQL;DB_CLOSE_DELAY=-1" );
        this.ds = h2;
        try ( Connection c = ds.getConnection(); Statement s = c.createStatement() ) {
            s.executeUpdate( """
                CREATE TABLE comment_threads (
                    id UUID PRIMARY KEY, canonical_id TEXT NOT NULL,
                    anchor_exact TEXT NOT NULL, anchor_prefix TEXT, anchor_suffix TEXT,
                    status TEXT NOT NULL DEFAULT 'open', created_by TEXT NOT NULL,
                    created_at TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT CURRENT_TIMESTAMP,
                    resolved_by TEXT, resolved_at TIMESTAMP WITH TIME ZONE
                )""" );
            s.executeUpdate( """
                CREATE TABLE comments (
                    id UUID PRIMARY KEY,
                    thread_id UUID NOT NULL REFERENCES comment_threads(id) ON DELETE CASCADE,
                    author TEXT NOT NULL, body TEXT NOT NULL,
                    created_at TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT CURRENT_TIMESTAMP,
                    edited_at TIMESTAMP WITH TIME ZONE
                )""" );
            s.executeUpdate( """
                CREATE TABLE comment_mentions (
                    id UUID PRIMARY KEY,
                    comment_id UUID NOT NULL REFERENCES comments(id) ON DELETE CASCADE,
                    mentioned_login TEXT NOT NULL, mentioning_login TEXT NOT NULL,
                    is_owner_mention BOOLEAN NOT NULL DEFAULT FALSE,
                    created_at TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT CURRENT_TIMESTAMP,
                    read_at TIMESTAMP WITH TIME ZONE
                )""" );
        }
        this.dao = new MentionFeedDao( ds );
    }

    @AfterEach
    void tearDown() throws Exception {
        try ( Connection c = ds.getConnection(); Statement s = c.createStatement() ) {
            s.executeUpdate( "DROP TABLE comment_mentions" );
            s.executeUpdate( "DROP TABLE comments" );
            s.executeUpdate( "DROP TABLE comment_threads" );
        }
    }

    private UUID seedThread( final String canonicalId ) throws Exception {
        final UUID id = UUID.randomUUID();
        try ( Connection c = ds.getConnection(); Statement s = c.createStatement() ) {
            s.executeUpdate( "INSERT INTO comment_threads (id, canonical_id, anchor_exact, created_by) " +
                    "VALUES ('" + id + "','" + canonicalId + "','quoted','alice')" );
        }
        return id;
    }

    private UUID seedComment( final UUID threadId, final String author, final String body ) throws Exception {
        final UUID id = UUID.randomUUID();
        try ( Connection c = ds.getConnection(); Statement s = c.createStatement() ) {
            s.executeUpdate( "INSERT INTO comments (id, thread_id, author, body) " +
                    "VALUES ('" + id + "','" + threadId + "','" + author + "','" + body + "')" );
        }
        return id;
    }

    private void seedMention( final UUID commentId, final String mentioned,
                              final String mentioning, final boolean isOwner ) throws Exception {
        try ( Connection c = ds.getConnection(); Statement s = c.createStatement() ) {
            s.executeUpdate( "INSERT INTO comment_mentions (id, comment_id, mentioned_login, mentioning_login, is_owner_mention) " +
                    "VALUES ('" + UUID.randomUUID() + "','" + commentId + "','" + mentioned + "','" + mentioning + "'," + isOwner + ")" );
        }
    }

    @Test
    void list_returns_only_callers_mentions_newest_first() throws Exception {
        final UUID t = seedThread( "CID-1" );
        final UUID c1 = seedComment( t, "alice", "hello @bob friend" );
        Thread.sleep( 10 );
        final UUID c2 = seedComment( t, "alice", "@bob still here" );
        seedMention( c1, "bob",   "alice", false );
        seedMention( c2, "bob",   "alice", false );
        seedMention( c1, "carol", "alice", false );  // not for bob

        final List< MentionFeedItem > items = dao.list( "bob", MentionFeedDao.Status.ALL, 50, Optional.empty() );
        assertEquals( 2, items.size() );
        assertEquals( c2, items.get( 0 ).commentId(), "newest first" );
        assertTrue( items.get( 0 ).snippet().contains( "@bob still here" ) );
    }

    @Test
    void list_unread_filter_excludes_read_rows() throws Exception {
        final UUID t = seedThread( "CID-1" );
        final UUID c = seedComment( t, "alice", "@bob" );
        seedMention( c, "bob", "alice", false );

        try ( Connection c2 = ds.getConnection(); Statement s = c2.createStatement() ) {
            s.executeUpdate( "UPDATE comment_mentions SET read_at = CURRENT_TIMESTAMP WHERE mentioned_login = 'bob'" );
        }
        assertEquals( 1, dao.list( "bob", MentionFeedDao.Status.ALL, 50, Optional.empty() ).size() );
        assertEquals( 0, dao.list( "bob", MentionFeedDao.Status.UNREAD, 50, Optional.empty() ).size() );
    }

    @Test
    void list_pagination_via_before_cursor() throws Exception {
        final UUID t = seedThread( "CID-1" );
        for ( int i = 0; i < 5; i++ ) {
            final UUID c = seedComment( t, "alice", "msg " + i + " @bob" );
            seedMention( c, "bob", "alice", false );
            Thread.sleep( 10 );
        }
        final List< MentionFeedItem > first = dao.list( "bob", MentionFeedDao.Status.ALL, 2, Optional.empty() );
        assertEquals( 2, first.size() );
        final Instant cursor = first.get( first.size() - 1 ).mentionedAt();
        final List< MentionFeedItem > second = dao.list( "bob", MentionFeedDao.Status.ALL, 2, Optional.of( cursor ) );
        assertEquals( 2, second.size() );
        assertNotEquals( first.get( 0 ).id(), second.get( 0 ).id() );
    }

    @Test
    void snippet_is_truncated_to_160_chars() throws Exception {
        final UUID t = seedThread( "CID-1" );
        final String body = "x".repeat( 500 ) + " @bob";
        final UUID c = seedComment( t, "alice", body );
        seedMention( c, "bob", "alice", false );
        final MentionFeedItem it = dao.list( "bob", MentionFeedDao.Status.ALL, 1, Optional.empty() ).get( 0 );
        assertTrue( it.snippet().length() <= 160 );
    }
}
