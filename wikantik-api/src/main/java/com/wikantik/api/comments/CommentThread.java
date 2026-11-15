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
package com.wikantik.api.comments;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

/** A comment thread: one anchor, a status, and an ordered list of comments. */
public record CommentThread(
        UUID id,
        String canonicalId,
        TextQuoteSelector anchor,
        String status,          // "open" | "resolved" | "dismissed"
        String createdBy,
        Instant createdAt,
        String resolvedBy,
        Instant resolvedAt,
        List< Comment > comments,
        boolean agentAvailable ) {

    public static final String OPEN = "open";
    public static final String RESOLVED = "resolved";
    public static final String DISMISSED = "dismissed";

    /** True when any comment was written by a human who could edit the page, so an agent may act on it. */
    public boolean actionable() {
        return comments != null && comments.stream().anyMatch( c -> !c.viaAgent() && c.authorCouldEdit() );
    }
}
