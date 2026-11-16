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
import java.util.Set;

/**
 * What the assignment log is asked for. Every part is optional: no {@code projectKey} means every project the caller
 * can see, null {@code types} the log's own types ({@link WorkEvent#ASSIGNMENT_LOG_TYPES}), no {@code actorKind} or
 * {@code agentId} both kinds of actor, a {@code limit} of 0 or less the default page. {@code before} and
 * {@code beforeId} are the compound {@code (at, id)} cursor of the last row already seen.
 */
public record AssignmentLogQuery( String projectKey, Instant before, Long beforeId, Set< String > types,
                                  Actor.Kind actorKind, Long agentId, int limit ) {

    /** The first page of the whole log. */
    public static AssignmentLogQuery all() {
        return new AssignmentLogQuery( null, null, null, null, null, null, 0 );
    }
}
