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

import com.wikantik.jdbc.testing.RequiresPostgres;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.sql.SQLException;
import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** The planner at dispatch: Recipients, then the standing check, through the real service and store. */
@RequiresPostgres
class WorkDispatchPostgresTest {

    private NotifyServiceFixture fx;

    @BeforeEach
    void setUp() throws SQLException {
        fx = new NotifyServiceFixture();
        fx.plant();
    }

    @Test
    void anAssigneeHearsOfTheAssignment() throws SQLException {
        final Item item = fx.newItem( NotifyServiceFixture.LENA );
        fx.patch( NotifyServiceFixture.LENA, item, "assignee", "otto" );
        fx.dispatchAll();
        final List< NoticeView > inbox = fx.notices.inbox( NotifyServiceFixture.OTTO, null, 10 );
        assertEquals( 1, inbox.size() );
        assertEquals( NoticeReason.ASSIGNED, inbox.get( 0 ).reason() );
        assertEquals( 0, fx.notices.inbox( NotifyServiceFixture.LENA, null, 10 ).size() );
    }

    @Test
    void watchersHearOfAStatusChange() throws SQLException {
        final Item item = fx.newItem( NotifyServiceFixture.LENA );
        fx.notices.setWatch( NotifyServiceFixture.OTTO, item.key(), WatchState.WATCHING );
        fx.patch( NotifyServiceFixture.LENA, item, "status", "in_progress" );
        fx.dispatchAll();
        final List< NoticeView > inbox = fx.notices.inbox( NotifyServiceFixture.OTTO, null, 10 );
        assertEquals( 1, inbox.size() );
        assertEquals( NoticeReason.WATCHING, inbox.get( 0 ).reason() );
    }

    @Test
    void theActorHearsNothing() throws SQLException {
        final Item item = fx.newItem( NotifyServiceFixture.LENA );
        fx.notices.setWatch( NotifyServiceFixture.LENA, item.key(), WatchState.WATCHING );
        fx.patch( NotifyServiceFixture.LENA, item, "status", "in_progress" );
        fx.dispatchAll();
        assertEquals( 0, fx.rows( "recipient = 'lena'" ) );
    }

    @Test
    void anAgentIsNeverARecipient() throws SQLException {
        final long id = WorkAgentFixtures.agent( fx.ds, "codex" );
        fx.store.putMember( fx.project.id(), String.valueOf( id ), "agent", WorkRole.MEMBER, NotifyServiceFixture.ROOT );
        final Actor agent = WorkAgentFixtures.sponsored( id, "codex" );
        final Item item = fx.newItem( NotifyServiceFixture.LENA );
        final CommentView c = fx.service.addComment( agent, item.key(), "ping @otto" );
        WorkAgentFixtures.exec( fx.ds, "INSERT INTO work_mention (item_id, comment_id, mentioned) VALUES ("
            + item.id() + "," + c.id() + ",'" + agent.login() + "')" );
        fx.dispatchAll();
        assertEquals( 1, fx.rows( "recipient = 'otto'" ) );
        assertEquals( 0, fx.rows( "recipient = '" + agent.login() + "'" ) );
        assertEquals( 0, fx.watches( agent.login() ), "an agent never starts watching" );
        assertEquals( 1, fx.watches( "otto" ), "a member who is mentioned does" );
    }

    @Test
    void aNameWithNoAccountIsNeverARecipientEvenWithARawMention() throws SQLException {
        final long id = WorkAgentFixtures.agent( fx.ds, "scribe-bot" );
        fx.store.putMember( fx.project.id(), String.valueOf( id ), "agent", WorkRole.MEMBER, NotifyServiceFixture.ROOT );
        final Item item = fx.newItem( NotifyServiceFixture.LENA );
        final CommentView c = fx.service.addComment( NotifyServiceFixture.LENA, item.key(), "ping" );
        WorkAgentFixtures.exec( fx.ds, "INSERT INTO work_mention (item_id, comment_id, mentioned) VALUES ("
            + item.id() + "," + c.id() + ",'scribe-bot')" );
        fx.dispatchAll();
        assertEquals( 0, fx.rows( "recipient = 'scribe-bot'" ) );
        assertEquals( 0, fx.watches( "scribe-bot" ) );
    }

    @Test
    void aRealAccountWinsOverAnAgentOfTheSameName() throws SQLException {
        final long id = WorkAgentFixtures.agent( fx.ds, "alice" );   // a collision from before the registry refused them
        fx.store.putMember( fx.project.id(), String.valueOf( id ), "agent", WorkRole.MEMBER, NotifyServiceFixture.ROOT );
        fx.accounts.add( "alice" );
        fx.store.putMember( fx.project.id(), "alice", "user", WorkRole.VIEWER, NotifyServiceFixture.ROOT );
        final Item item = fx.newItem( NotifyServiceFixture.LENA );
        fx.service.addComment( NotifyServiceFixture.LENA, item.key(), "ping @alice" );
        fx.dispatchAll();
        assertEquals( 1, fx.rows( "recipient = 'alice' AND reason = 'mentioned'" ), "Alice is told" );
        assertEquals( 1, fx.watches( "alice" ) );
    }

