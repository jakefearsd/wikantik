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

import com.wikantik.jdbc.testing.FaultInjectingDataSource;
import com.wikantik.jdbc.testing.PostgresTestDb;
import com.wikantik.jdbc.testing.RequiresPostgres;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import javax.sql.DataSource;
import java.net.URI;
import java.sql.SQLException;
import java.time.Instant;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

/** Each webhook write is its row plus its event in one transaction: a fault at either statement leaves neither. */
@RequiresPostgres
class JdbcWorkWebhooksRollbackTest {

    private static final URI URL = URI.create( "https://hooks.example.com/a" );
    private static final Actor LENA = JdbcWorkWebhooksPostgresTest.LENA;

    private DataSource ds;
    private FaultInjectingDataSource faulty;
    private WorkWebhookStore clean;
    private WorkWebhookStore failing;
    private Project project;

    @BeforeEach
    void setUp() throws SQLException {
        PostgresTestDb.truncate( WorkAgentFixtures.TABLES );
        ds = PostgresTestDb.createDataSource();
        faulty = new FaultInjectingDataSource( ds );
        final JdbcWorkStore store = new JdbcWorkStore( ds );
        project = store.createProject( "WIKI", "Wiki", "" );
        clean = store.notifyStores().webhooks();
        failing = new JdbcWorkStore( faulty ).notifyStores().webhooks();
    }

    private long webhookEvents() throws SQLException {
        return WorkAgentFixtures.scalar( ds, "SELECT COUNT(*) FROM work_event WHERE note = 'webhook'" );
    }

    private void failAt( final int n, final org.junit.jupiter.api.function.Executable op ) {
        faulty.reset();
        faulty.failOn( n, new IllegalStateException( "boom " + n ) );
        assertEquals( "boom " + n, assertThrows( IllegalStateException.class, op ).getMessage() );
    }

    @Test
    void aFailedSettingsEventLeavesNoWebhook() throws SQLException {
        for ( int n = 1; n <= 2; n++ ) {
            failAt( n, () -> failing.add( project, URL, WebhookFormat.GENERIC, "s", List.of(), LENA ) );
            assertEquals( 0, WorkAgentFixtures.scalar( ds, "SELECT COUNT(*) FROM work_webhook" ), "rows after failing at " + n );
            assertEquals( 0, webhookEvents() );
        }
    }

    @Test
    void aFailedSettingsEventLeavesTheWebhookUnchangedOnUpdate() throws SQLException {
        final Webhook w = clean.add( project, URL, WebhookFormat.GENERIC, "s", List.of(), LENA );
        for ( int n = 1; n <= 2; n++ ) {
            failAt( n, () -> failing.update( w, URI.create( "https://b.example.com/" ), List.of( "status" ), null, LENA ) );
            assertEquals( w, clean.byId( w.id() ).orElseThrow(), "unchanged after failing at " + n );
            assertEquals( 1, webhookEvents() );
        }
    }

    @Test
    void aFailedSettingsEventLeavesTheWebhookInPlaceOnRemove() throws SQLException {
        final Webhook w = clean.add( project, URL, WebhookFormat.GENERIC, "s", List.of(), LENA );
        for ( int n = 1; n <= 2; n++ ) {
            failAt( n, () -> failing.remove( w, LENA ) );
            assertEquals( w, clean.byId( w.id() ).orElseThrow(), "present after failing at " + n );
            assertEquals( 1, webhookEvents() );
        }
    }

    @Test
    void aFailedSwitchOffEventLeavesItOn() throws SQLException {
        final Webhook w = clean.add( project, URL, WebhookFormat.GENERIC, "s", List.of(), LENA );
        for ( int n = 1; n <= 2; n++ ) {
            failAt( n, () -> failing.disableForFailure( w, Instant.parse( "2026-03-02T00:00:00Z" ) ) );
            assertEquals( null, clean.byId( w.id() ).orElseThrow().disabledAt(), "still on after failing at " + n );
            assertEquals( 1, webhookEvents() );
        }
    }
}
