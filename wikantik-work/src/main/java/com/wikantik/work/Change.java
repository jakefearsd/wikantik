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

import java.time.Instant;
import java.util.List;

/**
 * One branch of work against one product, attached to one item. People are logins, agents are ids; an approval
 * names the commit it approved, so a later commit is not covered by it. {@code headAgentIds} are the agents that
 * have set a commit of this change: its authors, with the agent that opened it. {@code source} is
 * {@code reported} (an agent or person said so) or {@code codehost}; the {@code external*By} names are code-host
 * people with no wiki login; {@code policyFlag} marks a merge outside policy ({@code unreviewed} or {@code direct});
 * {@code supersededBy} names the change a direct row was recognised as (never counted twice).
 */
public record Change( long id, long itemId, long productId, String branch, String baseBranch, String headSha,
                      String reviewUrl, ChangeState state, String mergedSha, MergeKind mergeKind, String openedBy,
                      Long openedByAgentId, String reviewedBy, Long reviewedByAgentId, String reviewedHeadSha,
                      Instant reviewedAt, String mergedBy, Long mergedByAgentId, Instant openedAt,
                      Instant closedAt, List< Long > headAgentIds, Instant firstCommitAt, String source,
                      String externalOpenedBy, String externalReviewedBy, String externalMergedBy,
                      String reviewNumber, String policyFlag, String flagAcknowledgedBy,
                      Instant flagAcknowledgedAt, Long supersededBy ) {

    public Change {
        headAgentIds = headAgentIds == null ? List.of() : List.copyOf( headAgentIds );
    }

    /** Whether an approval stands for the commit the change is at now. */
    public boolean approvedForHead() {
        return reviewedHeadSha != null && reviewedHeadSha.equals( headSha );
    }

    /** Whether a lead still has to acknowledge a merge outside policy (superseded rows never wait). */
    public boolean flagWaiting() {
        return policyFlag != null && flagAcknowledgedAt == null && supersededBy == null;
    }

    /** Open or in review: not yet merged or abandoned. */
    public boolean live() {
        return state.isLive();
    }
}
