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
 * What a reporter is shown of an item they filed: its own fields, the whole discussion and a timeline of four kinds
 * of event reshaped by {@link ReporterEvents} (every status move as a {@code status} event of status words; no note,
 * assignee, queue or agent id). People appear by their public names only: {@code reporter}, every event actor and
 * every comment author is a display name (an agent by its name), never a login. Deliberately no parent or children, links, changes, deployments, sprint,
 * estimate, priority, assignee, queue, claim, watchers, policy or counts of anything else: a reporter's view is built
 * from the item alone and has no component that could carry another item. The record's components are pinned by
 * {@code ReportedItemViewTest}.
 */
public record ReportedItemView( String key, String projectKey, String projectName, WorkLevel level, WorkType type,
                                WorkStatus status, String title, String body, String bodyHtml, String reporter,
                                Instant createdAt, Instant updatedAt, List< ReportedComment > comments,
                                List< ReportedEvent > events, boolean archived ) {
}