    @Test
    void aPersonWhoSeesTheProjectOnlyThroughAGroupIsNotified() throws SQLException {
        fx.store.putMember( fx.project.id(), "Crew", "group", WorkRole.VIEWER, NotifyServiceFixture.ROOT );
        fx.accounts.add( "gwen" );
        fx.accountGroups.put( "gwen", Set.of( "Crew" ) );
        fx.accounts.add( "hank" );
        fx.accountGroups.put( "hank", Set.of( "Ghosts" ) );   // a group with no role in the project
        final Item item = fx.newItem( NotifyServiceFixture.LENA );
        fx.service.addComment( NotifyServiceFixture.LENA, item.key(), "ping @gwen and @hank" );
        fx.dispatchAll();
        assertEquals( 1, fx.rows( "recipient = 'gwen' AND reason = 'mentioned'" ) );
        assertEquals( 0, fx.rows( "recipient = 'hank'" ) );
    }

    @Test
    void aMentionOfSomeoneWithNoStandingLeavesNeitherARowNorAWatch() throws SQLException {
        final Item item = fx.newItem( NotifyServiceFixture.LENA );
        fx.service.addComment( NotifyServiceFixture.LENA, item.key(), "ping @mallory" );
        fx.dispatchAll();
        assertEquals( 0, fx.rows( "recipient = 'mallory'" ) );
        assertEquals( 0, fx.watches( "mallory" ) );
    }

    @Test
    void aMemberRemovedBeforeDispatchGetsNoRow() throws SQLException {
        final Item item = fx.newItem( NotifyServiceFixture.LENA );
        fx.patch( NotifyServiceFixture.LENA, item, "assignee", "otto" );
        fx.store.removeMember( fx.project.id(), "otto", "user", NotifyServiceFixture.ROOT );
        fx.dispatchAll();
        assertEquals( 0, fx.rows( "recipient = 'otto'" ) );
        assertEquals( 0, fx.watches( "otto" ), "no stray watch either" );
    }

    @Test
    void anAccountThatIsGoneGetsNoRow() throws SQLException {
        final Item item = fx.newItem( NotifyServiceFixture.LENA );
        fx.patch( NotifyServiceFixture.LENA, item, "assignee", "otto" );
        fx.accounts.remove( "otto" );
        fx.dispatchAll();
        assertEquals( 0, fx.rows( "recipient = 'otto'" ) );
    }

    @Test
    void aGroupLeadHearsAnAgentsUnacknowledgedClose() throws SQLException {
        fx.store.putMember( fx.project.id(), "Crew", "group", WorkRole.LEAD, NotifyServiceFixture.ROOT );
        fx.groupMembers.put( "Crew", Set.of( "vera" ) );
        final Item item = fx.newItem( NotifyServiceFixture.LENA );
        final long agent = WorkAgentFixtures.agent( fx.ds, "codex" );
        WorkAgentFixtures.exec( fx.ds, "UPDATE work_item SET ack_required = TRUE, status = 'done' WHERE id = "
            + item.id() );
        WorkAgentFixtures.exec( fx.ds, "INSERT INTO work_event (project_id, item_id, actor, actor_kind, agent_id, type, "
            + "to_value) VALUES (" + fx.project.id() + "," + item.id() + ",'codex','agent'," + agent
            + ",'status','done')" );
        fx.dispatchAll();
        assertEquals( 1, fx.rows( "recipient = 'lena' AND reason = 'acknowledge'" ) );
        assertEquals( 1, fx.rows( "recipient = 'vera' AND reason = 'acknowledge'" ), "through the group" );
    }

    @Test
    void aDirectoryOutageRollsTheBatchBack() throws SQLException {
        final Item item = fx.newItem( NotifyServiceFixture.LENA );
        fx.patch( NotifyServiceFixture.LENA, item, "assignee", "otto" );
        fx.directoryDown = true;
        fx.awaitWindow();
        assertThrows( WorkUnavailableException.class, () -> fx.notices.dispatch( 100 ) );
        assertEquals( 0, fx.rows( "TRUE" ) );
        fx.directoryDown = false;
        fx.dispatchAll();
        assertEquals( 1, fx.rows( "recipient = 'otto'" ) );
    }

    @Test
    void aMutedWatcherHearsNothingUntilMentioned() throws SQLException {
        final Item item = fx.newItem( NotifyServiceFixture.LENA );
        fx.notices.setWatch( NotifyServiceFixture.OTTO, item.key(), WatchState.MUTED );
        fx.patch( NotifyServiceFixture.LENA, item, "status", "in_progress" );
        fx.service.addComment( NotifyServiceFixture.LENA, item.key(), "a plain comment" );
        fx.dispatchAll();
        assertEquals( 0, fx.rows( "recipient = 'otto'" ) );
        fx.service.addComment( NotifyServiceFixture.LENA, item.key(), "now @otto" );
        fx.dispatchAll();
        assertEquals( 1, fx.rows( "recipient = 'otto' AND reason = 'mentioned'" ) );
        assertEquals( WatchState.WATCHING, fx.notices.watchState( NotifyServiceFixture.OTTO, item.key() ),
            "a mention un-mutes" );
    }

    @Test
    void metricsCountDeliveredAndDropped() throws SQLException {
        final Item item = fx.newItem( NotifyServiceFixture.LENA );
        fx.patch( NotifyServiceFixture.LENA, item, "assignee", "otto" );
        fx.store.removeMember( fx.project.id(), "otto", "user", NotifyServiceFixture.ROOT );
        final Item second = fx.newItem( NotifyServiceFixture.LENA );
        fx.patch( NotifyServiceFixture.LENA, second, "assignee", "vera" );
        fx.dispatchAll();
        assertEquals( 1, fx.notified.stream().filter( "inbox/dropped"::equals ).count() );
        assertEquals( 1, fx.notified.stream().filter( "inbox/delivered"::equals ).count() );
    }
}
