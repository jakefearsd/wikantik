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
package com.wikantik.collab.model;

import java.time.Instant;
import java.util.Objects;
import java.util.UUID;

/**
 * One stored draft without its bodies, for the drafts list and retention ({@code CollabStore#draftEntries}).
 * {@code lastEditAt} is the draft's real last edit: the later of the stored {@code last_edit_at} marker (written at
 * snapshot cadence and when a session leaves memory) and the newest participant edit still in the log (a text update,
 * or a meta op with an author), so a draft whose session never wrote its marker (a crash) is not aged by a stale one.
 * Null when the draft was never edited. {@code publishPending} is true while a pending-publish marker is stored.
 */
public record DraftEntry( UUID id, String pageName, String status, boolean hasUnpublished, boolean publishPending,
                          String createdBy, Instant createdAt, Instant lastEditAt ) {
    public DraftEntry {
        Objects.requireNonNull( id, "id" );
        ModelChecks.nonBlank( pageName, "pageName" );
        Objects.requireNonNull( status, "status" );
        ModelChecks.nonBlank( createdBy, "createdBy" );
        Objects.requireNonNull( createdAt, "createdAt" );
    }

    /** The time retention ages the draft by: its last edit, else its creation. */
    public Instant lastActivity() {
        return lastEditAt != null ? lastEditAt : createdAt;
    }
}
