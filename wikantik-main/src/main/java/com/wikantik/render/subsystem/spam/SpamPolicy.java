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
package com.wikantik.render.subsystem.spam;

import com.wikantik.api.core.Context;
import com.wikantik.api.exceptions.RedirectException;

/**
 * Strategy aggregator for the decomposed SpamFilter.
 *
 * <p>Runs all checks via the three collaborators ({@link SpamRateLimiter},
 * {@link SpamPatternMatcher}, {@link SpamExternalSignals}) according to the
 * configured strategy (eager or score). Extracted from {@code SpamFilter} in
 * Phase 6 Checkpoint 3 of the wikantik-main subsystem decomposition.</p>
 */
public interface SpamPolicy {

    /**
     * Evaluates all applicable spam checks for the given context and content.
     *
     * @param context page context
     * @param content the new page content
     * @param change  pre-computed change record
     * @throws RedirectException if a check rejects the edit (eager strategy), or if
     *                           the accumulated score reaches the limit (score strategy)
     */
    void evaluate( Context context, String content, SpamChange change ) throws RedirectException;
}
