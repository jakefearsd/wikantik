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
import com.wikantik.work.WorkClaimsService.Handoff;

import javax.sql.DataSource;
import java.sql.SQLException;
import java.time.Clock;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

/**
 * A real service over real PostgreSQL for the autonomy tests: project PLAT (default level delegated), a lead
 * (lena), a member (mia), two agents (author, reviewer) sponsored by the admin and serving both queues, and a
 * product whose ceiling the test chooses.
 */
final class GateFixture {

    final Actor admin = Actor.human( "admin", Set.of(), true );
    final Actor lena = Actor.human( "lena", Set.of(), false );
    final Actor mia = Actor.human( "mia", Set.of(), false );
    final DataSource ds;
    final WorkService svc;
    final Actor author;
    final Actor reviewer;

    GateFixture() throws SQLException {
        this( null );
    }

    /** As above, reporting outcomes to {@code listener} (null for none). */
    GateFixture( final WorkListener listener ) throws SQLException {
        ds = PostgresTestDb.createDataSource();
        PostgresTestDb.truncate( WorkAgentFixtures.TABLES );
        svc = new WorkService( new JdbcWorkStore( ds ), md -> md, new WorkDirectory() {
            @Override public Set< String > groupsOf( final String login ) { return Set.of(); }
            @Override public boolean exists( final String login ) { return true; }
            @Override public String displayName( final String login ) { return login; }
            @Override public Optional< String > loginForDisplayName( final String name ) {
                return Set.of( "lena", "mia" ).contains( name ) ? Optional.of( name ) : Optional.empty();
            }
        }, Clock.systemUTC(), WorkSettings.DEFAULTS, listener );
        svc.createProject( admin, "PLAT", "Platform", "" );
        svc.putMember( admin, "PLAT", "lena", "user", WorkRole.LEAD );
        svc.putMember( admin, "PLAT", "mia", "user", WorkRole.MEMBER );
        svc.registry().createQueue( admin, "implementer", "Implementer", "" );
        svc.registry().createQueue( admin, "reviewer", "Reviewer", "" );
        author = agent( "author" );
        reviewer = agent( "reviewer-bot" );
        svc.createProduct( admin, new Product( 0, "wikantik", "Wikantik", null, "main", null, Autonomy.AUTONOMOUS,
            DeployGate.HUMAN, null ) );
    }

    private Actor agent( final String name ) throws SQLException {
        final Agent a = svc.registry().registerAgent( admin,
            new AgentDraft( name, "", "", List.of( "implementer", "reviewer" ) ) ).agent();
        svc.putMember( admin, "PLAT", name, "agent", WorkRole.MEMBER );
        return Actor.agent( a.id(), a.name(), admin );
    }

    void ceiling( final Autonomy level ) throws SQLException {
        final Product p = svc.listProducts( admin ).get( 0 );
        svc.updateProduct( admin, new Product( p.id(), p.key(), p.name(), p.repoUrl(), p.defaultBranch(),
            p.wikiCluster(), level, p.deployGate(), p.archivedAt() ) );
    }

    /** A draft a person created, in the implementer queue, under {@code parentKey} (or none), targeting the product. */
    Item draft( final WorkLevel level, final String parentKey ) throws SQLException {
        final Item made = svc.createItem( lena, new ItemDraft( "PLAT", level, parentKey,
            level == WorkLevel.EPIC ? null : "wikantik", WorkKind.SOFTWARE, "An item", "", null, null, null,
            level == WorkLevel.EPIC ? null : "implementer" ) ).item();
        // a person's new task or story starts ready; these tests need the draft, so the person moves it back
        return made.status() == WorkStatus.DRAFT ? made : status( lena, made, "draft" );
    }

    Item task() throws SQLException {
        return draft( WorkLevel.TASK, null );
    }

    Item fresh( final Item i ) throws SQLException {
        return svc.item( admin, i.key() ).item();
    }

    Item set( final Actor by, final Item i, final String field, final Object value ) throws SQLException {
        final Map< String, Object > changes = new HashMap<>();
        changes.put( field, value );
        return svc.updateItem( by, i.key(), new ItemPatch( fresh( i ).version(), changes, null ) ).item();
    }

    Item status( final Actor by, final Item i, final String status ) throws SQLException {
        return set( by, i, "status", status );
    }

    Item level( final Item i, final Autonomy level ) throws SQLException {
        return set( lena, i, "autonomy", level == null ? null : level.wire() );
    }

    /** Ready in the queue, then claimed by {@code agent}: the item is in progress and the agent holds it. */
    Item claimedBy( final Actor agent, final Item i ) throws SQLException {
        status( lena, i, "ready" );
        return svc.claims().claim( agent, i.key() ).view().item();
    }

    Item handoff( final Actor by, final Item i, final WorkStatus status, final String person, final String queue )
            throws SQLException {
        return svc.claims().handoff( by, i.key(), new Handoff( fresh( i ).version(), status, person, queue, "note" ) )
            .item();
    }

    long count( final String sql ) throws SQLException {
        return WorkAgentFixtures.scalar( ds, sql );
    }
}
