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
package com.wikantik.insights;

import java.time.LocalDate;

/**
 * One row of {@code content_opportunity_snooze} (V053) -- a declined suggestion, keyed by
 * {@code (opportunityType, target)}.
 *
 * <p>{@code reason} is required at every call site, matching the {@code NOT NULL} constraint on
 * the column: a declined suggestion with no recorded reason is indistinguishable from a bug.</p>
 *
 * @param opportunityType the rule this snooze applies to (matches {@link Opportunity#type()})
 * @param target          the page path or query text this snooze applies to
 * @param snoozedUntil    the last day this snooze is active (inclusive)
 * @param reason          why the suggestion was declined; required
 * @param snoozedBy        login or agent identifier that created the snooze
 */
public record OpportunitySnooze( String opportunityType, String target, LocalDate snoozedUntil,
                                 String reason, String snoozedBy ) {
}
