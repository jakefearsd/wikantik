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
import java.time.Instant;
import java.util.List;

import static com.wikantik.work.CodeEvents.A;
import static com.wikantik.work.CodeEvents.ANA;
import static com.wikantik.work.CodeEvents.B;
import static com.wikantik.work.CodeEvents.MIA;
import static com.wikantik.work.CodeEvents.OCTO;
import static com.wikantik.work.CodeEvents.at;
import static com.wikantik.work.CodeEvents.commit;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Branch pushes and pull-request steps become change records, over real PostgreSQL. */
@RequiresPostgres
class WorkCodeHostApplyPostgresTest {

    private CodeHostFixture f;
    private HookDelivery d;
    private Item item;
    private String branch;

    @BeforeEach
    void setUp() throws SQLException {
        f = new CodeHostFixture();
        f.emails.put( "mia@example.com", "mia" );
        d = f.delivery( "github" );
        item = f.task();
        branch = "work/" + item.key() + "-fix-login";
    }

    private WorkCodeHostService.Applied apply( final CodeEvent... events ) throws SQLException {
        return f.hooks.apply( d, List.of( events ) );
    }

    private CodeEvent push( final String head, final String hhmm ) {
        return CodeEvents.push( branch, head, at( "11:00" ),
            commit( head, "work in progress", at( hhmm ), true, ANA ) );
    }

    @Test
    void aBranchPushNamingAnItemOpensAChange() throws SQLException {
        final WorkCodeHostService.Applied r = apply( CodeEvents.push( branch, B, at( "11:00" ),
            commit( A, "first", at( "09:00" ), true, ANA ), commit( B, "second", at( "10:00" ), true, ANA ) ) );
        assertEquals( new WorkCodeHostService.Applied( 1, 0, 0 ), r );
        final Change c = f.only( item );
        assertEquals( "codehost", c.source() );
        assertEquals( "ana", c.openedBy() );
        assertNull( c.externalOpenedBy() );
        assertEquals( B, c.headSha() );
        assertEquals( at( "09:00" ), c.firstCommitAt() );
        assertEquals( branch, c.branch() );
        assertEquals( "main", c.baseBranch() );
        assertEquals( ChangeState.OPEN, c.state() );
        assertEquals( 1, f.count( "SELECT count(*) FROM work_event WHERE type = 'change_opened' AND actor_kind = 'system' "
            + "AND actor = 'codehost:github' AND from_value = 'change:" + c.id() + "' AND to_value = '" + branch
            + "' AND note IS NULL" ) );
        assertEquals( 1, f.count( "SELECT count(*) FROM work_event WHERE type = 'change_updated' AND from_value = 'change:"
            + c.id() + "' AND to_value = '" + B + "'" ), "each head the host reports is one change_updated" );
    }

    @Test
    void aSecondPushMovesTheHeadAndKeepsTheEarliestCommitTime() throws SQLException {
        apply( push( A, "09:00" ) );
        apply( push( B, "10:00" ) );
        final Change c = f.only( item );
        assertEquals( B, c.headSha() );
        assertEquals( at( "09:00" ), c.firstCommitAt() );
        assertEquals( 1, f.count( "SELECT count(*) FROM work_event WHERE type = 'change_updated' AND to_value = '" + B + "'" ) );
    }

    @Test
    void aPullRequestOpenedAdoptsTheBranchChangeAndRecordsItsLink() throws SQLException {
        apply( push( A, "09:00" ) );
        final long id = f.only( item ).id();
        apply( CodeEvents.opened( branch, "12", A, at( "11:30" ) ) );
        final Change c = f.only( item );
        assertEquals( id, c.id() );
        assertEquals( "12", c.reviewNumber() );
        assertEquals( "https://github.com/acme/wk/pull/12", c.reviewUrl() );
        assertEquals( ChangeState.IN_REVIEW, c.state() );
        assertEquals( 1, f.count( "SELECT count(*) FROM work_event WHERE type = 'review_requested'" ) );
    }

