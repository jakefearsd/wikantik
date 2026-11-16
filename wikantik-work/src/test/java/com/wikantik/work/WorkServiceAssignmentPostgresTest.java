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
import java.time.Clock;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;

/** One assignment target per item, through the real service and store. */
@RequiresPostgres
class WorkServiceAssignmentPostgresTest {

    private final Actor lena = Actor.human( "lena", Set.of(), true );

    private DataSource ds;
    private JdbcWorkStore store;
    private WorkService service;
    private long agentId;

    @BeforeEach
    void setUp() throws SQLException {
        ds = PostgresTestDb.createDataSource();
        PostgresTestDb.truncate( WorkAgentFixtures.TABLES );
        store = new JdbcWorkStore( ds );
        final Project plat = store.createProject( "PLAT", "Platform", "" );
        store.putMember( plat.id(), "lena", "user", WorkRole.LEAD, lena );
        WorkAgentFixtures.queue( ds, "implementer" );
        agentId = WorkAgentFixtures.agent( ds, "codex" );
        service = new WorkService( store, md -> md, new WorkDirectory() {
            @Override public Set< String > groupsOf( final String login ) { return Set.of(); }
            @Override public boolean exists( final String login ) { return true; }
            @Override public String displayName( final String login ) { return login; }
        }, Clock.systemUTC() );
    }

    @Test
    void assigningAPersonToAClaimedQueueItemClearsQueueAndClaim() throws SQLException {
        service.createItem( lena, new ItemDraft( "PLAT", WorkLevel.TASK, null, null,
            WorkKind.SOFTWARE, "t", "", null, null, null, "implementer" ) );
        final Item item = store.item( "PLAT-1" ).orElseThrow();
        WorkAgentFixtures.exec( ds, "UPDATE work_item SET claimed_by_agent = " + agentId
            + ", claimed_at = NOW(), claim_expires_at = NOW() + INTERVAL '1 hour' WHERE id = " + item.id() );
        service.updateItem( lena, "PLAT-1", new ItemPatch( item.version(), Map.of( "assignee", "lena" ), null ) );
        final Item after = store.item( "PLAT-1" ).orElseThrow();
        assertNull( after.queue() );
        assertEquals( "lena", after.assignee() );
        assertNull( after.claimedByAgent() );
        assertNull( after.claimExpiresAt() );
        final List< WorkEvent > events = store.itemEvents( item.id() );
        final List< WorkEvent > assigned = events.stream().filter( e -> WorkEvent.ASSIGNED.equals( e.type() ) ).toList();
        assertEquals( 1, assigned.size() );
        assertEquals( "queue:implementer", assigned.get( 0 ).fromValue() );
        assertEquals( "lena", assigned.get( 0 ).toValue() );
        final List< WorkEvent > released = events.stream().filter( e -> WorkEvent.RELEASED.equals( e.type() ) ).toList();
        assertEquals( 1, released.size() );
        assertEquals( "agent:" + agentId, released.get( 0 ).fromValue() );
        assertEquals( "reassigned", released.get( 0 ).note() );
    }

    @Test
    void createWithPersonAndQueueIsRejectedAndWritesNothing() throws SQLException {
        final WorkValidationException e = assertThrows( WorkValidationException.class, () -> service.createItem( lena,
            new ItemDraft( "PLAT", WorkLevel.TASK, null, null, WorkKind.SOFTWARE, "t", "", null, null, "lena",
                "implementer" ) ) );
        assertEquals( "queue", e.field() );
        assertEquals( "Assign a person or a queue, not both.", e.getMessage() );
        assertEquals( 0, WorkAgentFixtures.scalar( ds, "SELECT COUNT(*) FROM work_item" ) );
    }
}
