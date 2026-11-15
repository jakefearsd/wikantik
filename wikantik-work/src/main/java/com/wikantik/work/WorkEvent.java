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
}
