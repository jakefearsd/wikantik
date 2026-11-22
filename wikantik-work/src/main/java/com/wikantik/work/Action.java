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
 * An operation a caller may attempt on a project or item, with its policy attributes: the least project role it needs
 * ({@code null} for {@link #MANAGE_INSTANCE}, which is decided before any role is looked at), whether it is
 * management (an agent never does it) and whether it is fenced (an agent may not do it to an item another agent
 * holds the claim on). The constant order is stable.
 */
public enum Action {
    VIEW( WorkRole.VIEWER, false, false ),
    CREATE_ITEM( WorkRole.MEMBER, false, false ),
    EDIT_ITEM( WorkRole.MEMBER, false, true ),
    MOVE( WorkRole.MEMBER, false, true ),
    COMMENT( WorkRole.MEMBER, false, false ),
    LINK( WorkRole.MEMBER, false, true ),
    MANAGE_PROJECT( WorkRole.LEAD, true, false ),
    SET_POLICY( WorkRole.LEAD, true, false ),
    MANAGE_INSTANCE( null, true, false ),
    // Managing an agent is decided by sponsorship (AgentSponsorship), not by a role in one project; asked of a role
    // it takes a lead, the least that may ever register one.
    MANAGE_AGENT( WorkRole.LEAD, true, false ),
    CLAIM( WorkRole.MEMBER, false, false ),
    OPEN_CHANGE( WorkRole.MEMBER, false, true ),
    REVIEW_CHANGE( WorkRole.MEMBER, false, true ),
    MERGE_CHANGE( WorkRole.MEMBER, false, true ),
    ACKNOWLEDGE( WorkRole.MEMBER, false, false ),
    REQUEST_DEPLOYMENT( WorkRole.MEMBER, false, false ),
    APPROVE_DEPLOYMENT( WorkRole.MEMBER, false, false ),
    RUN_DEPLOYMENT( WorkRole.MEMBER, false, false ),
    // Sprints are planned by people: an agent is refused both (WorkPolicy), though it may estimate (EDIT_ITEM).
    MANAGE_SPRINT( WorkRole.LEAD, true, false ),
    PLAN_SPRINT( WorkRole.MEMBER, false, false ),
    // Sharing a saved filter with a project's members (Part 4); an agent has no saved filters (SavedFilters).
    SHARE_FILTER( WorkRole.MEMBER, false, false ),
    // Attaching a file (and removing one; the uploader-or-lead rule is WorkAttachmentsService's), fenced like an edit.
    ATTACH( WorkRole.MEMBER, false, true ),
    // A lead acknowledges a merge outside policy (flagged by the code-host service); an agent never does.
    ACKNOWLEDGE_FLAG( WorkRole.LEAD, true, false ),
    // Filing an item through intake takes any row in the project, a reporter's included; a person's act, never an
    // agent's (WorkPolicy.agentRefusal). Appended last: the constant order is stable.
    FILE_ITEM( WorkRole.REPORTER, false, false );

    private final WorkRole minRole;
    private final boolean management;
    private final boolean fenced;

    Action( final WorkRole minRole, final boolean management, final boolean fenced ) {
        this.minRole = minRole;
        this.management = management;
        this.fenced = fenced;
    }

    /** The least project role that may do this; null for {@link #MANAGE_INSTANCE}, which no project role grants. */
    public WorkRole minRole() {
        return minRole;
    }

    /** Whether this changes policy, a project, an agent or the instance: an agent never may. */
    public boolean management() {
        return management;
    }

    /** Whether an agent may not do this to an item another agent holds the claim on. */
    public boolean fenced() {
        return fenced;
    }
}
