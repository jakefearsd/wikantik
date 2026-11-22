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
 * One comment of a reporter's own item as they are shown it: the whole discussion as members see it (decision D12),
 * with the author as a public name (a person's display name, an agent's name as members see it) and no login, item
 * id or agent id. A removed comment has an empty body, HTML and mention list.
 */
public record ReportedComment( long id, String author, Actor.Kind authorKind, String body, String bodyHtml,
                               List< String > mentions, Instant createdAt, Instant editedAt, Instant removedAt ) {
}
