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

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;

/** A work item: epic, story or task. */
public record Item( long id, long projectId, String key, WorkLevel level, Long parentId, Long productId,
                    WorkKind kind, String title, String body, WorkStatus status, int priority, double rank,
                    LocalDate dueOn, String assignee, String reporter, Autonomy autonomy, DoneMeans doneMeans,
                    Boolean allowDirect, int version, Instant createdAt, Instant updatedAt, Instant closedAt,
                    String queue, Long claimedByAgent, Instant claimedAt, Instant claimExpiresAt,
                    Long reporterAgentId, WorkType type, List< String > labels, Long sprintId,
                    BigDecimal estimate ) {

    /**
     * Defaults: no type means a feature, no labels means none; the label list is copied. An estimate is held at
     * scale one (half points), so {@code 3} and {@code 3.0} are the same value.
     */
    public Item {
        type = type == null ? WorkType.FEATURE : type;
        labels = labels == null ? List.of() : List.copyOf( labels );
        estimate = estimate == null ? null : estimate.setScale( 1, RoundingMode.UNNECESSARY );
    }
}
