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

import com.wikantik.jdbc.testing.RequiresPostgres;
import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * I-2: the post-restore step in BackupAndRecovery.md section 5.3, read from that page and run as written. A restore
 * into a new cluster is simulated by giving every event a transaction id far above the database's current one, as a
 * dump from an older, busier cluster carries, with the cursors restored at the newest of them.
 */
@RequiresPostgres
class WorkRestoreCursorPostgresTest {

    private static final Actor LENA = NotifyServiceFixture.LENA;
    private static final Actor OTTO = NotifyServiceFixture.OTTO;
    private static final Path DOC = Path.of( "..", "docs", "admin", "BackupAndRecovery.md" );

    private NotifyServiceFixture fx;

    /** The statements of the documented block, without its BEGIN and COMMIT (the test runs them in one transaction). */
    static List< String > documentedStatements() throws Exception {
        final String page = Files.readString( DOC );
        final int begin = page.indexOf( "<!-- work-cursor-rebase:begin -->" );
        final int end = page.indexOf( "<!-- work-cursor-rebase:end -->" );
        assertTrue( begin >= 0 && end > begin, "the marked block is in " + DOC );
        final String block = page.substring( begin, end ).replaceAll( "(?m)^--.*$", "" ).replaceAll( "(?m)^```.*$", "" )
            .replace( "<!-- work-cursor-rebase:begin -->", "" );
        final List< String > out = new ArrayList<>();
        for ( final String part : block.split( ";" ) ) {
            final String s = part.strip();
            if ( !s.isEmpty() && !s.equalsIgnoreCase( "BEGIN" ) && !s.equalsIgnoreCase( "COMMIT" ) ) {
                out.add( s );
            }
        }
        return out;
    }

    private void runDocumentedBlock() throws Exception {
        final List< String > statements = documentedStatements();
        assertEquals( 3, statements.size(), statements.toString() );
        try ( Connection c = fx.ds.getConnection() ) {
            c.setAutoCommit( false );
            try ( Statement st = c.createStatement() ) {
                for ( final String s : statements ) {
                    st.executeUpdate( s );
                }
            }
            c.commit();
        }
    }

    private long newestEventId() throws SQLException {
        return WorkAgentFixtures.scalar( fx.ds, "SELECT COALESCE( MAX( id ), 0 ) FROM work_event" );
    }

    private long webhook() throws SQLException {
        return WorkAgentFixtures.scalar( fx.ds, "INSERT INTO work_webhook (project_id, url, format, owner, cursor_tx, "
            + "cursor_event_id) SELECT " + fx.project.id() + ", 'https://hooks.example.com/x', 'slack', 'lena', "
            + "max( tx ), max( id ) FROM work_event RETURNING id" );
    }

    private List< Long > webhookPending( final long id ) throws SQLException {
        final WorkWebhookStore hooks = fx.store.notifyStores().webhooks();
        return hooks.pending( hooks.byId( id ).orElseThrow(), 100 ).stream().map( e -> e.event().id() ).toList();
    }

    @Test
    void afterARestoreIntoANewClusterTheDocumentedStepRestartsDispatchWithoutResendingHistory() throws Exception {
        fx = new NotifyServiceFixture();
        fx.plant();
        final Item item = fx.newItem( LENA );
        fx.notices.setWatch( OTTO, item.key(), WatchState.WATCHING );
        fx.patch( LENA, item, "status", "in_progress" );

        // The restore: every event carries a transaction id from a cluster far ahead of this one, and the cursors
        // were caught up with the log when the dump was taken.
        WorkAgentFixtures.exec( fx.ds, "UPDATE work_event SET tx = ( pg_current_xact_id()::text::bigint + 1000000000 )"
            + "::text::xid8" );
        WorkAgentFixtures.exec( fx.ds, "UPDATE work_notify_cursor SET tx = ( SELECT max( tx ) FROM work_event ), "
            + "event_id = ( SELECT max( id ) FROM work_event )" );
        final long hook = webhook();
        final long restored = newestEventId();

        // Without the step, a new event is never picked up: dispatch is stalled, silently.
        fx.patch( LENA, item, "status", "review" );
        final long stalled = newestEventId();
        DispatchAwait.windowPast( fx.ds, stalled );
        assertEquals( 0, fx.notices.dispatch( 100 ), "the restored cursor is above every new event" );
        assertEquals( 0, fx.rows( "recipient = 'otto'" ) );
        assertEquals( List.of(), webhookPending( hook ) );

        runDocumentedBlock();
        final long beforeStep = newestEventId();

        fx.patch( LENA, item, "status", "in_progress" );
        final long fresh = newestEventId();
        assertTrue( fresh > beforeStep && beforeStep >= stalled && stalled > restored );
        fx.dispatchAll();
        assertEquals( 1, fx.rows( "recipient = 'otto'" ), "the new event is delivered" );
        assertEquals( 1, fx.rows( "recipient = 'otto' AND event_id = " + fresh ) );
        assertEquals( 0, fx.rows( "event_id <= " + beforeStep ), "nothing from before the step is sent" );
        DispatchAwait.windowPast( fx.ds, fresh );
        assertEquals( List.of( fresh ), webhookPending( hook ), "the webhook resumes with the new event only" );

        fx.notices.dispatch( 100 );
        assertEquals( 1, fx.rows( "recipient = 'otto'" ), "delivered once" );
    }

    @Test
    void theDocumentedStepIsHarmlessWhenNotificationsNeverRan() throws Exception {
        fx = new NotifyServiceFixture();
        final Item item = fx.newItem( LENA );
        runDocumentedBlock();
        assertEquals( 0, WorkAgentFixtures.scalar( fx.ds, "SELECT COUNT(*) FROM work_notify_cursor" ) );
        fx.plant();
        fx.notices.setWatch( OTTO, item.key(), WatchState.WATCHING );
        fx.patch( LENA, item, "status", "in_progress" );
        fx.dispatchAll();
        assertEquals( 1, fx.rows( "recipient = 'otto'" ) );
    }
}
