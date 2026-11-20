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
import com.wikantik.work.WorkClaimsService.Handoff;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import javax.sql.DataSource;
import java.sql.SQLException;
import java.time.Clock;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The owner approved agent-created items on condition that they start as drafts for a person to refine: an agent never
 * moves a draft forward, by any service path. A person does, and an agent may still edit, comment on and pass on a
 * draft that stays a draft.
 */
@RequiresPostgres
class WorkDraftRulePostgresTest {

    private static final String RULE = "A person moves a draft forward.";

    private final Actor admin = Actor.human( "admin", Set.of(), true );
    private final Actor lena = Actor.human( "lena", Set.of(), false );
    private DataSource ds;
    private WorkService svc;
    private Actor agent;

    @BeforeEach
    void setUp() throws SQLException {
        ds = PostgresTestDb.createDataSource();
        PostgresTestDb.truncate( WorkAgentFixtures.TABLES );
        svc = new WorkService( new JdbcWorkStore( ds ), md -> md, new WorkDirectory() {
            @Override public Set< String > groupsOf( final String login ) { return Set.of(); }
            @Override public boolean exists( final String login ) { return true; }
            @Override public String displayName( final String login ) { return login; }
            @Override public Optional< String > loginForDisplayName( final String name ) {
                return "Lena Lead".equals( name ) ? Optional.of( "lena" ) : Optional.empty();
            }
        }, Clock.systemUTC(), WorkSettings.DEFAULTS, null );
        svc.createProject( admin, "PLAT", "Platform", "" );
        svc.putMember( admin, "PLAT", "lena", "user", WorkRole.LEAD );
        svc.registry().createQueue( admin, "implementer", "Implementer", "" );
        final Agent a = svc.registry().registerAgent( admin, new AgentDraft( "builder", "", "", List.of( "implementer" ) ) ).agent();
        svc.putMember( admin, "PLAT", "builder", "agent", WorkRole.MEMBER );
        agent = Actor.agent( a.id(), a.name(), admin );
    }

    private Item draftByAgent() throws SQLException {
        return svc.createItem( agent, new ItemDraft( "PLAT", WorkLevel.TASK, null, null, WorkKind.SOFTWARE, "A draft", "",
            null, null, null, "implementer" ) ).item();
    }

    private long eventCount() throws SQLException {
        return WorkAgentFixtures.scalar( ds, "SELECT count(*) FROM work_event" );
    }

    @Test
    void anAgentsNewItemStartsAsADraft() throws SQLException {
        assertEquals( WorkStatus.DRAFT, draftByAgent().status() );
    }

    @Test
    void aPersonsNewTaskOrStoryStartsReadyAndAnEpicAsADraft() throws SQLException {
        final Item task = svc.createItem( lena, new ItemDraft( "PLAT", WorkLevel.TASK, null, null, WorkKind.SOFTWARE,
            "A task", "", null, null, null, "implementer" ) ).item();
        final Item story = svc.createItem( lena, new ItemDraft( "PLAT", WorkLevel.STORY, null, null, WorkKind.SOFTWARE,
            "A story", "", null, null, null, null ) ).item();
        final Item epic = svc.createItem( lena, new ItemDraft( "PLAT", WorkLevel.EPIC, null, null, WorkKind.SOFTWARE,
            "An epic", "", null, null, null, null ) ).item();
        assertEquals( WorkStatus.READY, task.status() );
        assertEquals( WorkStatus.READY, story.status() );
        assertEquals( WorkStatus.DRAFT, epic.status(), "an epic has no Ready status" );
        assertEquals( WorkStatus.IN_PROGRESS, svc.claims().claim( agent, task.key() ).view().item().status(),
            "a person's queued task is available to agents at once" );
    }

    @Test
    void agentCannotMoveADraftForwardByUpdate() throws SQLException {
        final Item d = draftByAgent();
        final WorkDeniedException e = assertThrows( WorkDeniedException.class, () -> svc.updateItem( agent, d.key(),
            new ItemPatch( d.version(), Map.of( "status", "ready" ), "promote" ) ) );
        assertEquals( RULE, e.reason() );
        assertThrows( WorkDeniedException.class, () -> svc.updateItem( agent, d.key(),
            new ItemPatch( d.version(), Map.of( "status", "bogus" ), null ) ), "even an invalid status is a move" );
    }

    @Test
    void agentCannotMoveADraftForwardByHandoff() throws SQLException {
        final Item d = draftByAgent();
        final WorkDeniedException e = assertThrows( WorkDeniedException.class, () -> svc.claims().handoff( agent, d.key(),
            new Handoff( d.version(), WorkStatus.READY, null, "implementer", "promote" ) ) );
        assertEquals( RULE, e.reason() );
    }

