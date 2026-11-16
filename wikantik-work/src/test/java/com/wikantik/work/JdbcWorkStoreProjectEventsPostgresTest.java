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

import java.sql.SQLException;
import java.time.Instant;
import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Membership, settings and archive changes leave a project-level event behind (C1), against real PostgreSQL. */
@RequiresPostgres
class JdbcWorkStoreProjectEventsPostgresTest {

    private JdbcWorkStore store;
    private Project plat;

    @BeforeEach
    void setUp() throws SQLException {
        store = new JdbcWorkStore( PostgresTestDb.createDataSource() );
        PostgresTestDb.truncate( WorkAgentFixtures.TABLES );
        plat = store.createProject( "PLAT", "Platform", "old text" );
    }

    /** Oldest first. */
    private List< WorkEvent > events() throws SQLException {
        return store.projectEvents( plat.id(), null, null, null, 200 ).reversed();
    }

    @Test
    void addChangeAndRemoveMemberEachWriteOneMemberEvent() throws SQLException {
        store.putMember( plat.id(), "bob", "user", WorkRole.MEMBER, Actor.human( "lena", java.util.Set.of(), false ) );
        store.putMember( plat.id(), "bob", "user", WorkRole.LEAD, Actor.human( "lena", java.util.Set.of(), false ) );
        store.removeMember( plat.id(), "bob", "user", Actor.human( "root", java.util.Set.of(), false ) );

        final List< WorkEvent > e = events();
        assertEquals( 3, e.size() );
        e.forEach( ev -> {
            assertEquals( WorkEvent.MEMBER, ev.type() );
            assertNull( ev.itemId() );
            assertNull( ev.itemKey() );
        } );
        assertNull( e.get( 0 ).fromValue() );
        assertEquals( "user:bob:member", e.get( 0 ).toValue() );
        assertEquals( "lena", e.get( 0 ).actor() );
        assertEquals( "user:bob:member", e.get( 1 ).fromValue() );
        assertEquals( "user:bob:lead", e.get( 1 ).toValue() );
        assertEquals( "user:bob:lead", e.get( 2 ).fromValue() );
        assertNull( e.get( 2 ).toValue() );
        assertEquals( "root", e.get( 2 ).actor() );
        assertTrue( store.members( plat.id() ).isEmpty(), "the grant is deleted; the event is the history" );
    }

    @Test
    void puttingTheSameRoleOrRemovingANonMemberWritesNothing() throws SQLException {
        store.putMember( plat.id(), "editors", "group", WorkRole.VIEWER, Actor.human( "lena", java.util.Set.of(), false ) );
        store.putMember( plat.id(), "editors", "group", WorkRole.VIEWER, Actor.human( "lena", java.util.Set.of(), false ) );
        store.removeMember( plat.id(), "nobody", "user", Actor.human( "lena", java.util.Set.of(), false ) );
        assertEquals( 1, events().size() );
        assertEquals( "group:editors:viewer", events().get( 0 ).toValue() );
    }

    @Test
    void settingsChangesWriteOneEventPerChangedField() throws SQLException {
        store.updateProject( plat.id(), "Platform Team", "old text", Autonomy.SUPERVISED, Actor.human( "lena", java.util.Set.of(), false ) );
        final List< WorkEvent > e = events();
        assertEquals( 2, e.size(), "the unchanged description writes nothing" );
        assertEquals( List.of( "name", "default_autonomy" ), e.stream().map( WorkEvent::note ).toList() );
        e.forEach( ev -> assertEquals( WorkEvent.SETTINGS, ev.type() ) );
        assertEquals( "Platform", e.get( 0 ).fromValue() );
        assertEquals( "Platform Team", e.get( 0 ).toValue() );
        assertEquals( "delegated", e.get( 1 ).fromValue() );
        assertEquals( "supervised", e.get( 1 ).toValue() );
        assertEquals( "Platform Team", store.project( "PLAT" ).orElseThrow().name() );

        store.updateProject( plat.id(), "Platform Team", "old text", Autonomy.SUPERVISED, Actor.human( "lena", java.util.Set.of(), false ) );
        assertEquals( 2, events().size(), "no change, no event" );
    }

    @Test
    void archiveWritesASettingsEventNotedArchived() throws SQLException {
        final Instant at = Instant.parse( "2026-11-15T10:00:00Z" );
        store.archiveProject( plat.id(), at, Actor.human( "root", java.util.Set.of(), false ) );
        final List< WorkEvent > e = events();
        assertEquals( 1, e.size() );
        assertEquals( WorkEvent.SETTINGS, e.get( 0 ).type() );
        assertEquals( "archived", e.get( 0 ).note() );
        assertNull( e.get( 0 ).fromValue() );
        assertEquals( at.toString(), e.get( 0 ).toValue() );
        assertEquals( Optional.of( at ), store.project( "PLAT" ).map( Project::archivedAt ) );

        store.archiveProject( plat.id(), at.plusSeconds( 60 ), Actor.human( "root", java.util.Set.of(), false ) );
        assertEquals( 1, events().size(), "archiving twice changes nothing" );
    }

    @Test
    void projectEventsCanBeNarrowedToOneItem() throws SQLException {
        final Item a = store.createItem( plat, JdbcWorkStoreItemsPostgresTest.draft( "a" ), Actor.human( "lena", java.util.Set.of(), false ) );
        store.createItem( plat, JdbcWorkStoreItemsPostgresTest.draft( "b" ), Actor.human( "lena", java.util.Set.of(), false ) );
        store.putMember( plat.id(), "bob", "user", WorkRole.MEMBER, Actor.human( "lena", java.util.Set.of(), false ) );
        assertEquals( 3, store.projectEvents( plat.id(), null, null, null, 50 ).size() );
        final List< WorkEvent > onlyA = store.projectEvents( plat.id(), null, null, a.id(), 50 );
        assertEquals( 1, onlyA.size() );
        assertEquals( "PLAT-1", onlyA.get( 0 ).itemKey() );
    }

    @Test
    void theFirstPageHasNoTimeCutoff() throws SQLException {
        store.putMember( plat.id(), "bob", "user", WorkRole.MEMBER, Actor.human( "lena", java.util.Set.of(), false ) );
        try ( var c = PostgresTestDb.createDataSource().getConnection();
              var ps = c.prepareStatement( "UPDATE work_event SET at = NOW() + INTERVAL '1 hour'" ) ) {
            ps.executeUpdate();
        }
        assertEquals( 1, store.projectEvents( plat.id(), null, null, null, 50 ).size(),
            "an event stamped ahead of this clock still shows on the first page" );
    }

    @Test
    void anAgentActorIsRecordedAsAnAgent() throws SQLException {
        final long botId = WorkAgentFixtures.agent( PostgresTestDb.createDataSource(), "bot" );
        store.putMember( plat.id(), "bob", "user", WorkRole.MEMBER, Actor.agent( botId, "bot" ) );
        assertEquals( Actor.Kind.AGENT, events().get( 0 ).actorKind() );
        assertEquals( botId, events().get( 0 ).agentId() );
    }
}
