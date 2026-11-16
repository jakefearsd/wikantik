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

/**
 * One branch of work against one product, attached to one item. People are logins, agents are ids; an approval
 * names the commit it approved, so a later commit is not covered by it.
 */
public record Change( long id, long itemId, long productId, String branch, String baseBranch, String headSha,
                      String reviewUrl, ChangeState state, String mergedSha, MergeKind mergeKind, String openedBy,
                      Long openedByAgentId, String reviewedBy, Long reviewedByAgentId, String reviewedHeadSha,
                      Instant reviewedAt, String mergedBy, Long mergedByAgentId, Instant openedAt,
                      Instant closedAt ) {

    /** Whether an approval stands for the commit the change is at now. */
    public boolean approvedForHead() {
        return reviewedHeadSha != null && reviewedHeadSha.equals( headSha );
    }

    /** Open or in review: not yet merged or abandoned. */
    public boolean live() {
        return state == ChangeState.OPEN || state == ChangeState.IN_REVIEW;
    }
}
