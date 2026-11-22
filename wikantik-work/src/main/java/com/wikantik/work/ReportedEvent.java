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
 * One event of a reporter's own item as their timeline shows it, built from {@link ReporterEvents#reshape}: its id,
 * type ({@code created}, {@code status}, {@code commented} or {@code attached}), time, the actor's public name (a
 * person's display name, an agent's name as members see it, a code host's label; never a login or an id), the actor's
 * kind, and the two values a reshaped event keeps (status words, a comment id, an attachment and its file name). No
 * note, no item or project id, no agent id.
 */
public record ReportedEvent( long id, String type, Instant at, String actor, Actor.Kind actorKind, String fromValue,
                             String toValue ) {
}
