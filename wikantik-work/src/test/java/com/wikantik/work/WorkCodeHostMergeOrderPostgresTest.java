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
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;

/**
 * A pull request's merge also arrives as a push to the default branch, for merge, squash and rebase merges, in either
 * order. However they arrive there is exactly one merged change that is not superseded, with the right kind and flag.
 */
@RequiresPostgres
class WorkCodeHostMergeOrderPostgresTest {

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
        // an approved pull request, ready to merge
        apply( CodeEvents.opened( branch, "12", A, at( "11:00" ) ), CodeEvents.approved( branch, "12", A, at( "11:10" ), MIA ) );
    }

    private WorkCodeHostService.Applied apply( final CodeEvent... events ) throws SQLException {
        return f.hooks.apply( d, List.of( events ) );
    }

    private CodeEvent merge() {
        return CodeEvents.merged( branch, "12", A, M, at( "11:20" ), ANA );
    }

    private CodeEvent mergeCommitPush() {
        return CodeEvents.push( "main", M, at( "11:21" ),
            commit( A, "wip " + item.key(), at( "09:00" ), false, ANA ),
            commit( M, "Merge pull request #12 from o/" + branch, at( "11:20" ), true, ANA ) );
    }

    private CodeEvent squashPush( final String message ) {
        return CodeEvents.push( "main", M, at( "11:21" ), commit( M, message, at( "11:20" ), true, ANA ) );
    }

    private CodeEvent rebasePush() {
        return CodeEvents.push( "main", M, at( "11:21" ),
            commit( "fffffff6", item.key() + " first part", at( "11:20" ), true, ANA ),
            commit( M, item.key() + " second part", at( "11:20" ), true, ANA ) );
    }

    private void assertOneMergedChangeCountedOnce() throws SQLException {
        assertEquals( 1, f.count( "SELECT count(*) FROM work_change WHERE item_id = " + item.id()
            + " AND state = 'merged' AND superseded_by IS NULL" ), "merged changes that count" );
        assertEquals( 1, f.count( "SELECT count(*) FROM work_event e WHERE e.type = 'change_merged' AND e.item_id = "
            + item.id() + " AND NOT EXISTS ( SELECT 1 FROM work_event s WHERE s.type = 'change_superseded' "
            + "AND s.item_id = e.item_id AND s.from_value = e.from_value )" ), "merge events not superseded" );
    }

    private Change theMerge() throws SQLException {
        return f.changes( item ).stream().filter( c -> c.reviewNumber() != null ).findFirst().orElseThrow();
    }

    private void assertPullRequestMergeIsTheRecord() throws SQLException {
        final Change pr = theMerge();
        assertEquals( ChangeState.MERGED, pr.state() );
        assertEquals( MergeKind.REVIEWED, pr.mergeKind() );
        assertEquals( M, pr.mergedSha() );
        assertNull( pr.policyFlag() );
        assertNull( pr.supersededBy() );
    }

    @Test
    void mergeCommitPullRequestFirst() throws SQLException {
        assertEquals( 1, apply( merge() ).applied() );
        assertEquals( 0, apply( mergeCommitPush() ).applied() );
        assertEquals( 1, f.changes( item ).size(), "no direct change" );
        assertOneMergedChangeCountedOnce();
        assertPullRequestMergeIsTheRecord();
    }

    @Test
    void mergeCommitPushFirst() throws SQLException {
        assertEquals( new WorkCodeHostService.Applied( 0, 1, 0 ), apply( mergeCommitPush() ),
            "skipped: the pull request's head is among the push's commits, and its number is in the message" );
        assertEquals( 1, f.changes( item ).size() );
        apply( merge() );
        assertOneMergedChangeCountedOnce();
        assertPullRequestMergeIsTheRecord();
    }

    @Test
    void squashPullRequestFirst() throws SQLException {
        apply( merge() );
        assertEquals( 0, apply( squashPush( item.key() + " fix login (#12)" ) ).applied() );
        assertEquals( 1, f.changes( item ).size() );
        assertOneMergedChangeCountedOnce();
        assertPullRequestMergeIsTheRecord();
    }

    @Test
    void squashPushFirst() throws SQLException {
        assertEquals( 0, apply( squashPush( item.key() + " fix login (#12)" ) ).applied(), "the number names the pull request" );
        apply( merge() );
        assertEquals( 1, f.changes( item ).size() );
        assertOneMergedChangeCountedOnce();
        assertPullRequestMergeIsTheRecord();
    }

    @Test
    void squashPushFirstWithTheNumberEditedOut() throws SQLException {
        assertEquals( 1, apply( squashPush( item.key() + " fix login" ) ).applied(), "nothing says it is the pull request" );
        final Change direct = f.changes( item ).stream().filter( c -> c.mergeKind() == MergeKind.DIRECT ).findFirst()
            .orElseThrow();
        assertEquals( "direct", direct.policyFlag(), "direct commits are not allowed here" );
        apply( merge() );
        assertOneMergedChangeCountedOnce();
        assertPullRequestMergeIsTheRecord();
        final Change after = f.changes( item ).stream().filter( c -> c.id() == direct.id() ).findFirst().orElseThrow();
        assertEquals( theMerge().id(), after.supersededBy() );
        assertEquals( 1, f.count( "SELECT count(*) FROM work_event WHERE type = 'change_superseded' AND from_value = 'change:"
            + direct.id() + "' AND to_value = 'change:" + theMerge().id() + "'" ) );
        assertNotNull( theMerge().firstCommitAt(), "the earlier first commit time is kept" );
        // the squash commit (11:20) came after the pull request opened (11:00): a first commit is never after the
        // change opened, so the opening bounds it
        assertEquals( at( "11:00" ), theMerge().firstCommitAt() );
    }

    @Test
    void rebasePullRequestFirst() throws SQLException {
        apply( merge() );
        assertEquals( new WorkCodeHostService.Applied( 0, 1, 0 ), apply( rebasePush() ) );
        assertEquals( 1, f.changes( item ).size() );
        assertOneMergedChangeCountedOnce();
        assertPullRequestMergeIsTheRecord();
    }

    @Test
    void rebasePushFirst() throws SQLException {
        assertEquals( 1, apply( rebasePush() ).applied() );
        apply( merge() );
        assertEquals( 2, f.changes( item ).size(), "the superseded direct row stays as history" );
        assertOneMergedChangeCountedOnce();
        assertPullRequestMergeIsTheRecord();
        final Change direct = f.changes( item ).stream().filter( c -> c.mergeKind() == MergeKind.DIRECT ).findFirst()
            .orElseThrow();
        assertEquals( theMerge().id(), direct.supersededBy() );
    }

    @Test
    void aHeadMovedBeforeTheMergeIsStillOneMerge() throws SQLException {
        apply( CodeEvents.updated( branch, "12", B, at( "11:15" ) ) );
        apply( CodeEvents.merged( branch, "12", B, M, at( "11:20" ), ANA ) );
        assertOneMergedChangeCountedOnce();
        assertEquals( "unreviewed", theMerge().policyFlag(), "the approval was for the older commit" );
    }
}