    @Test
    void aPullRequestOpenedWithNoPriorPushOpensItsOwnChange() throws SQLException {
        apply( CodeEvents.opened( branch, "12", A, at( "11:30" ) ) );
        final Change c = f.only( item );
        assertEquals( "12", c.reviewNumber() );
        assertEquals( A, c.headSha() );
        assertEquals( "ana", c.openedBy() );
        assertEquals( ChangeState.IN_REVIEW, c.state() );
    }

    @Test
    void aDraftPullRequestStaysOpenUntilReady() throws SQLException {
        apply( CodeEvents.openedDraft( branch, "12", A, at( "11:30" ) ) );
        assertEquals( ChangeState.OPEN, f.only( item ).state() );
        apply( CodeEvents.ready( branch, "12", A, at( "11:40" ) ) );
        assertEquals( ChangeState.IN_REVIEW, f.only( item ).state() );
    }

    @Test
    void anApprovalRecordsTheCommitItApproved() throws SQLException {
        apply( CodeEvents.opened( branch, "12", A, at( "11:30" ) ) );
        apply( CodeEvents.approved( branch, "12", A, at( "11:45" ), MIA ) );
        final Change c = f.only( item );
        assertTrue( c.approvedForHead() );
        assertEquals( "mia", c.reviewedBy() );
        assertNull( c.externalReviewedBy() );
        assertEquals( A, c.reviewedHeadSha() );
        assertEquals( at( "11:45" ), c.reviewedAt() );
        assertEquals( ChangeState.IN_REVIEW, c.state() );
        assertEquals( 1, f.count( "SELECT count(*) FROM work_event WHERE type = 'change_reviewed' AND to_value = 'approved' "
            + "AND note IS NULL AND actor_kind = 'system'" ) );
    }

    @Test
    void anUnmatchedReviewerIsRecordedByName() throws SQLException {
        apply( CodeEvents.opened( branch, "12", A, at( "11:30" ) ) );
        apply( CodeEvents.approved( branch, "12", A, at( "11:45" ), OCTO ) );
        final Change c = f.only( item );
        assertNull( c.reviewedBy() );
        assertEquals( "octocat", c.externalReviewedBy() );
        assertTrue( c.approvedForHead() );
    }

    @Test
    void anApprovalForAnOlderCommitNeverStandsButIsLoggedOnce() throws SQLException {
        apply( CodeEvents.opened( branch, "12", A, at( "11:30" ) ) );
        apply( CodeEvents.updated( branch, "12", B, at( "11:35" ) ) );
        final WorkCodeHostService.Applied r = apply( CodeEvents.approved( branch, "12", A, at( "11:45" ), MIA ) );
        assertEquals( new WorkCodeHostService.Applied( 1, 0, 0 ), r, "the host's approval of A is on record" );
        assertNull( f.only( item ).reviewedHeadSha(), "an approval of a commit since replaced never counts" );
        assertEquals( 1, f.count( "SELECT count(*) FROM work_event WHERE type = 'change_reviewed'" ),
            "logged as in the host's order, where it came before the new commit" );
        assertEquals( new WorkCodeHostService.Applied( 0, 1, 0 ),
            apply( CodeEvents.approved( branch, "12", A, at( "11:45" ), MIA ) ) );
    }

    @Test
    void aNewCommitClearsAStandingApproval() throws SQLException {
        apply( CodeEvents.opened( branch, "12", A, at( "11:30" ) ) );
        apply( CodeEvents.approved( branch, "12", A, at( "11:45" ), MIA ) );
        apply( CodeEvents.updated( branch, "12", B, at( "11:50" ) ) );
        final Change c = f.only( item );
        assertEquals( B, c.headSha() );
        assertNull( c.reviewedHeadSha() );
        assertNull( c.reviewedBy() );
        assertFalse( c.approvedForHead() );
    }

