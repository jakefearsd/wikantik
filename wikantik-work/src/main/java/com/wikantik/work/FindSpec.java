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
import java.time.LocalDate;
import java.util.Set;

/**
 * A find as the store runs it: every name already resolved (projects to ids, people to logins or agent ids, the
 * product to its id) and the visible set already applied. {@code projectIds} is the only thing that decides which
 * projects are searched; an empty set runs no query. {@code fetch} is the page size plus one (to know whether
 * another page follows); {@code after} is the decoded cursor, or null for the first page.
 */
record FindSpec( Set< Long > projectIds, Set< WorkStatus > statuses, Set< WorkLevel > levels, WorkType type,
                 Set< String > labels, Set< Integer > priorities, String assigneeLogin, Long assigneeAgentId,
                 String queue, String reporterLogin, Long reporterAgentId, Long productId, SprintRef sprint,
                 String watcherLogin, String text, LocalDate dueBefore, Instant updatedSince,
                 FindFilter.Sort sort, int fetch, FindCursor after ) {
}
