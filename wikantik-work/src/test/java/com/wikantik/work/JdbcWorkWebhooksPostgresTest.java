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

import com.wikantik.jdbc.testing.PostgresTestDb;
import com.wikantik.jdbc.testing.RequiresPostgres;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import javax.sql.DataSource;
import java.net.URI;
import java.sql.SQLException;
import java.time.Instant;
import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

@RequiresPostgres
class JdbcWorkWebhooksPostgresTest {

    static final Actor LENA = Actor.human( "lena", Set.of(), false );
    static final Actor OTTO = Actor.human( "otto", Set.of(), false );
    static final URI URL = URI.create( "https://hooks.example.com/services/T0/B0/XYZSECRET" );

    private DataSource ds;
    private JdbcWorkStore store;
    private WorkWebhookStore webhooks;
    private Project project;

    @BeforeEach
    void setUp() throws SQLException {
        PostgresTestDb.truncate( WorkAgentFixtures.TABLES );
        ds = PostgresTestDb.createDataSource();
        store = new JdbcWorkStore( ds );
        webhooks = store.notifyStores().webhooks();
        project = store.createProject( "WIKI", "Wiki", "" );
    }

    private Webhook add() throws SQLException {
        return webhooks.add( project, URL, WebhookFormat.GENERIC, "s3cret", List.of( "status" ), LENA );
    }

    private long events( final String where ) throws SQLException {
        return WorkAgentFixtures.scalar( ds, "SELECT COUNT(*) FROM work_event WHERE note = 'webhook' AND " + where );
    }

    @Test
    void addStartsAtTheCurrentWindowAndWritesASettingsEvent() throws SQLException {
        WorkAgentFixtures.exec( ds, "INSERT INTO work_event (project_id, actor, type) VALUES (" + project.id()
            + ", 'x', 'edited')" );
        final long newestId = WorkAgentFixtures.scalar( ds, "SELECT id FROM work_event ORDER BY tx DESC, id DESC LIMIT 1" );
        final String newestTx = text( "SELECT tx::text FROM work_event WHERE id = " + newestId );
        final Webhook w = add();
        assertEquals( newestTx, w.cursorTx(), "history is never replayed" );
        assertEquals( newestId, w.cursorEventId() );
        assertEquals( "lena", w.owner() );
        assertEquals( List.of( "status" ), w.eventTypes() );
        assertEquals( 1, events( "type = 'settings' AND item_id IS NULL AND from_value = 'hooks.example.com' "
            + "AND to_value = 'added' AND actor = 'lena'" ) );
        assertEquals( w, webhooks.byId( w.id() ).orElseThrow() );
        assertEquals( List.of( w ), webhooks.forProject( project.id() ) );
        assertEquals( List.of( w ), webhooks.enabled() );
    }

    @Test
    void anEmptyLogStartsAtTheWindowStart() throws SQLException {
        PostgresTestDb.truncate( WorkAgentFixtures.TABLES );
        project = store.createProject( "TWO", "Two", "" );
        final Webhook w = webhooks.add( project, URL, WebhookFormat.SLACK, null, List.of(), LENA );
        assertNull( w.secret() );
        assertTrue( Long.parseLong( w.cursorTx() ) > 0 );
    }

    @Test
    void updateMakesTheUpdaterTheOwner() throws SQLException {
        final Webhook w = add();
        final Webhook u = webhooks.update( w, URI.create( "https://other.example.com/x" ), List.of(), null, OTTO );
        assertEquals( "otto", u.owner() );
        assertEquals( "other.example.com", u.url().getHost() );
        assertEquals( List.of(), u.eventTypes() );
        assertEquals( w.cursorTx(), u.cursorTx(), "an ordinary change keeps the cursor" );
        assertEquals( 1, events( "to_value = 'changed' AND from_value = 'other.example.com' AND actor = 'otto'" ) );
        assertEquals( u, webhooks.update( u, null, null, null, OTTO ), "nothing to change, nothing written" );
        assertEquals( 1, events( "to_value = 'changed'" ) );
    }

    @Test
    void reEnablingResetsTheCursorAndClearsTheFailure() throws SQLException {
        final Webhook w = add();
        webhooks.recordFailure( w.id(), Instant.parse( "2026-03-01T00:00:00Z" ), "HTTP 500" );
        assertTrue( webhooks.disableForFailure( webhooks.byId( w.id() ).orElseThrow(), Instant.parse( "2026-03-02T00:00:00Z" ) ) );
        WorkAgentFixtures.exec( ds, "INSERT INTO work_event (project_id, actor, type) VALUES (" + project.id()
            + ", 'x', 'edited')" );
        final long newestId = WorkAgentFixtures.scalar( ds, "SELECT id FROM work_event ORDER BY tx DESC, id DESC LIMIT 1" );
        final Webhook off = webhooks.byId( w.id() ).orElseThrow();
        assertTrue( off.disabledAt() != null && off.failingSince() != null );
        assertEquals( List.of(), webhooks.enabled() );
        final Webhook on = webhooks.update( off, null, null, true, OTTO );
        assertNull( on.disabledAt() );
        assertNull( on.failingSince() );
        assertNull( on.lastError() );
        assertEquals( newestId, on.cursorEventId(), "resumes from now, not from where it stopped" );
        assertEquals( 1, events( "to_value = 'enabled'" ) );
        final Webhook down = webhooks.update( on, null, null, false, OTTO );
        assertTrue( down.disabledAt() != null );
        assertEquals( 1, events( "to_value = 'disabled'" ) );
    }

    @Test
    void removeDeletesTheRowAndKeepsTheEvent() throws SQLException {
        final Webhook w = add();
        webhooks.remove( w, OTTO );
        assertTrue( webhooks.byId( w.id() ).isEmpty() );
        assertEquals( 1, events( "to_value = 'removed' AND actor = 'otto'" ) );
        assertEquals( 1, events( "to_value = 'added'" ), "the log keeps the history" );
    }

    @Test
    void toStringNeverShowsThePathOrSecret() throws SQLException {
        final Webhook w = add();
        assertEquals( "https://hooks.example.com/…", w.target() );
        for ( final String shown : new String[] { w.toString(), new WebhookDraft( URL.toString(), "generic", List.of() ).toString(),
            new WebhookPatch( URL.toString(), null, null ).toString(),
            new WebhookCreated( null, "s3cret" ).toString() } ) {
            assertFalse( shown.contains( "XYZSECRET" ) || shown.contains( "s3cret" ) || shown.contains( "/services" ), shown );
        }
        assertTrue( w.toString().contains( "target=https://hooks.example.com/…" ) );
        assertNotEquals( -1, w.toString().indexOf( "format=generic" ) );
    }

    private String text( final String sql ) throws SQLException {
        try ( var c = ds.getConnection(); var s = c.createStatement(); var rs = s.executeQuery( sql ) ) {
            rs.next();
            return rs.getString( 1 );
        }
    }
}