    @Test
    void agentMayEditAndHandOffADraftThatStaysDraft() throws SQLException {
        final Item d = draftByAgent();
        final Item edited = svc.updateItem( agent, d.key(), new ItemPatch( d.version(),
            Map.of( "title", "Better title", "status", "draft", "priority", 2 ), "tidy" ) ).item();
        assertEquals( "Better title", edited.title() );
        assertEquals( WorkStatus.DRAFT, edited.status() );
        svc.addComment( agent, d.key(), "Please refine" );
        final Item handed = svc.claims().handoff( agent, d.key(), new Handoff( edited.version(), WorkStatus.DRAFT,
            "Lena Lead", null, "Ready for you to refine and promote." ) ).item();
        assertEquals( "lena", handed.assignee() );
        assertEquals( WorkStatus.DRAFT, handed.status() );
        final Item kept = svc.claims().handoff( lena, d.key(), new Handoff( handed.version(), null, null, "implementer",
            "back to the queue" ) ).item();
        assertEquals( WorkStatus.DRAFT, kept.status() );
    }

    @Test
    void personMovesTheSameDraftForward() throws SQLException {
        final Item d = draftByAgent();
        final Item moved = svc.updateItem( lena, d.key(), new ItemPatch( d.version(), Map.of( "status", "ready" ), null ) ).item();
        assertEquals( WorkStatus.READY, moved.status() );
        assertTrue( svc.claims().claim( agent, d.key() ).view().item().status() == WorkStatus.IN_PROGRESS );
    }

    @Test
    void personHandsADraftOnWithANewStatus() throws SQLException {
        final Item d = draftByAgent();
        final Item moved = svc.claims().handoff( lena, d.key(), new Handoff( d.version(), WorkStatus.READY,
            "Lena Lead", null, "go" ) ).item();
        assertEquals( WorkStatus.READY, moved.status() );
    }

    @Test
    void deniedDraftMoveWritesNoEvent() throws SQLException {
        final Item d = draftByAgent();
        final long before = eventCount();
        assertThrows( WorkDeniedException.class, () -> svc.updateItem( agent, d.key(),
            new ItemPatch( d.version(), Map.of( "status", "ready" ), null ) ) );
        assertThrows( WorkDeniedException.class, () -> svc.claims().handoff( agent, d.key(),
            new Handoff( d.version(), WorkStatus.REVIEW, null, "implementer", "n" ) ) );
        assertEquals( before, eventCount() );
        final Item now = svc.item( lena, d.key() ).item();
        assertEquals( d.version(), now.version() );
        assertEquals( WorkStatus.DRAFT, now.status() );
    }

    @Test
    void hiddenStillGivesNotFoundNotTheDraftRule() throws SQLException {
        svc.createProject( admin, "SECRET", "Secret", "" );
        final Item hidden = svc.createItem( admin, new ItemDraft( "SECRET", WorkLevel.TASK, null, null, WorkKind.SOFTWARE,
            "hid", "", null, null, null, null ) ).item();
        assertThrows( WorkNotFoundException.class, () -> svc.updateItem( agent, hidden.key(),
            new ItemPatch( 1, Map.of( "status", "ready" ), null ) ) );
        assertThrows( WorkNotFoundException.class, () -> svc.claims().handoff( agent, hidden.key(),
            new Handoff( 1, WorkStatus.READY, null, "implementer", "n" ) ) );
    }

    @Test
    void anAgentCancellingADraftIsToldAPersonDoesThat() throws SQLException {
        final Item d = draftByAgent();
        final WorkDeniedException e = assertThrows( WorkDeniedException.class, () -> svc.updateItem( agent, d.key(),
            new ItemPatch( d.version(), Map.of( "status", "cancelled" ), "not needed" ) ) );
        assertEquals( "A person cancels or moves a draft.", e.reason() );
    }

    @Test
    void anAgentMayMoveAnItemIntoDraft() throws SQLException {
        final Item d = draftByAgent();
        final Item refining = svc.updateItem( lena, d.key(), new ItemPatch( d.version(), Map.of( "status", "refining" ),
            null ) ).item();
        final Item back = svc.updateItem( agent, d.key(), new ItemPatch( refining.version(), Map.of( "status", "draft" ),
            "needs more thought" ) ).item();
        assertEquals( WorkStatus.DRAFT, back.status() );
    }
}
