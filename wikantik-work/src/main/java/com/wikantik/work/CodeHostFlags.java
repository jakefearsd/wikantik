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

/**
 * When a merge the code host reports is outside policy. Every merge the code-host service records is judged, whoever
 * the author is; merges reported through {@link WorkChangesService} never are (their gates already decided).
 */
final class CodeHostFlags {

    private CodeHostFlags() {
    }

    /** {@code unreviewed} for an unreviewed merge; {@code direct} for a direct commit where none is allowed; else null. */
    static String forMerge( final MergeKind kind, final EffectivePolicy policy ) {
        if ( kind == MergeKind.UNREVIEWED ) {
            return "unreviewed";
        }
        return kind == MergeKind.DIRECT && !policy.directAllowed() ? "direct" : null;
    }

    /**
     * How a pull request's merge counts: reviewed when an approval stands on the head that was merged and it was not
     * the author's own; unreviewed otherwise.
     */
    static MergeKind kind( final Change locked, final WorkCodeHostStore.ReviewFacts f ) {
        if ( !locked.approvedForHead() || isSelfApproval( locked, f.author() ) ) {
            return MergeKind.UNREVIEWED;
        }
        return MergeKind.REVIEWED;
    }

    /**
     * How a pull request's merge stamped {@code f.at()} counts, given {@code host}, the code host's approval of the
     * merged commit (the change's head): reviewed when the host approved that commit, not as the author, no later
     * than the merge; an approval of the host's standing on the change counts only that way (one stamped after the
     * merge does not); an agent's or a person's standing approval counts as in {@link #kind(Change,
     * WorkCodeHostStore.ReviewFacts)}.
     */
    static MergeKind kind( final Change locked, final WorkCodeHostStore.ReviewFacts f,
                           final java.util.Optional< JdbcCodeHostHeads.Approval > host ) {
        if ( host.isPresent() && !host.get().at().isAfter( f.at() )
                && !isSelf( new CodeHostWho( host.get().login(), host.get().external() ), f.author() ) ) {
            return MergeKind.REVIEWED;
        }
        return JdbcCodeHostHeads.hostStanding( locked, host ) ? MergeKind.UNREVIEWED : kind( locked, f );
    }

    private static boolean isSelfApproval( final Change c, final CodeHostWho author ) {
        if ( author == null ) {
            return false;
        }
        if ( c.reviewedBy() != null && c.reviewedBy().equals( author.login() ) ) {
            return true;
        }
        return c.externalReviewedBy() != null && author.external() != null
            && c.externalReviewedBy().equalsIgnoreCase( author.external() );
    }

    /** Whether {@code reviewer} is the pull request's author (same wiki login, or same host name ignoring case). */
    static boolean isSelf( final CodeHostWho reviewer, final CodeHostWho author ) {
        if ( reviewer == null || author == null ) {
            return false;
        }
        if ( reviewer.login() != null && reviewer.login().equals( author.login() ) ) {
            return true;
        }
        return reviewer.external() != null && !CodeHostWho.UNKNOWN.external().equals( reviewer.external() )
            && reviewer.external().equalsIgnoreCase( author.external() );
    }
}
