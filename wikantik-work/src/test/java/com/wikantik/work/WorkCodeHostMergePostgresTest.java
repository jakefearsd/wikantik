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

import static com.wikantik.work.CodeEvents.A;
import static com.wikantik.work.CodeEvents.ANA;
import static com.wikantik.work.CodeEvents.B;
import static com.wikantik.work.CodeEvents.M;
import static com.wikantik.work.CodeEvents.MIA;
import static com.wikantik.work.CodeEvents.at;
import static com.wikantik.work.CodeEvents.commit;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Merges and direct pushes the code host reports: how they count, and which are flagged. */
@RequiresPostgres
class WorkCodeHostMergePostgresTest {

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
        branch = "work/" + item.key() + "-fix";
    }

    private WorkCodeHostService.Applied apply( final CodeEvent... events ) throws SQLException {
        return f.hooks.apply( d, List.of( events ) );
    }

    private long events( final String type ) throws SQLException {
        return f.count( "SELECT count(*) FROM work_event WHERE type = '" + type + "' AND item_id = " + item.id() );
    }

    private CodeEvent directPush( final String message ) {
        return CodeEvents.push( "main", M, at( "12:00" ), commit( M, message, at( "11:50" ), true, ANA ) );
    }

    @Test
    void aMergeAfterApprovalIsReviewedAndUnflagged() throws SQLException {
        apply( CodeEvents.opened( branch, "12", A, at( "11:00" ) ), CodeEvents.approved( branch, "12", A, at( "11:10" ), MIA ) );
        apply( CodeEvents.merged( branch, "12", A, M, at( "11:20" ), ANA ) );
        final Change c = f.only( item );
        assertEquals( ChangeState.MERGED, c.state() );
        assertEquals( MergeKind.REVIEWED, c.mergeKind() );
        assertEquals( M, c.mergedSha() );
        assertEquals( "ana", c.mergedBy() );
        assertEquals( at( "11:20" ), c.closedAt() );
        assertNull( c.policyFlag() );
        assertEquals( 1, f.count( "SELECT count(*) FROM work_event WHERE type = 'change_merged' AND to_value = 'reviewed' "
            + "AND note = '" + M + "' AND actor_kind = 'system'" ) );
        assertEquals( 0, events( "change_flagged" ) );
    }

    @Test
    void aMatchedPersonsUnreviewedMergeIsFlagged() throws SQLException {
        apply( CodeEvents.opened( branch, "12", A, at( "11:00" ) ) );
        apply( CodeEvents.merged( branch, "12", A, M, at( "11:20" ), ANA ) );
        final Change c = f.only( item );
        assertEquals( MergeKind.UNREVIEWED, c.mergeKind() );
        assertEquals( "unreviewed", c.policyFlag(), "a person's own unreviewed merge is flagged too (D17)" );
        assertEquals( "ana", c.openedBy() );
        assertEquals( "ana", c.mergedBy() );
        assertEquals( 1, f.count( "SELECT count(*) FROM work_event WHERE type = 'change_flagged' AND to_value = 'unreviewed'" ) );
    }

    @Test
    void aSelfApprovalCountsAsUnreviewed() throws SQLException {
        apply( CodeEvents.opened( branch, "12", A, at( "11:00" ) ), CodeEvents.approved( branch, "12", A, at( "11:10" ), ANA ) );
        apply( CodeEvents.merged( branch, "12", A, M, at( "11:20" ), ANA ) );
        assertEquals( "unreviewed", f.only( item ).policyFlag() );
    }

    @Test
    void aMergeWithANewerHeadThanTheApprovalIsUnreviewed() throws SQLException {
        apply( CodeEvents.opened( branch, "12", A, at( "11:00" ) ), CodeEvents.approved( branch, "12", A, at( "11:10" ), MIA ) );
        apply( CodeEvents.merged( branch, "12", B, M, at( "11:20" ), ANA ) );
        final Change c = f.only( item );
        assertEquals( MergeKind.UNREVIEWED, c.mergeKind() );
        assertEquals( "unreviewed", c.policyFlag() );
        assertEquals( B, c.headSha() );
    }

    @Test
    void aDirectPushIsFlaggedOnlyWhereDirectCommitsAreNotAllowed() throws SQLException {
        final Item allowed = f.task();
        f.set( f.lena, allowed, "allowDirect", true );
        apply( directPush( item.key() + " fix the login" ) );
        final Change refused = f.only( item );
        assertEquals( MergeKind.DIRECT, refused.mergeKind() );
        assertEquals( "direct", refused.policyFlag() );
        assertEquals( M, refused.mergedSha() );
        assertEquals( "codehost", refused.source() );
        assertEquals( "ana", refused.openedBy() );
        assertEquals( "ana", refused.mergedBy() );
        assertEquals( at( "11:50" ), refused.firstCommitAt() );
        assertEquals( 1, events( "change_flagged" ) );
        apply( directPush( allowed.key() + " fix the login" ) );
        final Change fine = f.only( allowed );
        assertEquals( MergeKind.DIRECT, fine.mergeKind() );
        assertNull( fine.policyFlag() );
    }

    @Test
    void aDirectCommitNamingAClosedItemIsRecorded() throws SQLException {
        f.set( f.lena, item, "status", "cancelled" );
        assertEquals( 1, apply( directPush( item.key() + " late fix" ) ).applied() );
        assertEquals( ChangeState.MERGED, f.only( item ).state() );
    }

    @Test
    void aDirectPushNeverMovesTheItemStatus() throws SQLException {
        final WorkStatus before = f.svc.item( f.admin, item.key() ).item().status();
        apply( directPush( item.key() + " fix" ) );
        assertEquals( before, f.svc.item( f.admin, item.key() ).item().status() );
    }

    @Test
    void aDirectPushOnlyCountsCommitsTheHostCallsDistinct() throws SQLException {
        final WorkCodeHostService.Applied r = apply( CodeEvents.push( "main", M, at( "12:00" ),
            commit( M, item.key() + " seen before elsewhere", at( "11:50" ), false, ANA ) ) );
        assertEquals( new WorkCodeHostService.Applied( 0, 0, 1 ), r );
        assertEquals( 0, f.count( "SELECT count(*) FROM work_change" ) );
    }

    @Test
    void aMergeOfAPullRequestNeverSeenOpenedOpensAndMergesIt() throws SQLException {
        apply( CodeEvents.merged( branch, "12", A, M, at( "11:20" ), MIA ) );
        final Change c = f.only( item );
        assertEquals( ChangeState.MERGED, c.state() );
        assertEquals( "12", c.reviewNumber() );
        assertEquals( "mia", c.mergedBy() );
        assertEquals( "ana", c.openedBy() );
        assertEquals( "unreviewed", c.policyFlag(), "nobody approved what was merged" );
        assertEquals( 1, events( "change_opened" ) );
        assertEquals( 1, events( "change_merged" ) );
    }

    @Test
    void aMergeReportedByAnAgentIsNotOverwritten() throws SQLException {
        final Change mine = f.svc.changes().open( f.author, item.key(), new ChangeDraft( branch, null, A, null, null ) );
        f.exec( "UPDATE work_change SET state = 'merged', merge_kind = 'unreviewed', merged_sha = 'ddddddd4', "
            + "merged_by = 'reviewer-bot', merged_by_agent_id = " + f.reviewer.agentId() + ", closed_at = '2026-03-01T10:00:00Z' "
            + "WHERE id = " + mine.id() );
        final WorkCodeHostService.Applied r = apply( CodeEvents.merged( branch, "12", A, M, at( "11:20" ), ANA ) );
        assertEquals( new WorkCodeHostService.Applied( 0, 1, 0 ), r );
        final Change c = f.only( item );
        assertEquals( "ddddddd4", c.mergedSha() );
        assertEquals( "reviewer-bot", c.mergedBy() );
        assertEquals( f.reviewer.agentId(), c.mergedByAgentId() );
        assertNull( c.policyFlag() );
        assertEquals( 0, events( "change_flagged" ) );
    }

    @Test
    void aMergeNeverMovesTheItemStatus() throws SQLException {
        final WorkStatus before = f.svc.item( f.admin, item.key() ).item().status();
        apply( CodeEvents.opened( branch, "12", A, at( "11:00" ) ), CodeEvents.merged( branch, "12", A, M, at( "11:20" ), ANA ) );
        assertEquals( before, f.svc.item( f.admin, item.key() ).item().status() );
        assertTrue( f.only( item ).state() == ChangeState.MERGED );
    }
}
