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
import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/** One append-only entry in the work event log. */
public record WorkEvent( long id, long projectId, Long itemId, String itemKey, Instant at, String actor,
                         Actor.Kind actorKind, Long agentId, String type, String fromValue, String toValue,
                         String note ) {
    public static final String CREATED = "created";
    public static final String EDITED = "edited";
    public static final String STATUS = "status";
    public static final String ASSIGNED = "assigned";
    public static final String HANDOFF = "handoff";
    public static final String FIELD = "field";
    public static final String COMMENTED = "commented";
    public static final String COMMENT_EDITED = "comment_edited";
    public static final String COMMENT_REMOVED = "comment_removed";
    public static final String LINKED = "linked";
    public static final String UNLINKED = "unlinked";
    public static final String MEMBER = "member";
    public static final String SETTINGS = "settings";
    public static final String CLAIMED = "claimed";
    public static final String CLAIM_RENEWED = "claim_renewed";
    public static final String RELEASED = "released";
    public static final String CLAIM_EXPIRED = "claim_expired";
    public static final String ACKNOWLEDGED = "acknowledged";
    public static final String CHANGE_OPENED = "change_opened";
    public static final String CHANGE_UPDATED = "change_updated";
    public static final String REVIEW_REQUESTED = "review_requested";
    public static final String CHANGE_REVIEWED = "change_reviewed";
    public static final String CHANGE_MERGED = "change_merged";
    public static final String CHANGE_ABANDONED = "change_abandoned";
    public static final String DEPLOYMENT_REQUESTED = "deployment_requested";
    public static final String SHIPPED = "shipped";
    public static final String ROLLED_BACK = "rolled_back";
    public static final String SPRINT_CREATED = "sprint_created";
    public static final String SPRINT_UPDATED = "sprint_updated";
    public static final String SPRINT_STARTED = "sprint_started";
    public static final String SPRINT_CLOSED = "sprint_closed";

    /**
     * What the assignment log shows, in the order its filters list them: assignments, hand-offs, status changes and
     * the life of a claim, plus {@code member} events that name an agent (an agent admitted to, re-roled in or removed
     * from a project; people's membership changes stay in the project feed). The store keeps only the agent ones of
     * the {@code member} type in the log query.
     */
    public static final Set< String > ASSIGNMENT_LOG_TYPES = Collections.unmodifiableSet( new LinkedHashSet<>( List.of(
        ASSIGNED, HANDOFF, STATUS, CLAIMED, CLAIM_RENEWED, RELEASED, CLAIM_EXPIRED, MEMBER ) ) );

    /** Every type of event this module writes: what a {@code types} filter may name. */
    public static final Set< String > ALL_TYPES = Collections.unmodifiableSet( new LinkedHashSet<>( List.of(
        CREATED, EDITED, STATUS, ASSIGNED, HANDOFF, FIELD, COMMENTED, COMMENT_EDITED, COMMENT_REMOVED, LINKED,
        UNLINKED, MEMBER, SETTINGS, CLAIMED, CLAIM_RENEWED, RELEASED, CLAIM_EXPIRED, SPRINT_CREATED, SPRINT_UPDATED,
        SPRINT_STARTED, SPRINT_CLOSED ) ) );
}
