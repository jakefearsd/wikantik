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
package com.wikantik.audit;

import java.time.Instant;

/**
 * Filter for {@link AuditRepository#query(AuditQuery)}. Every component is optional ({@code null} = no
 * constraint).
 *
 * <p>{@code actor} is the value an operator types into the Actor filter: a login name / principal.
 * It matches {@code actor_principal} exactly (that is the column every production writer populates) and
 * also {@code actor_id} when a writer has set one.</p>
 */
public record AuditQuery(
        String actor, AuditCategory category, String eventType, String targetId,
        Instant from, Instant to, AuditOutcome outcome, int limit, long beforeSeq ) {

    public static AuditQuery all() {
        return new AuditQuery( null, null, null, null, null, null, null, 1000, Long.MAX_VALUE );
    }
}
