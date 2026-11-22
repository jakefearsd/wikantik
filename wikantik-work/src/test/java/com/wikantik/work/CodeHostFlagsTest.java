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

import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

class CodeHostFlagsTest {

    private static EffectivePolicy policy( final boolean directAllowed ) {
        return new EffectivePolicy( Autonomy.DELEGATED, directAllowed, DoneMeans.MERGED );
    }

    private static final Instant T0 = Instant.parse( "2026-03-01T10:00:00Z" );

    private static Change change( final String head, final String reviewedHead, final String reviewedBy,
                                  final String externalReviewer ) {
        return new Change( 1, 1, 1, "b", "main", head, null, ChangeState.IN_REVIEW, null, null, "ana", null,
            reviewedBy, null, reviewedHead, reviewedHead == null ? null : T0, null, null, T0, null, List.of(), null,
            "codehost", null, externalReviewer, null, "12", null, null, null, null );
    }

    private static WorkCodeHostStore.ReviewFacts facts( final String login, final String external ) {
        return new WorkCodeHostStore.ReviewFacts( "12", null, "b", "main", "aaaaaaa1", T0,
            new CodeHostWho( login, external ) );
    }

    @Test
    void theFlagTable() {
        assertNull( CodeHostFlags.forMerge( MergeKind.REVIEWED, policy( false ) ) );
        assertNull( CodeHostFlags.forMerge( MergeKind.REVIEWED, policy( true ) ) );
        assertEquals( "unreviewed", CodeHostFlags.forMerge( MergeKind.UNREVIEWED, policy( false ) ) );
        assertEquals( "unreviewed", CodeHostFlags.forMerge( MergeKind.UNREVIEWED, policy( true ) ) );
        assertEquals( "direct", CodeHostFlags.forMerge( MergeKind.DIRECT, policy( false ) ) );
        assertNull( CodeHostFlags.forMerge( MergeKind.DIRECT, policy( true ) ) );
    }

    @Test
    void anOutsideApprovalMakesAReviewedMerge() {
        assertEquals( MergeKind.REVIEWED,
            CodeHostFlags.kind( change( "aaaaaaa1", "aaaaaaa1", "mia", null ), facts( "ana", "anagh" ) ) );
        assertEquals( MergeKind.REVIEWED,
            CodeHostFlags.kind( change( "aaaaaaa1", "aaaaaaa1", null, "octocat" ), facts( "ana", "anagh" ) ) );
    }

    @Test
    void aSelfApprovalByUsernameOrByLoginIsUnreviewed() {
        assertEquals( MergeKind.UNREVIEWED,
            CodeHostFlags.kind( change( "aaaaaaa1", "aaaaaaa1", null, "OctoCat" ), facts( null, "octocat" ) ) );
        assertEquals( MergeKind.UNREVIEWED,
            CodeHostFlags.kind( change( "aaaaaaa1", "aaaaaaa1", "ana", null ), facts( "ana", "anagh" ) ) );
    }

    @Test
    void noApprovalOrAnApprovalForAnotherCommitIsUnreviewed() {
        assertEquals( MergeKind.UNREVIEWED,
            CodeHostFlags.kind( change( "aaaaaaa1", null, null, null ), facts( "ana", "anagh" ) ) );
        assertEquals( MergeKind.UNREVIEWED,
            CodeHostFlags.kind( change( "bbbbbbb2", "aaaaaaa1", "mia", null ), facts( "ana", "anagh" ) ) );
    }

    private static java.util.Optional< JdbcCodeHostHeads.Approval > host( final String login, final String external,
                                                                          final Instant at ) {
        return java.util.Optional.of( new JdbcCodeHostHeads.Approval( login, external, at ) );
    }

    @Test
    void theHostsApprovalOfTheMergedCommitCountsOnlyIfStampedNoLaterThanTheMerge() {
        final Change unapproved = change( "aaaaaaa1", null, null, null );
        assertEquals( MergeKind.REVIEWED, CodeHostFlags.kind( unapproved, facts( "ana", "anagh" ), host( "mia", null, T0 ) ),
            "stamped at the merge's second" );
        assertEquals( MergeKind.UNREVIEWED, CodeHostFlags.kind( unapproved, facts( "ana", "anagh" ),
            host( "mia", null, T0.plusSeconds( 1 ) ) ), "stamped after the merge" );
        assertEquals( MergeKind.UNREVIEWED, CodeHostFlags.kind( unapproved, facts( "ana", "anagh" ),
            host( "ana", null, T0 ) ), "the author's own" );
        final Change standsFromHost = change( "aaaaaaa1", "aaaaaaa1", "mia", null );
        final WorkCodeHostStore.ReviewFacts earlierMerge = new WorkCodeHostStore.ReviewFacts( "12", null, "b", "main",
            "aaaaaaa1", T0.minusSeconds( 5 ), new CodeHostWho( "ana", "anagh" ) );
        assertEquals( MergeKind.UNREVIEWED, CodeHostFlags.kind( standsFromHost, earlierMerge, host( "mia", null, T0 ) ),
            "the host's approval standing on the change but stamped after the merge" );
        assertEquals( MergeKind.REVIEWED, CodeHostFlags.kind( change( "aaaaaaa1", "aaaaaaa1", "lena", null ),
            facts( "ana", "anagh" ), java.util.Optional.empty() ), "a person's standing approval" );
    }
}
