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

import com.wikantik.api.comments.Mention;
import org.h2.jdbcx.JdbcDataSource;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import javax.sql.DataSource;
import java.sql.Connection;
import java.sql.Statement;
import java.util.HashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;

class MentionServiceTest {

    private DataSource ds;
    private Set< String > users;
    private MentionService svc;

    @BeforeEach
    void setUp() throws Exception {
        final JdbcDataSource h2 = new JdbcDataSource();
        h2.setURL( "jdbc:h2:mem:msvc;MODE=PostgreSQL;DB_CLOSE_DELAY=-1" );
        this.ds = h2;
        try ( Connection c = ds.getConnection(); Statement s = c.createStatement() ) {
            // Minimal comments + comment_mentions schema for the FK + cascade.
            s.executeUpdate( "CREATE TABLE comments (id UUID PRIMARY KEY)" );
            s.executeUpdate( """
                CREATE TABLE comment_mentions (
                    id UUID PRIMARY KEY,
                    comment_id UUID NOT NULL REFERENCES comments(id) ON DELETE CASCADE,
                    mentioned_login TEXT NOT NULL,
                    mentioning_login TEXT NOT NULL,
                    is_owner_mention BOOLEAN NOT NULL DEFAULT FALSE,
                    created_at TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT CURRENT_TIMESTAMP,
                    read_at TIMESTAMP WITH TIME ZONE,
                    CONSTRAINT uq_comment_mentions UNIQUE (comment_id, mentioned_login)
                )""" );
        }
        this.users = new HashSet<>( List.of( "alice", "bob", "carol", "admin" ) );
        this.svc = new MentionService( ds, users::contains );
    }

    @AfterEach
    void tearDown() throws Exception {
        try ( Connection c = ds.getConnection(); Statement s = c.createStatement() ) {
            s.executeUpdate( "DROP TABLE comment_mentions" );
            s.executeUpdate( "DROP TABLE comments" );
        }
    }

    private UUID newComment() throws Exception {
        final UUID id = UUID.randomUUID();
        try ( Connection c = ds.getConnection(); Statement s = c.createStatement() ) {
            s.executeUpdate( "INSERT INTO comments (id) VALUES ('" + id + "')" );
        }
        return id;
    }

    @Test
    void recordCreate_writes_direct_mentions_minus_author() throws Exception {
        final UUID cid = newComment();
        try ( Connection c = ds.getConnection() ) {
            svc.recordCreate( c, cid, "alice", "hello @bob and @alice", Optional.empty() );
        }
        final List< Mention > rows = svc.findByComment( cid );
        assertEquals( 1, rows.size() );
        assertEquals( "bob", rows.get( 0 ).mentionedLogin() );
        assertFalse( rows.get( 0 ).isOwnerMention() );
    }

    @Test
    void recordCreate_with_owner_writes_owner_mention_when_distinct() throws Exception {
        final UUID cid = newComment();
        try ( Connection c = ds.getConnection() ) {
            svc.recordCreate( c, cid, "alice", "fyi @bob", Optional.of( "carol" ) );
        }
        final List< Mention > rows = svc.findByComment( cid );
        assertEquals( 2, rows.size() );
        assertTrue( rows.stream().anyMatch( m -> m.mentionedLogin().equals( "bob" ) && !m.isOwnerMention() ) );
        assertTrue( rows.stream().anyMatch( m -> m.mentionedLogin().equals( "carol" ) && m.isOwnerMention() ) );
    }

    @Test
    void recordCreate_skips_owner_mention_when_owner_is_author() throws Exception {
        final UUID cid = newComment();
        try ( Connection c = ds.getConnection() ) {
            svc.recordCreate( c, cid, "alice", "thinking out loud", Optional.of( "alice" ) );
        }
        assertTrue( svc.findByComment( cid ).isEmpty() );
    }

    @Test
    void recordCreate_skips_owner_mention_when_owner_already_directly_mentioned() throws Exception {
        final UUID cid = newComment();
        try ( Connection c = ds.getConnection() ) {
            svc.recordCreate( c, cid, "alice", "@bob ping", Optional.of( "bob" ) );
        }
        final List< Mention > rows = svc.findByComment( cid );
        assertEquals( 1, rows.size() );
        assertEquals( "bob", rows.get( 0 ).mentionedLogin() );
        assertFalse( rows.get( 0 ).isOwnerMention(),
                "the existing direct mention wins; no separate owner row" );
    }

    @Test
    void recordEdit_preserves_read_state_on_survivors() throws Exception {
        final UUID cid = newComment();
        try ( Connection c = ds.getConnection() ) {
            svc.recordCreate( c, cid, "alice", "@bob @carol", Optional.empty() );
        }
        final Mention bobMention = svc.findByComment( cid ).stream()
                .filter( m -> m.mentionedLogin().equals( "bob" ) ).findFirst().orElseThrow();
        svc.markRead( bobMention.id(), "bob" );

        try ( Connection c = ds.getConnection() ) {
            svc.recordEdit( c, cid, "alice", "@bob @carol", "@bob fine" );
        }
        final List< Mention > rows = svc.findByComment( cid );
        assertEquals( 1, rows.size(), "carol dropped, bob remains" );
        assertEquals( "bob", rows.get( 0 ).mentionedLogin() );
        assertNotNull( rows.get( 0 ).readAt(), "bob's read_at preserved across the edit" );
    }

    @Test
    void recordReply_does_not_write_owner_mention() throws Exception {
        final UUID cid = newComment();
        try ( Connection c = ds.getConnection() ) {
            svc.recordReply( c, cid, "alice", "@bob ack" );
        }
        final List< Mention > rows = svc.findByComment( cid );
        assertEquals( 1, rows.size() );
        assertEquals( "bob", rows.get( 0 ).mentionedLogin() );
    }

    @Test
    void markAllRead_updates_only_caller_unread_rows() throws Exception {
        final UUID c1 = newComment(), c2 = newComment();
        try ( Connection c = ds.getConnection() ) {
            svc.recordCreate( c, c1, "alice", "@bob hi", Optional.empty() );
            svc.recordCreate( c, c2, "carol", "@bob too", Optional.empty() );
        }
        assertEquals( 2, svc.markAllRead( "bob" ) );
        assertEquals( 0, svc.unreadCount( "bob" ) );
    }

    @Test
    void deletion_cascades_via_fk() throws Exception {
        final UUID cid = newComment();
        try ( Connection c = ds.getConnection() ) {
            svc.recordCreate( c, cid, "alice", "@bob", Optional.empty() );
        }
        try ( Connection c = ds.getConnection(); Statement s = c.createStatement() ) {
            s.executeUpdate( "DELETE FROM comments WHERE id = '" + cid + "'" );
        }
        assertTrue( svc.findByComment( cid ).isEmpty() );
    }

    @Test
    void duplicate_insert_is_a_no_op() throws Exception {
        final UUID cid = newComment();
        try ( Connection c = ds.getConnection() ) {
            svc.recordCreate( c, cid, "alice", "@bob @bob @bob", Optional.empty() );
            // Re-running shouldn't duplicate either.
            svc.recordCreate( c, cid, "alice", "@bob", Optional.empty() );
        }
        assertEquals( 1, svc.findByComment( cid ).size() );
    }
}