    @Test
    void aPullRequestClosedWithoutMergingIsAbandoned() throws SQLException {
        apply( CodeEvents.opened( branch, "12", A, at( "11:30" ) ) );
        apply( CodeEvents.closed( branch, "12", A, at( "11:55" ) ) );
        final Change c = f.only( item );
        assertEquals( ChangeState.ABANDONED, c.state() );
        assertEquals( at( "11:55" ), c.closedAt() );
        assertEquals( 1, f.count( "SELECT count(*) FROM work_event WHERE type = 'change_abandoned' "
            + "AND note = 'closed on the code host'" ) );
        assertEquals( new WorkCodeHostService.Applied( 0, 1, 0 ),
            apply( CodeEvents.closed( branch, "12", A, at( "11:55" ) ) ), "no live change left to close" );
    }

    @Test
    void aBranchNamingTwoItemsMakesAChangeOnEach() throws SQLException {
        final Item other = f.task();
        final String both = "work/" + item.key() + "-" + other.key() + "-shared";
        final WorkCodeHostService.Applied r = apply( CodeEvents.push( both, A, at( "11:00" ),
            commit( A, "x", at( "09:00" ), true, ANA ) ) );
        assertEquals( 2, r.applied() );
        assertEquals( 1, f.changes( item ).size() );
        assertEquals( 1, f.changes( other ).size() );
    }

    @Test
    void anUnknownKeyAnEpicAnotherProductsItemAndUTF8AreIgnored() throws SQLException {
        final Item epic = f.epic();
        f.svc.createProduct( f.admin, new Product( 0, "other", "Other", null, "main", null, Autonomy.AUTONOMOUS,
            DeployGate.HUMAN, null ) );
        final Item foreign = f.svc.createItem( f.lena, new ItemDraft( "PLAT", WorkLevel.TASK, null, "other",
            WorkKind.SOFTWARE, "Elsewhere", "", null, null, null, "implementer", null, null ) ).item();
        final String name = "work/NOPE-9-" + epic.key() + "-" + foreign.key() + "-UTF-8-x";
        final WorkCodeHostService.Applied r = apply( CodeEvents.push( name, A, at( "11:00" ),
            commit( A, "x", at( "09:00" ), true, ANA ) ) );
        assertEquals( new WorkCodeHostService.Applied( 0, 0, 4 ), r );
        assertEquals( 0, f.count( "SELECT count(*) FROM work_change" ) );
        assertEquals( 0, f.count( "SELECT count(*) FROM work_event WHERE actor_kind = 'system'" ) );
    }

    @Test
    void anEventNamingNothingIsOneIgnored() throws SQLException {
        assertEquals( new WorkCodeHostService.Applied( 0, 0, 1 ), apply( CodeEvents.push( "feature/nothing", A,
            at( "11:00" ), commit( A, "x", at( "09:00" ), true, ANA ) ) ) );
    }

    @Test
    void anArchivedProjectsItemIsIgnored() throws SQLException {
        f.svc.archiveProject( f.admin, "PLAT" );
        assertEquals( new WorkCodeHostService.Applied( 0, 0, 1 ), apply( push( A, "09:00" ) ) );
        assertEquals( 0, f.count( "SELECT count(*) FROM work_change" ) );
    }

    @Test
    void aClosedItemGetsNoNewLiveChange() throws SQLException {
        f.set( f.lena, item, "status", "cancelled" );
        assertEquals( new WorkCodeHostService.Applied( 0, 0, 1 ), apply( push( A, "09:00" ) ) );
        assertEquals( new WorkCodeHostService.Applied( 0, 0, 1 ),
            apply( CodeEvents.opened( branch, "12", A, at( "11:30" ) ) ) );
        assertEquals( 0, f.count( "SELECT count(*) FROM work_change" ) );
    }

    @Test
    void aDeliveryFromAnotherRepositoryIsIgnored() throws SQLException {
        final CodeEvent base = push( A, "09:00" );
        final CodeEvent foreign = new CodeEvent( base.kind(), "evil/other", base.defaultBranch(), base.branch(), null,
            base.before(), base.after(), base.at(), base.commits(), null, null, null );
        assertEquals( new WorkCodeHostService.Applied( 0, 0, 1 ), apply( foreign ) );
        assertEquals( 0, f.count( "SELECT count(*) FROM work_change" ) );
    }

