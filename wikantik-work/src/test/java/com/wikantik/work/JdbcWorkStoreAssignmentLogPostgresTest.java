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
import java.sql.SQLException;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** The assignment log's queries over real PostgreSQL: the type filter, the cross-project merge and the cursor. */
@RequiresPostgres
class JdbcWorkStoreAssignmentLogPostgresTest {

    private static final Instant T0 = Instant.parse( "2026-11-16T10:00:00Z" );

    private DataSource ds;
    private JdbcWorkStore store;
    private Project plat;
    private Project web;

    @BeforeEach
    void setUp() throws SQLException {
        ds = PostgresTestDb.createDataSource();
        PostgresTestDb.truncate( WorkAgentFixtures.TABLES );
        store = new JdbcWorkStore( ds );
        plat = store.createProject( "PLAT", "Platform", "" );
        web = store.createProject( "WEB", "Website", "" );
    }

    private void event( final Project p, final Item item, final Instant at, final String type, final String actor,
                        final String kind, final String from, final String to ) throws SQLException {
        WorkAgentFixtures.exec( ds, "INSERT INTO work_event (project_id, item_id, at, actor, actor_kind, type, "
            + "from_value, to_value) VALUES (" + p.id() + "," + ( item == null ? "NULL" : item.id() ) + ",'" + at + "','"
            + actor + "','" + kind + "','" + type + "'," + quote( from ) + "," + quote( to ) + ")" );
    }

    private static String quote( final String s ) {
        return s == null ? "NULL" : "'" + s + "'";
    }

    private static List< Long > ids( final List< LogEntry > entries ) {
        return entries.stream().map( e -> e.event().id() ).toList();
    }

    @Test
    void typesFilterReturnsOnlyThoseTypes() throws SQLException {
        event( plat, null, T0, "assigned", "lena", "user", null, "queue:x" );
        event( plat, null, T0.plusSeconds( 1 ), "commented", "lena", "user", null, null );
        event( plat, null, T0.plusSeconds( 2 ), "claimed", "a", "agent", "ready", "in_progress" );
        event( plat, null, T0.plusSeconds( 3 ), "created", "lena", "user", null, null );

        final List< WorkEvent > claims = store.projectEvents( plat.id(), null, null, null, Set.of( "claimed" ), true, 50 );
        assertEquals( List.of( "claimed" ), claims.stream().map( WorkEvent::type ).toList() );
        assertEquals( 4, store.projectEvents( plat.id(), null, null, null, null, true, 50 ).size() );
        assertEquals( 4, store.projectEvents( plat.id(), null, null, null, Set.of(), true, 50 ).size(), "empty means all" );
        final List< LogEntry > log = store.eventsAcross( List.of( plat.id() ), null, null, null, null, null, 50 );
        assertEquals( List.of( "claimed", "assigned" ), log.stream().map( e -> e.event().type() ).toList(),
            "null types in the log means the log's types, never comments or creations" );
    }

    @Test
    void eventsAcrossMergesProjectsNewestFirst() throws SQLException {
        event( plat, null, T0, "assigned", "lena", "user", null, "queue:a" );
        event( web, null, T0.plusSeconds( 5 ), "assigned", "lena", "user", null, "queue:b" );
        event( plat, null, T0.plusSeconds( 9 ), "assigned", "lena", "user", null, "queue:c" );
        event( web, null, T0.plusSeconds( 7 ), "assigned", "lena", "user", null, "queue:d" );

        final List< LogEntry > all = store.eventsAcross( List.of( plat.id(), web.id() ), null, null, null, null, null, 50 );
        assertEquals( List.of( "queue:c", "queue:d", "queue:b", "queue:a" ),
            all.stream().map( e -> e.event().toValue() ).toList() );
        final List< LogEntry > onlyWeb = store.eventsAcross( List.of( web.id() ), null, null, null, null, null, 50 );
        assertEquals( List.of( "queue:d", "queue:b" ), onlyWeb.stream().map( e -> e.event().toValue() ).toList() );
    }

    @Test
    void eventsAcrossPagesWithoutLossOnEqualTimestamps() throws SQLException {
        event( plat, null, T0, "assigned", "lena", "user", null, "queue:1" );
        event( web, null, T0, "assigned", "lena", "user", null, "queue:2" );
        event( plat, null, T0, "assigned", "lena", "user", null, "queue:3" );
        event( web, null, T0.minusSeconds( 60 ), "assigned", "lena", "user", null, "queue:old" );

        final List< Long > seen = new ArrayList<>();
        Instant before = null;
        Long beforeId = null;
        for ( int guard = 0; guard < 10; guard++ ) {
            final List< LogEntry > page = store.eventsAcross( List.of( plat.id(), web.id() ), before, beforeId, null,
                null, null, 2 );
            if ( page.isEmpty() ) {
                break;
            }
            page.forEach( e -> seen.add( e.event().id() ) );
            final WorkEvent last = page.get( page.size() - 1 ).event();
            before = last.at();
            beforeId = last.id();
        }
        assertEquals( 4, seen.size(), "every event exactly once: " + seen );
        assertEquals( 4, new HashSet<>( seen ).size() );
        assertTrue( seen.get( 0 ) > seen.get( 1 ) && seen.get( 1 ) > seen.get( 2 ), "ties come newest id first" );
    }

