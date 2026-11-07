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
 * One stored draft as the administrators' drafts list shows it (section 7.7): the stored record overlaid with the live
 * session's state when it is in memory. {@code createdBy} is the author key, not a display name: this is an admin
 * surface, and the REST layer adds display names. {@code lastEditAt} is null for a draft never edited;
 * {@code participants} is 0 unless {@code live}; {@code publishPending} is true while a publish is in its save or its
 * pending-publish marker is stored.
 */
public record DraftListing( UUID sessionId, String pageName, String status, String createdBy, Instant createdAt,
                            Instant lastEditAt, boolean live, int participants, boolean publishPending ) {
    public DraftListing {
        Objects.requireNonNull( sessionId, "sessionId" );
        ModelChecks.nonBlank( pageName, "pageName" );
        Objects.requireNonNull( status, "status" );
        ModelChecks.nonBlank( createdBy, "createdBy" );
        Objects.requireNonNull( createdAt, "createdAt" );
        ModelChecks.nonNegative( participants, "participants" );
    }
}