    @Test
    void anAgentsChangeIsAdoptedNotDuplicated() throws SQLException {
        f.svc.claims().claim( f.author, item.key() );
        final Change mine = f.svc.changes().open( f.author, item.key(), new ChangeDraft( branch, null, A, null, null ) );
        f.exec( "UPDATE work_change SET state = 'in_review', reviewed_by = 'reviewer-bot', reviewed_by_agent_id = "
            + f.reviewer.agentId() + ", reviewed_head_sha = '" + A + "', reviewed_at = '2026-03-01T08:00:00Z' WHERE id = "
            + mine.id() );
        apply( push( A, "09:00" ) );
        apply( CodeEvents.opened( branch, "12", A, at( "11:30" ) ) );
        final WorkCodeHostService.Applied approval = apply( CodeEvents.approved( branch, "12", A, at( "11:45" ), MIA ) );
        assertEquals( new WorkCodeHostService.Applied( 1, 0, 0 ), approval, "recorded against the commit it approved" );
        final Change c = f.only( item );
        assertEquals( mine.id(), c.id() );
        assertEquals( f.author.agentId(), c.openedByAgentId() );
        assertEquals( "author", c.openedBy() );
        assertEquals( List.of( f.author.agentId() ), c.headAgentIds() );
        assertEquals( f.reviewer.agentId(), c.reviewedByAgentId(), "an agent's approval stands" );
        assertEquals( "reviewer-bot", c.reviewedBy() );
        assertNull( c.externalReviewedBy() );
        assertEquals( "reported", c.source() );
        assertEquals( at( "09:00" ), c.firstCommitAt() );
        assertEquals( "12", c.reviewNumber() );
        assertEquals( "https://github.com/acme/wk/pull/12", c.reviewUrl() );
    }

    @Test
    void anUnmatchedAuthorIsRecordedByName() throws SQLException {
        apply( CodeEvents.push( branch, A, at( "11:00" ), commit( A, "x", at( "09:00" ), true, OCTO ) ) );
        final Change c = f.only( item );
        assertNull( c.openedBy() );
        assertEquals( "octocat", c.externalOpenedBy() );
    }

    @Test
    void aMatchedAuthorTeachesTheUsernameForNextTime() throws SQLException {
        apply( push( A, "09:00" ) );
        assertEquals( 1, f.count( "SELECT count(*) FROM work_codehost_identity WHERE host = 'github' "
            + "AND username = 'anagh' AND login = 'ana'" ) );
    }

    @Test
    void codeHostActivityNeverMovesTheItemStatusAndAnyDeliveryTouchesTheHook() throws SQLException {
        final WorkStatus before = f.svc.item( f.admin, item.key() ).item().status();
        apply( push( A, "09:00" ) );
        apply( CodeEvents.opened( branch, "12", A, at( "11:30" ) ) );
        apply( CodeEvents.approved( branch, "12", A, at( "11:45" ), MIA ) );
        apply( CodeEvents.closed( branch, "12", A, at( "11:55" ) ) );
        assertEquals( before, f.svc.item( f.admin, item.key() ).item().status() );
        assertEquals( 0, f.count( "SELECT count(*) FROM work_event WHERE item_id = " + item.id()
            + " AND type IN ('status','handoff')" ) );
        assertEquals( CodeHostFixture.START, f.hooks.hooks( f.admin ).get( "wk" ).lastReceivedAt() );
    }

    @Test
    void theHostTimeIsNeverLaterThanTheServiceClock() throws SQLException {
        apply( CodeEvents.opened( branch, "12", A, at( "11:30" ) ) );
        apply( CodeEvents.closed( branch, "12", A, Instant.parse( "2099-01-01T00:00:00Z" ) ) );
        assertEquals( CodeHostFixture.START, f.only( item ).closedAt() );
    }
}