    @Test
    void emptyProjectListReturnsEmpty() throws SQLException {
        event( plat, null, T0, "assigned", "lena", "user", null, "queue:1" );
        assertTrue( store.eventsAcross( List.of(), null, null, null, null, null, 50 ).isEmpty() );
        assertTrue( store.eventsAcross( null, null, null, null, null, null, 50 ).isEmpty() );
    }

    @Test
    void onlyAgentMembershipIsInTheLog() throws SQLException {
        event( plat, null, T0, "member", "lena", "user", null, "user:bob:member" );
        event( plat, null, T0.plusSeconds( 1 ), "member", "lena", "user", null, "agent:7:member" );
        event( plat, null, T0.plusSeconds( 2 ), "member", "lena", "user", "agent:7:member", null );
        event( plat, null, T0.plusSeconds( 3 ), "member", "lena", "user", null, "group:eng:viewer" );

        final List< LogEntry > log = store.eventsAcross( List.of( plat.id() ), null, null, Set.of( "member" ), null,
            null, 50 );
        assertEquals( List.of( "agent:7:member", "agent:7:member" ),
            log.stream().map( e -> e.event().toValue() == null ? e.event().fromValue() : e.event().toValue() ).toList() );
        assertEquals( 4, store.projectEvents( plat.id(), null, null, null, Set.of( "member" ), true, 50 ).size(),
            "the project feed's type filter is not narrowed" );
    }

    @Test
    void actorKindAndAgentNarrowTheLog() throws SQLException {
        WorkAgentFixtures.exec( ds, "INSERT INTO work_queue (key, name) VALUES ('qq','qq')" );
        final long a = WorkAgentFixtures.agent( ds, "agent-a" );
        final long b = WorkAgentFixtures.agent( ds, "agent-b" );
        event( plat, null, T0, "assigned", "lena", "user", null, "queue:qq" );
        WorkAgentFixtures.exec( ds, "INSERT INTO work_event (project_id, at, actor, actor_kind, agent_id, type, "
            + "from_value, to_value) VALUES (" + plat.id() + ",'" + T0.plusSeconds( 1 ) + "','agent-a','agent'," + a
            + ",'claimed','ready','in_progress')" );
        WorkAgentFixtures.exec( ds, "INSERT INTO work_event (project_id, at, actor, actor_kind, agent_id, type, "
            + "from_value, to_value) VALUES (" + plat.id() + ",'" + T0.plusSeconds( 2 ) + "','agent-b','agent'," + b
            + ",'claimed','ready','in_progress')" );
        event( plat, null, T0.plusSeconds( 3 ), "released", "lena", "user", "agent:" + a, "ready" );

        final List< Long > all = List.of( plat.id() );
        assertEquals( 4, store.eventsAcross( all, null, null, null, null, null, 50 ).size() );
        assertEquals( List.of( "released", "assigned" ), store.eventsAcross( all, null, null, null, Actor.Kind.HUMAN,
            null, 50 ).stream().map( e -> e.event().type() ).toList() );
        assertEquals( 2, store.eventsAcross( all, null, null, null, Actor.Kind.AGENT, null, 50 ).size() );
        assertEquals( List.of( "released", "claimed" ), store.eventsAcross( all, null, null, null, null, a, 50 )
            .stream().map( e -> e.event().type() ).toList(),
            "an agent's own events and the release of its claim by someone else" );
    }

    @Test
    void anExpiryIsASystemEventShownWhateverTheWhoFilter() throws SQLException {
        final long a = WorkAgentFixtures.agent( ds, "agent-a" );
        event( plat, null, T0, "assigned", "lena", "user", null, "queue:qq" );
        WorkAgentFixtures.exec( ds, "INSERT INTO work_event (project_id, at, actor, actor_kind, agent_id, type, "
            + "from_value, to_value) VALUES (" + plat.id() + ",'" + T0.plusSeconds( 1 ) + "','agent-a','agent'," + a
            + ",'claimed','ready','in_progress')" );
        WorkAgentFixtures.exec( ds, "INSERT INTO work_event (project_id, at, actor, actor_kind, agent_id, type, "
            + "from_value, to_value) VALUES (" + plat.id() + ",'" + T0.plusSeconds( 2 ) + "','agent-a','agent'," + a
            + ",'claim_expired','in_progress','ready')" );

        final List< Long > all = List.of( plat.id() );
        assertEquals( List.of( "claim_expired", "assigned" ), store.eventsAcross( all, null, null, null,
            Actor.Kind.HUMAN, null, 50 ).stream().map( e -> e.event().type() ).toList(), "people see expiries too" );
        assertEquals( List.of( "claim_expired", "claimed" ), store.eventsAcross( all, null, null, null,
            Actor.Kind.AGENT, null, 50 ).stream().map( e -> e.event().type() ).toList() );
        assertEquals( 3, store.eventsAcross( all, null, null, null, null, null, 50 ).size() );
    }
}
