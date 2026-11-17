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
 * Who may pass each gate of an item's path: approving it as ready, reviewing a change, merging it, closing the
 * item. The gates restrict agents only: a person is never refused here, whatever the level. Two rules hold at
 * every level and are not configurable: an agent never reviews or merges a change it opened or put a commit on.
 */
public final class WorkGates {

    static final String APPROVE = "A person approves work as ready at this autonomy level.";
    static final String CLOSE = "A person closes work at the supervised level.";
    static final String REVIEW_OWN = "An agent may not review its own change.";
    static final String MERGE_OWN = "An agent may not merge its own change.";
    static final String MERGE_SUPERVISED = "A person merges at the supervised level.";
    static final String MERGE_NOT_REVIEWER = "The agent that approved this change merges it.";
    static final String DIRECT = "Direct commits by an agent need a lead to allow them on this item or its epic.";

    private WorkGates() {
    }

    /** Moving work out of draft or refining. An agent may only at the autonomous level. */
    public static Decision mayApprove( final Actor a, final EffectivePolicy p ) {
        return person( a ) || p.level() == Autonomy.AUTONOMOUS ? Decision.allow() : Decision.deny( APPROVE );
    }

    /** Moving work to done. An agent may not at the supervised level. */
    public static Decision mayClose( final Actor a, final EffectivePolicy p ) {
        return person( a ) || p.level() != Autonomy.SUPERVISED ? Decision.allow() : Decision.deny( CLOSE );
    }

    /** Recording a review of {@code c}. */
    public static Decision mayReview( final Actor a, final Change c ) {
        return person( a ) || !authored( a, c ) ? Decision.allow() : Decision.deny( REVIEW_OWN );
    }

    /**
     * Reporting {@code c} merged. An agent may not when it is one of the change's authors, at the supervised
     * level, or when the approval standing on the current commit is another reviewer's. A change with no approval
     * for its current commit passes here: that is not about who is asking, and the caller answers it as
     * {@code not_approved}.
     */
    public static Decision mayMerge( final Actor a, final Change c, final EffectivePolicy p ) {
        if ( person( a ) ) {
            return Decision.allow();
        }
        if ( authored( a, c ) ) {
            return Decision.deny( MERGE_OWN );
        }
        if ( p.level() == Autonomy.SUPERVISED ) {
            return Decision.deny( MERGE_SUPERVISED );
        }
        final boolean anothers = c.approvedForHead() && !a.agentId().equals( c.reviewedByAgentId() );
        return anothers ? Decision.deny( MERGE_NOT_REVIEWER ) : Decision.allow();
    }

    /** Opening and merging a change in one step: a commit straight to the product's branch. */
    public static Decision mayOpenDirect( final Actor a, final EffectivePolicy p ) {
        return person( a ) || ( p.directAllowed() && p.level() != Autonomy.SUPERVISED )
            ? Decision.allow() : Decision.deny( DIRECT );
    }

    /** Whether a close by {@code a} leaves no person in the item's path, so one must acknowledge it. */
    public static boolean closesWithoutAPerson( final Actor a, final EffectivePolicy p ) {
        return !person( a ) && p.level() == Autonomy.AUTONOMOUS;
    }

    private static boolean person( final Actor a ) {
        return a.kind() == Actor.Kind.HUMAN;
    }

    /** Whether the agent opened the change or set one of its commits: it is then its own work, not another's. */
    private static boolean authored( final Actor a, final Change c ) {
        return a.agentId() != null
            && ( a.agentId().equals( c.openedByAgentId() ) || c.headAgentIds().contains( a.agentId() ) );
    }
}
