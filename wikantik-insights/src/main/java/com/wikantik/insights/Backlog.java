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

import java.util.List;

/**
 * The result of {@link OpportunityEngine#evaluateGated} -- the priority-sorted opportunity
 * backlog, plus the rules that were suppressed and why (content-intelligence design §7.3.0). A
 * read surface renders both: the backlog is what to work on, and {@code suppressed} is what did
 * not run so the silence stays legible rather than mysterious.
 *
 * @param opportunities the merged, filtered, priority-sorted backlog
 * @param suppressed    rules that did not run for this evaluation, with the measured/required
 *                       values that explain why
 */
public record Backlog( List<Opportunity> opportunities, List<SuppressedRule> suppressed ) {
}
