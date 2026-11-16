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
 * One row of the assignment log, flat so the REST layer shapes it exactly as it shapes any event: the event's own
 * fields plus the project it belongs to (key, name, whether it is archived) and the item's key and title. Titles
 * are visible to anyone who can see the project, which is everyone who can be handed this row.
 */
public record AssignmentLogEvent( long id, long projectId, String projectKey, String projectName,
                                  boolean projectArchived, Long itemId, String itemKey, String itemTitle, Instant at,
                                  String actor, Actor.Kind actorKind, Long agentId, String type, String fromValue,
                                  String toValue, String note ) {

    static AssignmentLogEvent of( final LogEntry entry, final Project project ) {
        final WorkEvent e = entry.event();
        return new AssignmentLogEvent( e.id(), e.projectId(), project.key(), project.name(),
            project.archivedAt() != null, e.itemId(), e.itemKey(), entry.itemTitle(), e.at(), e.actor(), e.actorKind(),
            e.agentId(), e.type(), e.fromValue(), e.toValue(), e.note() );
    }
}
