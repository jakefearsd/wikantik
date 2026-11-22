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
 * A write that cannot be applied as asked. {@link #reason()} says why: {@link #STALE} (the item changed since the
 * caller read it), {@link #ARCHIVED} (the project is archived and read-only) or {@link #EXISTS} (the key is taken).
 */
public class WorkConflictException extends RuntimeException {
    private static final long serialVersionUID = 1L;

    /** The item changed since the version the caller sent. */
    public static final String STALE = "stale";
    /** The project is archived: everything in it is read-only. */
    public static final String ARCHIVED = "archived";
    /** A project or product with that key already exists. */
    public static final String EXISTS = "exists";

    /** The item cannot be claimed: it is closed, assigned to someone, or held by another agent. */
    public static final String NOT_CLAIMABLE = "not_claimable";
    /** The caller does not hold a claim on the item. */
    public static final String NOT_CLAIMED = "not_claimed";

    /** The item has a change that is open or in review. */
    public static final String OPEN_CHANGES = "open_changes";
    /** The change has no approval for the commit being merged. */
    public static final String NOT_APPROVED = "not_approved";
    /** The change is already merged or abandoned. */
    public static final String CHANGE_CLOSED = "change_closed";
    /** The change has not been put up for review. */
    public static final String NOT_IN_REVIEW = "not_in_review";

    /** The deployment cannot move from the state it is in to the one asked for. */
    public static final String BAD_TRANSITION = "bad_transition";
    /** The item closes only when a deployment that carries it is verified. */
    public static final String AWAITING_DEPLOYMENT = "awaiting_deployment";

    /** The project already has an active sprint. */
    public static final String SPRINT_ACTIVE = "sprint_active";
    /** A closed sprint takes no more work and cannot be edited. */
    public static final String SPRINT_CLOSED = "sprint_closed";
    /** The target of the request is not one the operation can use. */
    public static final String BAD_TARGET = "bad_target";

    private final String reason;

    /** A stale-version conflict. */
    public WorkConflictException( final String message ) {
        this( message, STALE );
    }

    public WorkConflictException( final String message, final String reason ) {
        super( message );
        this.reason = reason;
    }

    /** {@link #STALE}, {@link #ARCHIVED} or {@link #EXISTS}. */
    public String reason() {
        return reason;
    }
}
