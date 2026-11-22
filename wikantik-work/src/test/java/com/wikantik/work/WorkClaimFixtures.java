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

import javax.sql.DataSource;
import java.sql.SQLException;
import java.util.List;
import java.util.Set;

/** A project, a queue, two agents serving it and item helpers, shared by the claim tests. */
final class WorkClaimFixtures {

    static final Actor LENA = Actor.human( "lena", Set.of(), false );

    final DataSource ds;
    final JdbcWorkStore store;
    final Project plat;
    final Agent a;
    final Agent b;
    final Actor actorA;
    final Actor actorB;

    WorkClaimFixtures( final DataSource ds ) throws SQLException {
        this.ds = ds;
        this.store = new JdbcWorkStore( ds );
        plat = store.createProject( "PLAT", "Platform", "" );
        store.createQueue( "implementer", "Implementer", "" );
        store.createQueue( "reviewer", "Reviewer", "" );
        a = agent( "agent-a", List.of( "implementer" ) );
        b = agent( "agent-b", List.of( "implementer" ) );
        actorA = WorkAgentFixtures.sponsored( a.id(), a.name() );
        actorB = WorkAgentFixtures.sponsored( b.id(), b.name() );
        store.putMember( plat.id(), String.valueOf( a.id() ), "agent", WorkRole.MEMBER, LENA );
        store.putMember( plat.id(), String.valueOf( b.id() ), "agent", WorkRole.MEMBER, LENA );
    }

    Agent agent( final String name, final List< String > queues ) throws SQLException {
        // Sponsored on its row by the same admin WorkAgentFixtures.sponsored resolves, as the claim lock requires.
        return store.registerAgent( name, "", "", queues, "sponsor-root" );
    }

    /** A task with the given status, priority, due date, person and queue (the last two exclusive). */
    Item create( final Project project, final String title, final WorkStatus status, final int priority,
                 final java.time.LocalDate dueOn, final String assignee, final String queue ) throws SQLException {
        final Item draft = new Item( 0, 0, null, WorkLevel.TASK, null, null, WorkKind.SOFTWARE, title,
            "body of " + title, status, priority, 0, dueOn, assignee, null, null, null, null, 0, null, null, null,
            queue, null, null, null, null, null, null, null, null );
        return store.createItem( project, draft, LENA );
    }

    Item ready( final String title ) throws SQLException {
        return create( plat, title, WorkStatus.READY, 3, null, null, "implementer" );
    }

    Item reload( final Item item ) throws SQLException {
        return store.itemById( item.id() ).orElseThrow();
    }

    List< WorkEvent > events( final Item item, final String type ) throws SQLException {
        return store.itemEvents( item.id() ).stream().filter( e -> e.type().equals( type ) ).toList();
    }

    void expireNow( final Item item ) throws SQLException {
        WorkAgentFixtures.exec( ds, "UPDATE work_item SET claim_expires_at = now() - INTERVAL '1 minute' WHERE id = "
            + item.id() );
    }

    /** Makes the agent a member of the project, as the service does before any claim. */
    void member( final Agent agent ) throws SQLException {
        store.putMember( plat.id(), String.valueOf( agent.id() ), "agent", WorkRole.MEMBER, LENA );
    }

    /** Waits (bounded) until some backend is blocked by the backend {@code holderPid}; fails otherwise. */
    static void awaitBlockedBy( final DataSource ds, final long holderPid ) throws Exception {
        final long deadline = System.nanoTime() + java.util.concurrent.TimeUnit.SECONDS.toNanos( 20 );
        while ( System.nanoTime() < deadline ) {
            if ( WorkAgentFixtures.scalar( ds, "SELECT COUNT(*) FROM pg_stat_activity WHERE " + holderPid
                    + " = ANY( pg_blocking_pids( pid ) )" ) > 0 ) {
                return;
            }
            Thread.sleep( 25 );
        }
        throw new AssertionError( "nothing ever waited on backend " + holderPid );
    }
}
