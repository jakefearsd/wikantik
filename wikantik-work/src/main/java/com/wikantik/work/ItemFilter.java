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

import java.time.LocalDate;
import java.util.Set;

/**
 * Criteria for {@link WorkStore#items}. Every field is optional: null (or empty for {@code statuses})
 * means "any". {@code text} is a case-insensitive substring match on title and body; {@code limit} of zero
 * or less means the store default. {@code order} null means {@link Order#KEY}.
 */
public record ItemFilter( Long projectId, Set< WorkStatus > statuses, String assignee, Long productId,
                          WorkLevel level, Long parentId, String text, LocalDate dueBefore, int limit, Order order ) {

    /** The order of a list: by key (project, then number), or by due date soonest first with no date last. */
    public enum Order {
        KEY, DUE;

        /** {@code key} or {@code due}. */
        public String wire() {
            return name().toLowerCase( java.util.Locale.ROOT );
        }
    }

    /** This filter with another project id and assignee; everything else kept. */
    public ItemFilter with( final Long newProjectId, final String newAssignee ) {
        return new ItemFilter( newProjectId, statuses, newAssignee, productId, level, parentId, text, dueBefore, limit,
            order );
    }
}
