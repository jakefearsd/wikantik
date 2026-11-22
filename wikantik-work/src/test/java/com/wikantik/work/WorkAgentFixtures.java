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

import javax.sql.DataSource;
import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.UUID;

/** SQL fixtures for tests that need real queue and agent rows (the registry store methods are a later task). */
final class WorkAgentFixtures {

    /** Tables a test that writes as an agent truncates, in one call. */
    /** Names the tests register as agents; a directory stub that says "everyone exists" must not claim them (an agent never takes a person's name). */
    private static final java.util.Set< String > AGENT_NAMES = java.util.Set.of( "codex", "ghost", "plain", "outsider",
        "orphan", "spare", "old", "third", "third-bot", "second", "author", "builder", "agent-a", "agent-b",
        "ops-bot", "scribe-bot", "bot", "other", "deployer", "lead-hopeful", "reviewer-bot", "codex-2", "claude" );

    /** Whether a stub directory that accepts every login should treat it as a person: not an agent name, no {@code @host}. */
    static boolean isPerson( final String login ) {
        return login != null && !login.contains( "@" ) && !AGENT_NAMES.contains( login.toLowerCase( java.util.Locale.ROOT ) );
    }

    static final String[] TABLES = { "work_attachment_blob", "work_attachment", "work_saved_filter", "work_agent_credential", "work_agent_sponsor_history", "work_agent_queue", "work_agent", "work_queue", "work_link", "work_mention",
        "work_comment", "work_notification", "work_watch", "work_webhook", "work_notify_cursor", "work_notify_pref", "work_event", "work_item_revision", "work_sprint", "work_item", "work_member", "work_product_event", "work_product",
        "work_project" };

    private WorkAgentFixtures() {
    }

    /**
     * An agent actor for a test that goes through the service: sponsored by a wiki admin, so the sponsor ceiling
     * limits nothing and the test sees the agent's own rows. (A sponsorless {@link Actor#agent(long, String)} has no
     * standing at the service; it is for store-level tests.)
     */
    static Actor sponsored( final long agentId, final String name ) {
        return Actor.agent( agentId, name, Actor.human( "sponsor-root", java.util.Set.of(), true ) );
    }

    static long scalar( final DataSource ds, final String sql ) throws SQLException {
        try ( Connection c = ds.getConnection(); Statement s = c.createStatement();
              ResultSet rs = s.executeQuery( sql ) ) {
            rs.next();
            return rs.getLong( 1 );
        }
    }

    static void exec( final DataSource ds, final String sql ) throws SQLException {
        try ( Connection c = ds.getConnection(); Statement s = c.createStatement() ) {
            s.execute( sql );
        }
    }

    /** A fresh API key (random hash) of the given scope; {@code api_keys} is never truncated. */
    static long apiKey( final DataSource ds, final String scope ) throws SQLException {
        return scalar( ds, "INSERT INTO api_keys (key_hash, principal_login, scope) VALUES ('"
            + UUID.randomUUID().toString().replace( "-", "" ) + "', 'owner', '" + scope + "') RETURNING id" );
    }

    /** A fresh work key (random hash) owned by {@code owner}, as an agent-key fake mints it; null owner is 'owner'. */
    static long ownedKey( final DataSource ds, final String owner ) throws SQLException {
        try ( Connection c = ds.getConnection(); java.sql.PreparedStatement ps = c.prepareStatement(
                "INSERT INTO api_keys (key_hash, principal_login, scope) VALUES (?, ?, 'work') RETURNING id" ) ) {
            ps.setString( 1, UUID.randomUUID().toString().replace( "-", "" ) );
            ps.setString( 2, owner == null ? "owner" : owner );
            try ( ResultSet rs = ps.executeQuery() ) {
                rs.next();
                return rs.getLong( 1 );
            }
        }
    }

    /** The owner of a key: what a remint keeps (the fakes must preserve it, as the real port does). */
    static String ownerOf( final DataSource ds, final long keyId ) {
        try ( Connection c = ds.getConnection(); java.sql.PreparedStatement ps = c.prepareStatement(
                "SELECT principal_login FROM api_keys WHERE id = ?" ) ) {
            ps.setLong( 1, keyId );
            try ( ResultSet rs = ps.executeQuery() ) {
                if ( !rs.next() ) {
                    throw new WorkConflictException( "The agent's current API key was not found." );
                }
                return rs.getString( 1 );
            }
        } catch ( final SQLException e ) {
            throw new IllegalStateException( e );
        }
    }

    /** An agent row bound to the given key. */
    static long agent( final DataSource ds, final String name, final long keyId ) throws SQLException {
        // Sponsored on its row by the admin that sponsored(...) resolves: the claim lock compares the two.
        return scalar( ds, "INSERT INTO work_agent (name, sponsor) VALUES ('" + name + "', 'sponsor-root') RETURNING id" );
    }

    /** An agent row on a fresh work-scope key. */
    static long agent( final DataSource ds, final String name ) throws SQLException {
        return agent( ds, name, apiKey( ds, "work" ) );
    }

    static void queue( final DataSource ds, final String key ) throws SQLException {
        exec( ds, "INSERT INTO work_queue (key, name) VALUES ('" + key + "', '" + key + "')" );
    }
}
