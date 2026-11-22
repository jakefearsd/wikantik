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
import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** The intake store's reads against real PostgreSQL. */
@RequiresPostgres
class JdbcWorkIntakePostgresTest {

    private static final Actor ROOT = Actor.human( "root", Set.of(), true );

    private JdbcWorkStore store;
    private WorkIntakeStore intake;
    private Project plat;
    private Project ops;

    @BeforeEach
    void setUp() throws SQLException {
        PostgresTestDb.truncate( WorkAgentFixtures.TABLES );
        store = new JdbcWorkStore( PostgresTestDb.createDataSource() );
        intake = store.notifyStores().intake();
        plat = store.createProject( "PLAT", "Platform", "" );
        ops = store.createProject( "OPS", "Operations", "" );
    }

    private static Actor person( final String login, final String... groups ) {
        return Actor.human( login, Set.of( groups ), false );
    }

    @Test
    void reportedByIgnoresItemsAnAgentOfTheSameNameReported() throws SQLException {
        final long agent = store.registerAgent( "rita", "", "", List.of(), "root" ).id();
        final Item mine = store.createItem( plat, JdbcWorkStoreItemsPostgresTest.draft( "mine" ), person( "rita" ) );
        store.createItem( plat, JdbcWorkStoreItemsPostgresTest.draft( "an agent named rita" ), Actor.agent( agent, "rita" ) );
        store.createItem( plat, JdbcWorkStoreItemsPostgresTest.draft( "someone else's" ), person( "gus" ) );
        store.createItem( ops, JdbcWorkStoreItemsPostgresTest.draft( "in another project" ), person( "rita" ) );
        assertEquals( List.of( mine.key() ), intake.reportedBy( "rita", Set.of( plat.id() ), 10 ).stream().map( Item::key ).toList() );
        assertEquals( 2, intake.reportedBy( "rita", Set.of( plat.id(), ops.id() ), 10 ).size() );
        assertEquals( 1, intake.reportedBy( "rita", Set.of( plat.id(), ops.id() ), 1 ).size(), "the limit applies" );
        assertEquals( List.of(), intake.reportedBy( "rita", Set.of(), 10 ) );
        assertEquals( List.of(), intake.reportedBy( null, Set.of( plat.id() ), 10 ) );
    }

    @Test
    void reportedByListsTheNewestActivityFirst() throws SQLException {
        final Item first = store.createItem( plat, JdbcWorkStoreItemsPostgresTest.draft( "first" ), person( "rita" ) );
        final Item second = store.createItem( plat, JdbcWorkStoreItemsPostgresTest.draft( "second" ), person( "rita" ) );
        assertEquals( List.of( second.key(), first.key() ),
            intake.reportedBy( "rita", Set.of( plat.id() ), 10 ).stream().map( Item::key ).toList() );
    }

    @Test
    void reportableProjectsListsReporterViewerAndGroupRows() throws SQLException {
        store.putMember( plat.id(), "rita", "user", WorkRole.REPORTER, ROOT );
        store.putMember( ops.id(), "Customers", "group", WorkRole.VIEWER, ROOT );
        final Project archived = store.createProject( "OLD", "Old", "" );
        store.putMember( archived.id(), "rita", "user", WorkRole.REPORTER, ROOT );
        store.archiveProject( archived.id(), java.time.Instant.parse( "2026-01-01T00:00:00Z" ), ROOT );
        assertEquals( List.of( "OPS", "PLAT" ), intake.reportableProjects( person( "rita", "Customers" ) ).stream()
            .map( Project::key ).toList() );
        assertEquals( List.of( "PLAT" ), intake.reportableProjects( person( "rita" ) ).stream().map( Project::key ).toList() );
        assertEquals( List.of(), intake.reportableProjects( person( "mallory" ) ) );
        assertEquals( 2, intake.reportableProjects( ROOT ).size(), "an admin may file into every project that is not archived" );
    }

    @Test
    void anythingButAPersonHasNoReportableProjects() throws SQLException {
        store.putMember( plat.id(), "rita", "user", WorkRole.REPORTER, ROOT );
        final long agent = store.registerAgent( "codex", "", "", List.of(), "root" ).id();
        store.putMember( plat.id(), String.valueOf( agent ), "agent", WorkRole.MEMBER, ROOT );
        assertEquals( List.of(), intake.reportableProjects( Actor.agent( agent, "rita", ROOT ) ) );
        assertEquals( List.of(), intake.reportableProjects( Actor.system( "github" ) ) );
    }

    @Test
    void hasReportersLooksOnlyAtReporterRows() throws SQLException {
        assertFalse( intake.hasReporters( plat.id() ) );
        store.putMember( plat.id(), "lena", "user", WorkRole.LEAD, ROOT );
        assertFalse( intake.hasReporters( plat.id() ) );
        store.putMember( plat.id(), "rita", "user", WorkRole.REPORTER, ROOT );
        assertTrue( intake.hasReporters( plat.id() ) );
        assertFalse( intake.hasReporters( ops.id() ) );
    }
}
