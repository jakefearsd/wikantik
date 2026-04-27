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
package com.wikantik.api.kgpolicy;

import java.time.Instant;

/**
 * One row in {@code kg_policy_audit}. Append-only.
 *
 * <p>{@code oldAction} is null on the first set for a cluster.
 *
 * <p>{@code newAction} is intentionally a free-form string rather than a
 * {@link ClusterAction} value: the audit log records action transitions
 * including non-policy events such as {@code cleared} (row deleted) and
 * {@code purged} (cluster's KG data hard-deleted). Valid values are:
 * {@code include}, {@code exclude}, {@code cleared}, {@code purged}.
 */
public record PolicyAuditEntry(
        long id,
        String cluster,
        String oldAction,
        String newAction,
        String reason,
        String actor,
        Instant changedAt
) {}
