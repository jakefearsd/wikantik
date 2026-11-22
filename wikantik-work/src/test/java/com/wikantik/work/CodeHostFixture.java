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

import javax.sql.DataSource;
import java.sql.SQLException;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

/**
 * A real service over real PostgreSQL for the code-host tests: project PLAT (lena leads, mia is a member, ana is a
 * person whose address is {@code ana@example.com}), a product {@code wk} on repository {@code acme/wk} with default
 * branch {@code main}, two agents, and a clock the test moves. Audit records are captured, never sent.
 */
final class CodeHostFixture {

    /** A clock the test sets; never the wall clock. */
    static final class TestClock extends Clock {
        private Instant now;

        TestClock( final Instant start ) {
            this.now = start;
        }

        void set( final Instant at ) {
            this.now = at;
        }

        void advance( final Duration d ) {
            this.now = now.plus( d );
        }

        @Override public ZoneId getZone() {
            return ZoneOffset.UTC;
        }

        @Override public Clock withZone( final ZoneId zone ) {
            return this;
        }

        @Override public Instant instant() {
            return now;
        }
    }

    static final Instant START = Instant.parse( "2026-03-01T12:00:00Z" );

    final Actor admin = Actor.human( "admin", Set.of(), true );
    final Actor lena = Actor.human( "lena", Set.of(), false );
    final Actor mia = Actor.human( "mia", Set.of(), false );
    final Actor ana = Actor.human( "ana", Set.of(), false );
    final Actor github = Actor.system( "github" );
    final TestClock clock = new TestClock( START );
    final Map< String, String > emails = new HashMap<>();
    /** Profile addresses by login, for notification email (separate from {@link #emails}, the code-host lookup). */
    final Map< String, String > addresses = new HashMap<>();
    final List< String[] > audits = new ArrayList<>();
    final DataSource ds;
    final JdbcWorkStore store;
    final WorkService svc;
    final WorkCodeHostService hooks;
    final Actor author;
    final Actor reviewer;
    final Product product;
    final String secret;

    CodeHostFixture() throws SQLException {
        this( WorkNotifyPorts.NONE );
    }

    /** As above, with notification ports (a recording mailer for the email tests). */
    CodeHostFixture( final WorkNotifyPorts ports ) throws SQLException {
        ds = PostgresTestDb.createDataSource();
        PostgresTestDb.truncate( WorkAgentFixtures.TABLES );
        emails.put( "ana@example.com", "ana" );
        store = new JdbcWorkStore( ds );
        svc = new WorkService( store, md -> md, new WorkDirectory() {
            @Override public Set< String > groupsOf( final String login ) { return Set.of(); }
            @Override public boolean exists( final String login ) { return WorkAgentFixtures.isPerson( login ); }
            @Override public String displayName( final String login ) { return login; }
            @Override public Optional< String > loginForDisplayName( final String name ) {
                return Set.of( "lena", "mia", "ana" ).contains( name ) ? Optional.of( name ) : Optional.empty();
            }
            @Override public Optional< String > loginForEmail( final String email ) {
                return Optional.ofNullable( emails.get( email.toLowerCase( java.util.Locale.ROOT ) ) );
            }
            @Override public Optional< String > email( final String login ) {
                return Optional.ofNullable( addresses.get( login ) );
            }
        }, clock, WorkSettings.DEFAULTS, null, ( login, action, type, target, detail ) ->
            audits.add( new String[] { login, action, type, target, detail } ), ports );
        svc.createProject( admin, "PLAT", "Platform", "" );
        svc.putMember( admin, "PLAT", "lena", "user", WorkRole.LEAD );
        svc.putMember( admin, "PLAT", "mia", "user", WorkRole.MEMBER );
        svc.putMember( admin, "PLAT", "ana", "user", WorkRole.MEMBER );
        svc.registry().createQueue( admin, "implementer", "Implementer", "" );
        svc.registry().createQueue( admin, "reviewer", "Reviewer", "" );
        author = agent( "author" );
        reviewer = agent( "reviewer-bot" );
        svc.createProduct( admin, new Product( 0, "wk", "Wk", "https://github.com/acme/wk", "main", null,
            Autonomy.AUTONOMOUS, DeployGate.HUMAN, null ) );
        product = svc.listProducts( admin ).stream().filter( p -> p.key().equals( "wk" ) ).findFirst().orElseThrow();
        hooks = svc.features().codeHost();
        secret = hooks.enableHook( admin, "wk" ).secret();
        audits.clear();
    }

    private Actor agent( final String name ) throws SQLException {
        final Agent a = svc.registry().registerAgent( admin,
            new AgentDraft( name, "", "", List.of( "implementer", "reviewer" ) ) ).agent();
        svc.putMember( admin, "PLAT", name, "agent", WorkRole.MEMBER );
        return Actor.agent( a.id(), a.name(), admin );
    }

    /** The delivery a verified request becomes. */
    HookDelivery delivery( final String host ) throws SQLException {
        return new HookDelivery( product.id(), product.key(), host, product.repoUrl(), product.defaultBranch() );
    }

    /** A ready task of project PLAT targeting the product, made by a person; its key is the server's. */
    Item task() throws SQLException {
        return svc.createItem( lena, new ItemDraft( "PLAT", WorkLevel.TASK, null, "wk", WorkKind.SOFTWARE, "An item",
            "", null, null, null, "implementer", null, null ) ).item();
    }

    Item epic() throws SQLException {
        return svc.createItem( lena, new ItemDraft( "PLAT", WorkLevel.EPIC, null, null, WorkKind.SOFTWARE, "An epic",
            "", null, null, null, null, null, null ) ).item();
    }

    Item set( final Actor by, final Item i, final String field, final Object value ) throws SQLException {
        final Map< String, Object > changes = new HashMap<>();
        changes.put( field, value );
        final int version = svc.item( admin, i.key() ).item().version();
        return svc.updateItem( by, i.key(), new ItemPatch( version, changes, null ) ).item();
    }

    long count( final String sql ) throws SQLException {
        return WorkAgentFixtures.scalar( ds, sql );
    }

    void exec( final String sql ) throws SQLException {
        WorkAgentFixtures.exec( ds, sql );
    }

    /** All changes of the item, oldest first. */
    List< Change > changes( final Item item ) throws SQLException {
        return store.changes().forItem( item.id() );
    }

    /** The row of the only change of the item. */
    Change only( final Item item ) throws SQLException {
        final List< Change > all = changes( item );
        if ( all.size() != 1 ) {
            throw new AssertionError( "expected one change, found " + all.size() );
        }
        return all.get( 0 );
    }
}
