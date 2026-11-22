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
package com.wikantik.rest;

import com.wikantik.jdbc.testing.PostgresTestDb;
import com.wikantik.jdbc.testing.RequiresPostgres;
import com.wikantik.work.Actor;
import com.wikantik.work.Autonomy;
import com.wikantik.work.DeployGate;
import com.wikantik.work.DeploymentDraft;
import com.wikantik.work.ItemDraft;
import com.wikantik.work.JdbcWorkStore;
import com.wikantik.work.Product;
import com.wikantik.work.WorkDirectory;
import com.wikantik.work.WorkKind;
import com.wikantik.work.WorkLevel;
import com.wikantik.work.WorkRole;
import com.wikantik.work.WorkService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import javax.sql.DataSource;
import java.sql.Connection;
import java.sql.SQLException;
import java.sql.Statement;
import java.time.Clock;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * A fix-forward filed under project OPS must not show a member of PLAT anything of OPS: not through the DORA
 * route, not through a deployment view. Real service, real PostgreSQL, the real servlets; the response text is
 * what is searched.
 */
@RequiresPostgres
class WorkDoraLeakTest {

    private static final String[] TABLES = { "work_attachment_blob", "work_attachment", "work_saved_filter",
        "work_agent_credential", "work_agent_sponsor_history", "work_agent_queue", "work_agent", "work_queue",
        "work_link", "work_mention", "work_comment", "work_notification", "work_watch", "work_webhook",
        "work_notify_cursor", "work_notify_pref", "work_event", "work_item_revision", "work_sprint", "work_item",
        "work_member", "work_product_event", "work_codehost_identity", "work_product_hook", "work_product",
        "work_project" };

    private final Actor admin = Actor.human( "admin", Set.of(), true );
    private final Actor alice = Actor.human( "alice", Set.of(), false );
    private WorkService svc;
    private DataSource ds;

    @BeforeEach
    void setUp() throws SQLException {
        ds = PostgresTestDb.createDataSource();
        PostgresTestDb.truncate( TABLES );
        svc = new WorkService( new JdbcWorkStore( ds ), md -> md, new WorkDirectory() {
            @Override public Set< String > groupsOf( final String login ) { return Set.of(); }
            @Override public boolean exists( final String login ) { return true; }
            @Override public String displayName( final String login ) { return "olga".equals( login ) ? "Olga Ops" : login; }
            @Override public Optional< String > loginForDisplayName( final String name ) { return Optional.empty(); }
        }, Clock.systemUTC() );
        svc.createProject( admin, "PLAT", "Platform", "" );
        svc.putMember( admin, "PLAT", "alice", "user", WorkRole.MEMBER );
        svc.createProject( admin, "OPS", "Operations", "" );
        svc.putMember( admin, "OPS", "olga", "user", WorkRole.LEAD );
        svc.createProduct( admin, new Product( 0, "wikantik", "Wikantik", null, "main", null, Autonomy.AUTONOMOUS,
            DeployGate.HUMAN, null ) );
        exec( "SELECT setval( pg_get_serial_sequence( 'work_deployment', 'id' ), 4242 )" );
    }

    private void exec( final String sql ) throws SQLException {
        try ( Connection c = ds.getConnection(); Statement st = c.createStatement() ) {
            st.execute( sql );
        }
    }

    private String item( final String project ) throws SQLException {
        return svc.createItem( admin, new ItemDraft( project, WorkLevel.TASK, null, "wikantik", WorkKind.SOFTWARE,
            "Work of " + project, "", null, null, null, null, null, null ) ).item().key();
    }

    private long live( final String itemKey, final String version, final Long remediates ) throws SQLException {
        final long id = svc.deployments().request( admin, new DeploymentDraft( "wikantik", "production", version,
            List.of( itemKey ), remediates ) ).deployment().id();
        svc.deployments().approve( admin, id, null );
        svc.deployments().start( admin, id );
        svc.deployments().verify( admin, id, "health 200" );
        return id;
    }

    private String today( final int plusDays ) {
        return LocalDate.now( ZoneOffset.UTC ).plusDays( plusDays ).toString();
    }

    @Test
    void aFixForwardFiledUnderBShowsAMemberOfAOnlyANumber() throws Exception {
        final String a = item( "PLAT" );
        final String b = item( "OPS" );
        final long bad = live( a, "1.0.0", null );
        final long fix = live( b, "9.9.9-fix", bad );
        assertEquals( 4244L, fix );
        final WorkHttp.Result r = WorkHttp.call( WorkHttp.wire( new WorkProjectResource(), svc, alice ), "GET",
            "/api/work/projects", "/PLAT/dora", Map.of( "from", today( -1 ), "to", today( 1 ) ), null );
        assertEquals( 200, r.status(), r.body() );
        assertEquals( 1, r.json().getAsJsonObject().getAsJsonObject( "changeFailureRate" ).get( "numerator" ).getAsInt(),
            "the failure counts: " + r.body() );
        for ( final String secret : List.of( "OPS", b, String.valueOf( fix ), "9.9.9-fix", "olga", "Olga Ops",
                "Operations" ) ) {
            assertFalse( r.body().contains( secret ), "A's DORA response names B's " + secret + ": " + r.body() );
        }
    }

    @Test
    void aDeploymentViewDoesNotNameATargetOnlyBCanSee() throws Exception {
        final String a = item( "PLAT" );
        final String b = item( "OPS" );
        final long target = live( b, "2.0.0", null );
        final long fix = live( a, "2.0.1", target );
        final WorkHttp.Result seen = WorkHttp.call( WorkHttp.wire( new WorkDeploymentResource(), svc, alice ), "GET",
            "/api/work/deployments", "/" + fix, Map.of(), null );
        assertEquals( 200, seen.status(), seen.body() );
        final com.google.gson.JsonObject shown = seen.json().getAsJsonObject();
        assertTrue( !shown.has( "remediatesId" ) || shown.get( "remediatesId" ).isJsonNull(), seen.body() );
        assertFalse( seen.body().contains( String.valueOf( target ) ), seen.body() );
        final WorkHttp.Result all = WorkHttp.call( WorkHttp.wire( new WorkDeploymentResource(), svc, admin ), "GET",
            "/api/work/deployments", "/" + fix, Map.of(), null );
        assertTrue( all.body().contains( "\"remediatesId\":" + target ), all.body() );
    }
}
